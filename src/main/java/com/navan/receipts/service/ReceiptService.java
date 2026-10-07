package com.navan.receipts.service;

import com.navan.receipts.domain.Receipt;
import com.navan.receipts.repository.ReceiptRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final FileStorageService storage;

    public ReceiptService(ReceiptRepository receipts, FileStorageService storage) {
        this.receipts = receipts;
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
        } catch (DataIntegrityViolationException race) {
            // A concurrent identical upload won the unique(content_hash). The file is
            // content-addressed and shared with the winner, so keep it; return the winner.
            Receipt winner = receipts.findByContentHash(hash).orElseThrow();
            log.warn("Concurrent identical upload; returning winner receipt {}", winner.getId());
            // TODO(metric): increment receipt.upload.race counter
            return new UploadResult(winner, false);
        } catch (RuntimeException dbFailure) {
            // DB save failed for another reason: compensate by removing the orphan file.
            log.error("DB save failed after storing file {}; deleting orphan", path, dbFailure);
            // TODO(metric): increment receipt.upload.failure counter
            try {
                storage.delete(path);
            } catch (RuntimeException ignored) {
                // best-effort cleanup; surface the original failure
            }
            throw dbFailure;
        }
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
