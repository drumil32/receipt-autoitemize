package com.navan.receipts.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.LineItem;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Tax;
import com.navan.receipts.domain.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class TransactionPersistenceTest {

    @Autowired
    ReceiptRepository receipts;

    @Autowired
    TransactionRepository transactions;

    @BeforeEach
    void clean() {
        transactions.deleteAll();
        receipts.deleteAll();
    }

    @Test
    void secondTransactionForSameReceipt_violatesUniqueConstraint() {
        Receipt receipt = receipts.save(receipt("hash-1"));
        transactions.saveAndFlush(txn(receipt));

        // SQLite surfaces the unique violation as JpaSystemException, Postgres as
        // DataIntegrityViolationException; both extend DataAccessException.
        assertThatThrownBy(() -> transactions.saveAndFlush(txn(receipt)))
                .isInstanceOf(DataAccessException.class);

        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    @Transactional
    void saveTransaction_cascadesChildren_andIsFoundByReceiptId() {
        Receipt receipt = receipts.save(receipt("hash-2"));

        Transaction t = txn(receipt);
        Tax vat = new Tax();
        vat.setName("VAT");
        vat.setRate(new BigDecimal("0.1900"));
        vat.setAmount(new BigDecimal("2.85"));
        t.addTax(vat);
        LineItem espresso = new LineItem();
        espresso.setDescription("Espresso");
        espresso.setAmount(new BigDecimal("3.50"));
        t.addLineItem(espresso);
        transactions.save(t);

        Transaction found = transactions.findByReceipt_Id(receipt.getId()).orElseThrow();
        assertThat(found.getReceipt().getId()).isEqualTo(receipt.getId());
        assertThat(found.getTaxes()).hasSize(1);
        assertThat(found.getLineItems()).hasSize(1);
    }

    private Receipt receipt(String hash) {
        Receipt r = new Receipt();
        r.setOriginalFilename("r.txt");
        r.setContentHash(hash);
        r.setStoragePath("data/files/" + hash);
        return r;
    }

    private Transaction txn(Receipt receipt) {
        Transaction t = new Transaction();
        t.setReceipt(receipt);
        t.setMerchant("Cafe Mitte");
        t.setDate(LocalDate.parse("2026-03-12"));
        t.setCurrency("EUR");
        t.setGrandTotal(new BigDecimal("17.85"));
        t.setItemizeStatus(ItemizeStatus.COMPLETE);
        return t;
    }
}
