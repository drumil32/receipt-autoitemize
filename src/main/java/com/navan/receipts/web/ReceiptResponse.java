package com.navan.receipts.web;

import com.navan.receipts.domain.Receipt;
import java.time.Instant;

/** Response body for GET /receipts/{id}. */
public record ReceiptResponse(
        String id,
        String filename,
        Instant uploadedAt,
        boolean processed,
        String transactionId) {

    public static ReceiptResponse from(Receipt receipt, String transactionId) {
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getOriginalFilename(),
                receipt.getUploadedAt(),
                receipt.isProcessed(),
                transactionId);
    }
}
