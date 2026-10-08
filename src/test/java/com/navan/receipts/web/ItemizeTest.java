package com.navan.receipts.web;

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
class ItemizeTest {

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
        var process = mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(process.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void itemize_clean_replacesItems_keepsHeaderAndTaxes_complete() throws Exception {
        String txnId = process("receipt-clean.txt");

        mockMvc.perform(post("/transactions/{id}/itemize", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txnId)) // same transaction, not a new one
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte")) // header untouched
                .andExpect(jsonPath("$.grand_total").value(17.85)) // total untouched
                .andExpect(jsonPath("$.taxes.length()").value(1)) // taxes untouched
                .andExpect(jsonPath("$.line_items.length()").value(3))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void itemize_mismatch_keepsTotal_needsReview() throws Exception {
        String txnId = process("receipt-mismatch.txt");

        mockMvc.perform(post("/transactions/{id}/itemize", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grand_total").value(18.5))
                .andExpect(jsonPath("$.line_items.length()").value(2))
                .andExpect(jsonPath("$.itemize_status").value("NEEDS_REVIEW"));
    }

    @Test
    void itemize_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(post("/transactions/{id}/itemize", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
