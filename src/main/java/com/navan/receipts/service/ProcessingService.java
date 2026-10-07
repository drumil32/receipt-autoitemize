package com.navan.receipts.service;

import com.navan.receipts.domain.Receipt;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.error.UnprocessableEntityException;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.extract.ReceiptExtractor;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.service.TransactionService.UpsertResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Orchestrates process: OCR + extraction (outside the DB tx), then the transactional upsert. */
@Service
@Slf4j
public class ProcessingService {

    private final ReceiptRepository receipts;
    private final OcrService ocr;
    private final ReceiptExtractor extractor;
    private final TransactionService transactionService;

    public ProcessingService(
            ReceiptRepository receipts,
            OcrService ocr,
            ReceiptExtractor extractor,
            TransactionService transactionService) {
        this.receipts = receipts;
        this.ocr = ocr;
        this.extractor = extractor;
        this.transactionService = transactionService;
    }

    public UpsertResult process(String receiptId) {
        Receipt receipt = receipts.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));

        String ocrText = ocr.readText(receipt);
        ExtractedReceipt extracted;
        try {
            extracted = extractor.extract(ocrText);
        } catch (UnprocessableEntityException e) {
            log.warn("Receipt {} could not be extracted: {}", receiptId, e.getMessage());
            // TODO(metric): increment process.failed counter
            throw e; // still returns 422
        }

        UpsertResult result;
        try {
            result = transactionService.upsert(receiptId, ocrText, extracted);
        } catch (DataAccessException race) {
            // A concurrent first-process won the unique(receipt_id); the failed insert rolled
            // back. Retry in a fresh transaction: it now finds the winner's row and updates it.
            log.warn("Concurrent process race for receipt {}; retrying as update", receiptId);
            // TODO(metric): increment process.race counter
            result = transactionService.upsert(receiptId, ocrText, extracted);
        }

        log.info("Processed receipt {} -> transaction {} ({})",
                receiptId, result.transaction().getId(), result.transaction().getItemizeStatus());
        // TODO(metric): increment process.result{status=<itemizeStatus>} counter
        return result;
    }
}
