package com.navan.receipts.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.navan.receipts.domain.Receipt;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReceiptResponseTest {

    @Test
    void from_mapsFields_andLeavesTransactionIdNull() {
        Instant uploadedAt = Instant.parse("2026-03-12T10:15:30Z");
        Receipt receipt = new Receipt();
        receipt.setId("r-1");
        receipt.setOriginalFilename("receipt-clean.txt");
        receipt.setUploadedAt(uploadedAt);
        receipt.setProcessed(false);

        ReceiptResponse response = ReceiptResponse.from(receipt, "txn-9");

        assertThat(response.id()).isEqualTo("r-1");
        assertThat(response.filename()).isEqualTo("receipt-clean.txt");
        assertThat(response.uploadedAt()).isEqualTo(uploadedAt);
        assertThat(response.processed()).isFalse();
        assertThat(response.transactionId()).isEqualTo("txn-9");
    }

    @Test
    void from_nullTransactionId_whenUnprocessed() {
        Receipt receipt = new Receipt();
        receipt.setId("r-2");
        receipt.setOriginalFilename("x.txt");
        receipt.setProcessed(false);

        assertThat(ReceiptResponse.from(receipt, null).transactionId()).isNull();
    }
}
