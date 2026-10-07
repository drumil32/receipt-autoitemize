package com.navan.receipts.web;

import static org.assertj.core.api.Assertions.assertThat;
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
class TransactionReadTest {

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

    /** Upload + process the clean fixture; returns [receiptId, transactionId]. */
    private String[] uploadAndProcess() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("fixtures/task-a/receipt-clean.txt"));
        var upload = mockMvc.perform(multipart("/receipts")
                        .file(new MockMultipartFile("file", "receipt-clean.txt", "text/plain", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        String receiptId = JsonPath.read(upload.getResponse().getContentAsString(), "$.receipt_id");

        var process = mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andReturn();
        String txnId = JsonPath.read(process.getResponse().getContentAsString(), "$.id");
        return new String[] {receiptId, txnId};
    }

    @Test
    void getTransactionById_returnsHeaderTaxesAndItems() throws Exception {
        String[] ids = uploadAndProcess();

        mockMvc.perform(get("/transactions/{id}", ids[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ids[1]))
                .andExpect(jsonPath("$.receipt_id").value(ids[0]))
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte"))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.line_items.length()").value(3));
    }

    @Test
    void getTransactionById_unknown_returns404() throws Exception {
        mockMvc.perform(get("/transactions/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByReceiptId_returnsTheOneTransaction() throws Exception {
        String[] ids = uploadAndProcess();

        mockMvc.perform(get("/transactions").param("receipt_id", ids[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ids[1]))
                .andExpect(jsonPath("$.receipt_id").value(ids[0]));
    }

    @Test
    void getByReceiptId_unknown_returns404() throws Exception {
        mockMvc.perform(get("/transactions").param("receipt_id", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // Required test #7: GET /receipts/{id} and GET /transactions?receipt_id= agree on the txn id
    @Test
    void receiptAndTransactionByReceiptId_agreeOnTransactionId() throws Exception {
        String[] ids = uploadAndProcess();
        String receiptId = ids[0];

        var receiptView = mockMvc.perform(get("/receipts/{id}", receiptId)).andReturn();
        String fromReceipt = JsonPath.read(receiptView.getResponse().getContentAsString(), "$.transaction_id");

        var byReceiptId = mockMvc.perform(get("/transactions").param("receipt_id", receiptId)).andReturn();
        String fromQuery = JsonPath.read(byReceiptId.getResponse().getContentAsString(), "$.id");

        assertThat(fromReceipt).isEqualTo(ids[1]).isEqualTo(fromQuery);
    }
}
