package com.navan.receipts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.LineItem;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Tax;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.MismatchException;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.extract.ExtractedLineItem;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.extract.ExtractedTax;
import com.navan.receipts.extract.ReceiptExtractor;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import com.navan.receipts.service.TransactionService.UpsertResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    ReceiptRepository receipts;

    @Mock
    TransactionRepository transactions;

    @Mock
    ReceiptExtractor extractor;

    @InjectMocks
    TransactionService service;

    private Receipt receipt() {
        Receipt r = new Receipt();
        r.setId("r-1");
        return r;
    }

    private ExtractedReceipt extracted() {
        return new ExtractedReceipt(
                "Cafe Mitte", LocalDate.parse("2026-03-12"), "EUR", new BigDecimal("17.85"),
                List.of(new ExtractedTax("VAT", new BigDecimal("0.19"), new BigDecimal("2.85"))),
                List.of(new ExtractedLineItem("Espresso", new BigDecimal("3.50"))),
                ItemizeStatus.COMPLETE);
    }

    @Test
    void upsert_create_whenNoExistingTransaction() {
        Receipt receipt = receipt();
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt));
        when(transactions.findByReceipt_Id("r-1")).thenReturn(Optional.empty());
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));

        UpsertResult result = service.upsert("r-1", "ocr text", extracted());

        assertThat(result.created()).isTrue();
        Transaction t = result.transaction();
        assertThat(t.getReceipt()).isSameAs(receipt);
        assertThat(t.getMerchant()).isEqualTo("Cafe Mitte");
        assertThat(t.getGrandTotal()).isEqualByComparingTo("17.85");
        assertThat(t.getItemizeStatus()).isEqualTo(ItemizeStatus.COMPLETE);
        assertThat(t.getTaxes()).hasSize(1);
        assertThat(t.getLineItems()).hasSize(1);
        assertThat(receipt.getOcrText()).isEqualTo("ocr text");
        assertThat(receipt.isProcessed()).isTrue();
    }

    @Test
    void upsert_update_whenExistingTransaction_replacesChildren() {
        Receipt receipt = receipt();
        Transaction existing = new Transaction();
        existing.setId("t-1");
        existing.setReceipt(receipt);
        // stale children that must be replaced
        Tax oldTax = new Tax();
        oldTax.setName("OLD");
        oldTax.setRate(new BigDecimal("0.10"));
        oldTax.setAmount(new BigDecimal("1.00"));
        existing.addTax(oldTax);
        LineItem oldItem = new LineItem();
        oldItem.setDescription("Old");
        oldItem.setAmount(new BigDecimal("1.00"));
        existing.addLineItem(oldItem);

        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt));
        when(transactions.findByReceipt_Id("r-1")).thenReturn(Optional.of(existing));
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));

        UpsertResult result = service.upsert("r-1", "ocr text", extracted());

        assertThat(result.created()).isFalse();
        Transaction t = result.transaction();
        assertThat(t.getId()).isEqualTo("t-1"); // same row
        assertThat(t.getMerchant()).isEqualTo("Cafe Mitte"); // header updated
        assertThat(t.getTaxes()).hasSize(1);
        assertThat(t.getTaxes().get(0).getName()).isEqualTo("VAT"); // old replaced
        assertThat(t.getLineItems()).hasSize(1);
        assertThat(t.getLineItems().get(0).getDescription()).isEqualTo("Espresso");
    }

    @Test
    void reitemize_replacesLineItems_recomputesStatus_keepsHeaderAndTaxes() {
        Receipt receipt = receipt();
        receipt.setOcrText("stored ocr");
        Transaction existing = new Transaction();
        existing.setId("t-1");
        existing.setReceipt(receipt);
        existing.setMerchant("Cafe Mitte");
        existing.setGrandTotal(new BigDecimal("17.85"));
        existing.setItemizeStatus(ItemizeStatus.NEEDS_REVIEW);
        Tax vat = new Tax();
        vat.setName("VAT");
        vat.setRate(new BigDecimal("0.19"));
        vat.setAmount(new BigDecimal("2.85"));
        existing.addTax(vat);
        LineItem stale = new LineItem();
        stale.setDescription("Stale");
        stale.setAmount(new BigDecimal("99.00"));
        existing.addLineItem(stale);

        when(transactions.findById("t-1")).thenReturn(Optional.of(existing));
        when(extractor.extract("stored ocr")).thenReturn(extracted()); // 1 item 3.50, VAT, total 17.85
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));

        Transaction result = service.reitemize("t-1");

        assertThat(result.getLineItems()).hasSize(1);
        assertThat(result.getLineItems().get(0).getDescription()).isEqualTo("Espresso"); // stale replaced
        assertThat(result.getMerchant()).isEqualTo("Cafe Mitte"); // header untouched
        assertThat(result.getTaxes()).hasSize(1); // taxes untouched
        assertThat(result.getGrandTotal()).isEqualByComparingTo("17.85"); // total untouched
        // 3.50 + 2.85 != 17.85 -> NEEDS_REVIEW
        assertThat(result.getItemizeStatus()).isEqualTo(ItemizeStatus.NEEDS_REVIEW);
    }

    @Test
    void replaceItems_reconciles_replacesAndCompletes() {
        when(transactions.findById("t-1")).thenReturn(Optional.of(txnWithVat()));
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));

        // 15.00 + 2.85 == 17.85; also carries optional quantity + tax_amount
        Transaction result = service.replaceItems("t-1",
                List.of(new ExtractedLineItem(
                        "Combined", new BigDecimal("15.00"), new BigDecimal("2.85"), 3)));

        assertThat(result.getLineItems()).hasSize(1);
        assertThat(result.getLineItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(result.getLineItems().get(0).getTaxAmount()).isEqualByComparingTo("2.85");
        assertThat(result.getItemizeStatus()).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void replaceItems_doesNotReconcile_throwsMismatch_andDoesNotSave() {
        when(transactions.findById("t-1")).thenReturn(Optional.of(txnWithVat()));

        assertThatThrownBy(() -> service.replaceItems("t-1",
                List.of(new ExtractedLineItem("Too little", new BigDecimal("5.00")))))
                .isInstanceOf(MismatchException.class);

        verify(transactions, never()).save(any());
    }

    @Test
    void replaceItems_empty_throwsMismatch() {
        when(transactions.findById("t-1")).thenReturn(Optional.of(txnWithVat()));

        assertThatThrownBy(() -> service.replaceItems("t-1", List.of()))
                .isInstanceOf(MismatchException.class);

        verify(transactions, never()).save(any());
    }

    @Test
    void complete_reconciles_setsComplete() {
        Transaction txn = txnWithVat();
        LineItem item = new LineItem();
        item.setDescription("Combined");
        item.setAmount(new BigDecimal("15.00")); // 15.00 + 2.85 == 17.85
        txn.addLineItem(item);
        when(transactions.findById("t-1")).thenReturn(Optional.of(txn));
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));

        Transaction result = service.complete("t-1");

        assertThat(result.getItemizeStatus()).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void complete_doesNotReconcile_throwsMismatch_andDoesNotSave() {
        Transaction txn = txnWithVat();
        LineItem item = new LineItem();
        item.setDescription("Too little");
        item.setAmount(new BigDecimal("5.00")); // 5.00 + 2.85 != 17.85
        txn.addLineItem(item);
        when(transactions.findById("t-1")).thenReturn(Optional.of(txn));

        assertThatThrownBy(() -> service.complete("t-1")).isInstanceOf(MismatchException.class);

        verify(transactions, never()).save(any());
    }

    @Test
    void complete_emptyItems_throwsMismatch_andDoesNotSave() {
        when(transactions.findById("t-1")).thenReturn(Optional.of(txnWithVat())); // no line items

        assertThatThrownBy(() -> service.complete("t-1")).isInstanceOf(MismatchException.class);

        verify(transactions, never()).save(any());
    }

    @Test
    void complete_unknownId_throwsNotFound() {
        when(transactions.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    /** A transaction with VAT 2.85 and grand total 17.85 (so items must net to 15.00). */
    private Transaction txnWithVat() {
        Transaction t = new Transaction();
        t.setId("t-1");
        t.setReceipt(receipt());
        t.setGrandTotal(new BigDecimal("17.85"));
        t.setItemizeStatus(ItemizeStatus.NEEDS_REVIEW);
        Tax vat = new Tax();
        vat.setName("VAT");
        vat.setRate(new BigDecimal("0.19"));
        vat.setAmount(new BigDecimal("2.85"));
        t.addTax(vat);
        return t;
    }
}
