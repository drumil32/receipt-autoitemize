package com.navan.receipts.error;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/** Thrown when edited line items + stored taxes do not reconcile with the grand total (409). */
public class MismatchException extends ApiException {

    private final BigDecimal itemsTotal;
    private final BigDecimal taxesTotal;
    private final BigDecimal grandTotal;

    public MismatchException(BigDecimal itemsTotal, BigDecimal taxesTotal, BigDecimal grandTotal) {
        super(HttpStatus.CONFLICT, "Line items plus taxes do not reconcile with the grand total");
        this.itemsTotal = itemsTotal;
        this.taxesTotal = taxesTotal;
        this.grandTotal = grandTotal;
    }

    public BigDecimal getItemsTotal() {
        return itemsTotal;
    }

    public BigDecimal getTaxesTotal() {
        return taxesTotal;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    /** (items + taxes) - grand_total: negative = short, positive = over. */
    public BigDecimal getDifference() {
        return itemsTotal.add(taxesTotal).subtract(grandTotal);
    }
}
