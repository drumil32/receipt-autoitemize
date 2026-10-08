package com.navan.receipts.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.repository.TransactionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CompleteTransactionTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ReceiptRepository receipts;

    @Autowired
    TransactionRepository transactions;

    @BeforeEach
    void clean() {
        transactions.deleteAll();
        receipts.deleteAll();
    }

    /** Upload + process a fixture; returns its transaction id. */
    private String process(String fixture) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("fixtures/task-a/" + fixture));
        var upload = mockMvc.perform(multipart("/receipts")
                        .file(new MockMultipartFile("file", fixture, "text/plain", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        String receiptId = JsonPath.read(upload.getResponse().getContentAsString(), "$.receipt_id");
        var p = mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(p.getResponse().getContentAsString(), "$.id");
    }

    // Required test #9: completing a non-reconciling transaction is a 409, status unchanged.
    @Test
    void complete_mismatch_returns409_withPayload_andStaysNeedsReview() throws Exception {
        String txnId = process("receipt-mismatch.txt"); // 10.00 items + 1.90 VAT != 18.50

        mockMvc.perform(post("/transactions/{id}/complete", txnId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.items_total").value(10.00))
                .andExpect(jsonPath("$.taxes_total").value(1.90))
                .andExpect(jsonPath("$.grand_total").value(18.50))
                .andExpect(jsonPath("$.difference").value(-6.60));

        // nothing changed: still NEEDS_REVIEW
        mockMvc.perform(get("/transactions/{id}", txnId))
                .andExpect(jsonPath("$.itemize_status").value("NEEDS_REVIEW"));
    }

    @Test
    void complete_reconciling_returns200_andCompletes() throws Exception {
        String txnId = process("receipt-clean.txt"); // 3 items net 15.00 + 2.85 VAT == 17.85

        mockMvc.perform(post("/transactions/{id}/complete", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txnId))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));

        // idempotent: completing again still 200 COMPLETE
        mockMvc.perform(post("/transactions/{id}/complete", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void complete_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(post("/transactions/{id}/complete", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
