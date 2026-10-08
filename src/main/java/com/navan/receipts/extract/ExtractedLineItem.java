package com.navan.receipts.extract;

import java.math.BigDecimal;

/**
 * A line item's data (net amount), as extracted from a receipt or supplied by a PATCH edit.
 *
 * <p>{@code taxAmount} and {@code quantity} are optional per-item attributes. The current text
 * parser doesn't read them (they arrive null via the 2-arg constructor); they're populated today
 * only through {@code PATCH /transactions/{id}/items}. When OCR learns to read them, the extractor
 * just starts passing them here — no new type or mapping needed.
 */
public record ExtractedLineItem(
        String description, BigDecimal amount, BigDecimal taxAmount, Integer quantity) {

    /** Convenience for the extractor and tests: description + amount only, optional fields null. */
    public ExtractedLineItem(String description, BigDecimal amount) {
        this(description, amount, null, null);
    }
}
