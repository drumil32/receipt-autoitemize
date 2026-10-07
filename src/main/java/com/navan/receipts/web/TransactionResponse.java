package com.navan.receipts.web;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Response body for a transaction: header + taxes + line items + status + receipt_id. */
public record TransactionResponse(
        String id,
        String receiptId,
        String merchant,
        LocalDate date,
        String currency,
        BigDecimal grandTotal,
        ItemizeStatus itemizeStatus,
        List<TaxResponse> taxes,
        List<LineItemResponse> lineItems) {

    public record TaxResponse(String name, BigDecimal rate, BigDecimal amount) {}

    public record LineItemResponse(
            String description, BigDecimal amount, BigDecimal taxAmount, Integer quantity) {}

    public static TransactionResponse from(Transaction t) {
        List<TaxResponse> taxes = t.getTaxes().stream()
                .map(tax -> new TaxResponse(tax.getName(), tax.getRate(), tax.getAmount()))
                .toList();
        List<LineItemResponse> items = t.getLineItems().stream()
                .map(i -> new LineItemResponse(
                        i.getDescription(), i.getAmount(), i.getTaxAmount(), i.getQuantity()))
                .toList();
        return new TransactionResponse(
                t.getId(),
                t.getReceipt().getId(),
                t.getMerchant(),
                t.getDate(),
                t.getCurrency(),
                t.getGrandTotal(),
                t.getItemizeStatus(),
                taxes,
                items);
    }
}
