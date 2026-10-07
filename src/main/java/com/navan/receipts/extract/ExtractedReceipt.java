package com.navan.receipts.extract;

import com.navan.receipts.domain.ItemizeStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Structured result of parsing a receipt's OCR text. */
public record ExtractedReceipt(
        String merchant,
        LocalDate date,
        String currency,
        BigDecimal grandTotal,
        List<ExtractedTax> taxes,
        List<ExtractedLineItem> lineItems,
        ItemizeStatus itemizeStatus) {}
