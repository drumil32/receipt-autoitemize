package com.navan.receipts.service;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.LineItem;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Tax;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.MismatchException;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.extract.ExtractedLineItem;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.extract.ReceiptExtractor;
import com.navan.receipts.extract.Reconciliation;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class TransactionService {

    private final ReceiptRepository receipts;
    private final TransactionRepository transactions;
    private final ReceiptExtractor extractor;

    public TransactionService(
            ReceiptRepository receipts, TransactionRepository transactions, ReceiptExtractor extractor) {
        this.receipts = receipts;
        this.transactions = transactions;
        this.extractor = extractor;
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
        txn.replaceLineItems(toLineItems(extracted.lineItems()));

        return new UpsertResult(transactions.save(txn), created);
    }

    /**
     * Re-runs auto-itemize from the receipt's stored OCR, replacing line items only. Header,
     * taxes and grand total are untouched; status is recomputed against the stored taxes/total.
     */
    @Transactional
    public Transaction reitemize(String transactionId) {
        Transaction txn = transactions.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        ExtractedReceipt extracted = extractor.extract(txn.getReceipt().getOcrText());
        txn.replaceLineItems(toLineItems(extracted.lineItems()));
        txn.setItemizeStatus(statusFor(txn.getLineItems(), txn.getTaxes(), txn.getGrandTotal()));

        Transaction saved = transactions.save(txn);
        log.info("Re-itemized transaction {} -> {}", saved.getId(), saved.getItemizeStatus());
        // TODO(metric): increment itemize.result{status=<itemizeStatus>} counter
        return saved;
    }

    /**
     * User override: replace the line items with the supplied set. Persists only if items + stored
     * taxes reconcile with the grand total; otherwise throws {@link MismatchException} (409) and
     * changes nothing. Header, taxes and grand total are never touched.
     */
    @Transactional
    public Transaction replaceItems(String transactionId, List<ExtractedLineItem> newItems) {
        Transaction txn = transactions.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        List<ExtractedLineItem> items = newItems == null ? List.of() : newItems;
        BigDecimal itemsTotal = sum(items.stream().map(ExtractedLineItem::amount).toList());
        BigDecimal taxesTotal = sum(txn.getTaxes().stream().map(Tax::getAmount).toList());
        BigDecimal grandTotal = txn.getGrandTotal();

        // Empty items can never be COMPLETE; otherwise require exact reconciliation.
        // (A per-item tax_amount is stored metadata; reconciliation uses the tax rows.)
        if (items.isEmpty() || !Reconciliation.reconciles(itemsTotal, taxesTotal, grandTotal)) {
            log.warn("PATCH items rejected for transaction {}: does not reconcile", transactionId);
            // TODO(metric): increment items.patch.rejected counter
            throw new MismatchException(itemsTotal, taxesTotal, grandTotal);
        }

        txn.replaceLineItems(toLineItems(items));
        txn.setItemizeStatus(ItemizeStatus.COMPLETE);
        Transaction saved = transactions.save(txn);
        log.info("Patched items on transaction {} -> COMPLETE", saved.getId());
        // TODO(metric): increment items.patch.applied counter
        return saved;
    }

    /** COMPLETE iff items are present and items + taxes reconcile with the grand total. */
    private static ItemizeStatus statusFor(List<LineItem> items, List<Tax> taxes, BigDecimal grandTotal) {
        if (items.isEmpty()) {
            return ItemizeStatus.NEEDS_REVIEW;
        }
        BigDecimal itemsTotal = sum(items.stream().map(LineItem::getAmount).toList());
        BigDecimal taxesTotal = sum(taxes.stream().map(Tax::getAmount).toList());
        return Reconciliation.reconciles(itemsTotal, taxesTotal, grandTotal)
                ? ItemizeStatus.COMPLETE
                : ItemizeStatus.NEEDS_REVIEW;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private static List<LineItem> toLineItems(List<ExtractedLineItem> items) {
        return items.stream().map(i -> {
            LineItem item = new LineItem();
            item.setDescription(i.description());
            item.setAmount(i.amount());
            item.setTaxAmount(i.taxAmount());
            item.setQuantity(i.quantity());
            return item;
        }).toList();
    }

    /** Outcome of an upsert: the transaction, and whether it was newly created. */
    public record UpsertResult(Transaction transaction, boolean created) {}
}
