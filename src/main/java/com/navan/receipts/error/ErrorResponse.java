package com.navan.receipts.error;

/** Consistent error body for failed requests. */
public record ErrorResponse(int status, String error, String message) {}
