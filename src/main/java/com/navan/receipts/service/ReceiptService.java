package com.navan.receipts.service;

import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.ConflictException;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final TransactionRepository transactions;
    private final FileStorageService storage;

    public ReceiptService(
            ReceiptRepository receipts, TransactionRepository transactions, FileStorageService storage) {
        this.receipts = receipts;
        this.transactions = transactions;
        this.storage = storage;
    }

    /** Stores the upload, or returns the existing receipt when identical bytes were seen before. */
    public UploadResult upload(String filename, byte[] bytes) {
        String hash = sha256(bytes);

        var existing = receipts.findByContentHash(hash);
        if (existing.isPresent()) {
            log.info("Duplicate upload; returning existing receipt {}", existing.get().getId());
            // TODO(metric): increment receipt.upload.duplicate counter
            return new UploadResult(existing.get(), false);
        }

        // File first: if this throws, no DB row is created, so nothing to revert.
        String path = storage.store(hash, bytes);
        Receipt receipt = new Receipt();
        receipt.setOriginalFilename(filename);
        receipt.setContentHash(hash);
        receipt.setStoragePath(path);
        try {
            Receipt saved = receipts.save(receipt);
            log.info("Stored new receipt {} ({})", saved.getId(), filename);
            // TODO(metric): increment receipt.upload.created counter
            return new UploadResult(saved, true);
        } catch (DataAccessException failure) {
            // The insert failed. Re-read to tell a concurrent-duplicate race from a real error
            // without depending on the provider's exception mapping (SQLite reports the unique
            // violation as a generic JpaSystemException, Postgres as DataIntegrityViolationException).
            var winner = receipts.findByContentHash(hash);
            if (winner.isPresent()) {
                // A concurrent identical upload won; the file is content-addressed and shared.
                log.warn("Concurrent identical upload; returning winner receipt {}", winner.get().getId());
                // TODO(metric): increment receipt.upload.race counter
                return new UploadResult(winner.get(), false);
            }
            // Genuine failure: compensate by removing the orphan file, then surface it.
            log.error("DB save failed after storing file {}; deleting orphan", path, failure);
            // TODO(metric): increment receipt.upload.failure counter
            try {
                storage.delete(path);
            } catch (RuntimeException ignored) {
                // best-effort cleanup; surface the original failure
            }
            throw failure;
        }
    }

    /** Returns the receipt, or throws {@link ResourceNotFoundException} if the id is unknown. */
    public Receipt getReceipt(String id) {
        return receipts.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + id));
    }

    /** The id of this receipt's transaction, or null if it has not been processed. */
    public String transactionIdFor(String receiptId) {
        return transactions.findByReceipt_Id(receiptId).map(Transaction::getId).orElse(null);
    }

    /** Stored OCR text. 404 if the receipt is missing, 409 if it has not been processed yet. */
    public String getOcrText(String id) {
        Receipt receipt = getReceipt(id);
        if (receipt.getOcrText() == null) {
            throw new ConflictException("Receipt not processed yet: " + id);
        }
        return receipt.getOcrText();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Outcome of an upload: the receipt, and whether it was newly created. */
    public record UploadResult(Receipt receipt, boolean created) {}
}
