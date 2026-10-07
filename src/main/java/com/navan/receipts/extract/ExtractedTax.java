package com.navan.receipts.extract;

import java.math.BigDecimal;

/** A tax parsed from OCR text. Rate is a fraction (0.19 = 19%). */
public record ExtractedTax(String name, BigDecimal rate, BigDecimal amount) {}
