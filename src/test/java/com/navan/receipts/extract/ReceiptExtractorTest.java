package com.navan.receipts.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.error.UnprocessableEntityException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReceiptExtractorTest {

    private final ReceiptExtractor extractor = new ReceiptExtractor();

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("fixtures/task-a/" + name));
    }

    @Test
    void cleanFixture_matchesGold_andIsComplete() throws Exception {
        ExtractedReceipt r = extractor.extract(fixture("receipt-clean.txt"));

        assertThat(r.merchant()).isEqualTo("Cafe Mitte");
        assertThat(r.date()).isEqualTo(LocalDate.parse("2026-03-12"));
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.grandTotal()).isEqualByComparingTo("17.85");

        assertThat(r.taxes()).hasSize(1);
        assertThat(r.taxes().get(0).name()).isEqualTo("VAT");
        assertThat(r.taxes().get(0).rate()).isEqualByComparingTo("0.19");
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("2.85");

        assertThat(r.lineItems()).hasSize(3);
        assertThat(r.lineItems().get(0).description()).isEqualTo("Espresso");
        assertThat(r.lineItems().get(0).amount()).isEqualByComparingTo("3.50");
        assertThat(r.lineItems().get(1).description()).isEqualTo("Sandwich");
        assertThat(r.lineItems().get(2).description()).isEqualTo("Mineral water");

        assertThat(r.itemizeStatus()).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void taxOnlyFixture_hasNoItems_andNeedsReview() throws Exception {
        ExtractedReceipt r = extractor.extract(fixture("receipt-tax-only.txt"));

        assertThat(r.merchant()).isEqualTo("Berlin Taxi GmbH");
        assertThat(r.grandTotal()).isEqualByComparingTo("24.00");
        assertThat(r.taxes()).hasSize(1);
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("3.83");
        assertThat(r.lineItems()).isEmpty();
        assertThat(r.itemizeStatus()).isEqualTo(ItemizeStatus.NEEDS_REVIEW);
    }

    @Test
    void mismatchFixture_keepsTotal_hasItems_andNeedsReview() throws Exception {
        ExtractedReceipt r = extractor.extract(fixture("receipt-mismatch.txt"));

        assertThat(r.merchant()).isEqualTo("Hotel Shop");
        assertThat(r.grandTotal()).isEqualByComparingTo("18.50"); // not changed to 11.90
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("1.90");
        assertThat(r.lineItems()).hasSize(2); // no balancing line added
        assertThat(r.lineItems().get(0).amount()).isEqualByComparingTo("4.00");
        assertThat(r.lineItems().get(1).amount()).isEqualByComparingTo("6.00");
        assertThat(r.itemizeStatus()).isEqualTo(ItemizeStatus.NEEDS_REVIEW);
    }

    @Test
    void missingRequiredFields_throwsUnprocessable() {
        String noMerchantNoTotal = "CURRENCY: EUR\nEspresso        3.50\n";

        assertThatThrownBy(() -> extractor.extract(noMerchantNoTotal))
                .isInstanceOf(UnprocessableEntityException.class);
    }
}
