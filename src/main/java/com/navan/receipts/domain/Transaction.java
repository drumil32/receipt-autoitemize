package com.navan.receipts.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The single transaction extracted from a receipt. One receipt -> one transaction. */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Unique FK: enforces one transaction per receipt at the DB level. */
    @OneToOne(optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, unique = true)
    private Receipt receipt;

    @Column(nullable = false)
    private String merchant;

    @Column(name = "txn_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemizeStatus itemizeStatus;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Tax> taxes = new ArrayList<>();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LineItem> lineItems = new ArrayList<>();

    public void addTax(Tax tax) {
        tax.setTransaction(this);
        taxes.add(tax);
    }

    public void addLineItem(LineItem item) {
        item.setTransaction(this);
        lineItems.add(item);
    }

    /** Clears line items so orphanRemoval deletes the old rows (used by re-itemize). */
    public void replaceLineItems(List<LineItem> items) {
        lineItems.clear();
        items.forEach(this::addLineItem);
    }

    /** Clears taxes so orphanRemoval deletes the old rows (used by re-process). */
    public void replaceTaxes(List<Tax> newTaxes) {
        taxes.clear();
        newTaxes.forEach(this::addTax);
    }
}
