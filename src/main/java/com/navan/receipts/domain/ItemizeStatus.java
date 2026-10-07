package com.navan.receipts.domain;

/** Reconciliation state of a transaction's line items. */
public enum ItemizeStatus {
    COMPLETE,
    NEEDS_REVIEW,
    FAILED
}
