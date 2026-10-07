package com.navan.receipts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import com.navan.receipts.service.ProcessingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReceiptProcessTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ReceiptRepository receipts;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    ProcessingService processingService;

    @BeforeEach
    void clean() {
        transactions.deleteAll();
        receipts.deleteAll();
    }

    private String upload(String filename, byte[] bytes) throws Exception {
        var result = mockMvc.perform(multipart("/receipts")
                        .file(new MockMultipartFile("file", filename, "text/plain", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.receipt_id");
    }

    private String uploadFixture(String name) throws Exception {
        return upload(name, Files.readAllBytes(Path.of("fixtures/task-a/" + name)));
    }

    // Required test #1
    @Test
    void cleanFixture_process_matchesGold() throws Exception {
        String receiptId = uploadFixture("receipt-clean.txt");

        mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt_id").value(receiptId))
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.grand_total").value(17.85))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.taxes[0].name").value("VAT"))
                .andExpect(jsonPath("$.taxes[0].rate").value(0.19))
                .andExpect(jsonPath("$.taxes[0].amount").value(2.85))
                .andExpect(jsonPath("$.line_items.length()").value(3))
                .andExpect(jsonPath("$.line_items[0].description").value("Espresso"));
    }

    // Required test #3
    @Test
    void taxOnlyFixture_process_needsReviewWithNoItems() throws Exception {
        String receiptId = uploadFixture("receipt-tax-only.txt");

        mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemize_status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.line_items.length()").value(0))
                .andExpect(jsonPath("$.taxes.length()").value(1));
    }

    // Required test #5
    @Test
    void reprocessTwice_sequentially_oneTransactionNoDuplicates() throws Exception {
        String receiptId = uploadFixture("receipt-clean.txt");

        var first = mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andReturn();
        String txnId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txnId))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.line_items.length()").value(3));

        assertThat(transactions.count()).isEqualTo(1);
    }

    // Required test #6
    @Test
    void concurrentProcess_sameReceipt_oneTransaction() throws Exception {
        String receiptId = uploadFixture("receipt-clean.txt");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> { start.await(); return processingService.process(receiptId); });
        Future<?> b = pool.submit(() -> { start.await(); return processingService.process(receiptId); });

        start.countDown(); // release both at once
        a.get();
        b.get();
        pool.shutdown();

        assertThat(transactions.count()).isEqualTo(1);
        assertThat(transactions.findByReceipt_Id(receiptId)).isPresent();
    }

    @Test
    void unknownReceipt_process_returns404() throws Exception {
        mockMvc.perform(post("/receipts/{id}/process", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unextractableReceipt_process_returns422_andPersistsNothing() throws Exception {
        String receiptId = upload("bad.txt", "CURRENCY: EUR\nWater 4.00\n".getBytes());

        mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isUnprocessableContent());

        assertThat(transactions.count()).isZero();
        assertThat(receipts.findById(receiptId).orElseThrow().isProcessed()).isFalse();
    }
}
