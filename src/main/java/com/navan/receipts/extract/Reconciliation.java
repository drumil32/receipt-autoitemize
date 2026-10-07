package com.navan.receipts.extract;

import java.math.BigDecimal;

/** The single reconciliation rule: line items + taxes must equal the grand total. */
public final class Reconciliation {

    private Reconciliation() {}

    public static boolean reconciles(BigDecimal itemsTotal, BigDecimal taxesTotal, BigDecimal grandTotal) {
        return itemsTotal.add(taxesTotal).compareTo(grandTotal) == 0;
    }
}
