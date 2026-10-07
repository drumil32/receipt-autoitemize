package com.navan.receipts.web;

/** Response body for GET /receipts/{id}/ocr. Serialized as {"ocr_text": "..."}. */
public record OcrResponse(String ocrText) {}
