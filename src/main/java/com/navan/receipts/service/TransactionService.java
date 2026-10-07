package com.navan.receipts.service;

import com.navan.receipts.domain.LineItem;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Tax;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final ReceiptRepository receipts;
    private final TransactionRepository transactions;

    public TransactionService(ReceiptRepository receipts, TransactionRepository transactions) {
        this.receipts = receipts;
        this.transactions = transactions;
    }

    /**
     * Creates or updates the one transaction for this receipt, storing OCR text and the
     * extracted header, taxes and line items in a single commit. Separate bean + method so
     * the transaction boundary applies and the OCR/extract work stays outside it.
     */
    @Transactional
    public UpsertResult upsert(String receiptId, String ocrText, ExtractedReceipt extracted) {
        Receipt receipt = receipts.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
        receipt.setOcrText(ocrText);
        receipt.setProcessed(true);

        var existing = transactions.findByReceipt_Id(receiptId);
        Transaction txn = existing.orElseGet(Transaction::new);
        boolean created = existing.isEmpty();
        if (created) {
            txn.setReceipt(receipt);
        }

        txn.setMerchant(extracted.merchant());
        txn.setDate(extracted.date());
        txn.setCurrency(extracted.currency());
        txn.setGrandTotal(extracted.grandTotal());
        txn.setItemizeStatus(extracted.itemizeStatus());
        txn.replaceTaxes(toTaxes(extracted));
        txn.replaceLineItems(toLineItems(extracted));

        return new UpsertResult(transactions.save(txn), created);
    }

    /** Returns a transaction by id with its children loaded. 404 if unknown. */
    @Transactional(readOnly = true)
    public Transaction getTransaction(String id) {
        return initChildren(transactions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id)));
    }

    /** Returns the one transaction for a receipt with its children loaded. 404 if none. */
    @Transactional(readOnly = true)
    public Transaction getByReceiptId(String receiptId) {
        return initChildren(transactions.findByReceipt_Id(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("No transaction for receipt: " + receiptId)));
    }

    /** Force-initialize lazy collections inside the session so the DTO can map them later. */
    private static Transaction initChildren(Transaction txn) {
        txn.getTaxes().size();
        txn.getLineItems().size();
        return txn;
    }

    private static List<Tax> toTaxes(ExtractedReceipt extracted) {
        return extracted.taxes().stream().map(t -> {
            Tax tax = new Tax();
            tax.setName(t.name());
            tax.setRate(t.rate());
            tax.setAmount(t.amount());
            return tax;
        }).toList();
    }

    private static List<LineItem> toLineItems(ExtractedReceipt extracted) {
        return extracted.lineItems().stream().map(i -> {
            LineItem item = new LineItem();
            item.setDescription(i.description());
            item.setAmount(i.amount());
            return item;
        }).toList();
    }

    /** Outcome of an upsert: the transaction, and whether it was newly created. */
    public record UpsertResult(Transaction transaction, boolean created) {}
}
