package com.navan.receipts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.LineItem;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Tax;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.extract.ExtractedLineItem;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.extract.ExtractedTax;
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
}
