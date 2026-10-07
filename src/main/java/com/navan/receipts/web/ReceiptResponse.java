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

    public static ReceiptResponse from(Receipt receipt) {
        // TODO: populate transactionId once process/transactions exist
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getOriginalFilename(),
                receipt.getUploadedAt(),
                receipt.isProcessed(),
                null);
    }
}
