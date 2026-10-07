package com.navan.receipts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** An uploaded receipt file. Deduplicated by content hash. */
@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String originalFilename;

    /** SHA-256 of the file bytes; unique so identical uploads dedupe. */
    @Column(nullable = false, unique = true)
    private String contentHash;

    @Column(nullable = false)
    private String storagePath;

    /** Raw OCR text, populated by process; source for re-itemize. */
    private String ocrText;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    private boolean processed;
}
