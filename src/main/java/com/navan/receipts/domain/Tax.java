package com.navan.receipts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A tax line on a transaction (e.g. VAT). */
@Entity
@Table(name = "taxes")
@Getter
@Setter
@NoArgsConstructor
public class Tax {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(nullable = false)
    private String name;

    /** Rate as a fraction (0.1900 = 19%). */
    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
