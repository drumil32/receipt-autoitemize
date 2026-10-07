package com.navan.receipts.extract;

import java.math.BigDecimal;

/** A line item parsed from OCR text (net amount). */
public record ExtractedLineItem(String description, BigDecimal amount) {}
