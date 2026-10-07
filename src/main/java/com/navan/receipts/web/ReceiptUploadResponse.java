package com.navan.receipts.web;

/** Response body for POST /receipts. Serialized as {"receipt_id": "..."}. */
public record ReceiptUploadResponse(String receiptId) {}
