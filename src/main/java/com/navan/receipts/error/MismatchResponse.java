package com.navan.receipts.error;

import java.math.BigDecimal;

/** 409 body for a reconciliation mismatch; shows the caller the numbers that didn't add up. */
public record MismatchResponse(
        int status,
        String error,
        String message,
        BigDecimal itemsTotal,
        BigDecimal taxesTotal,
        BigDecimal grandTotal,
        BigDecimal difference) {}
