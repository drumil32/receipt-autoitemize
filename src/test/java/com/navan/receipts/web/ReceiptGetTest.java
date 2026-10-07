package com.navan.receipts.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.navan.receipts.repository.ReceiptRepository;
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
class ReceiptGetTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ReceiptRepository receipts;

    @BeforeEach
    void clean() {
        receipts.deleteAll();
    }

    private String uploadCleanReceipt() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("fixtures/task-a/receipt-clean.txt"));
        MockMultipartFile file =
                new MockMultipartFile("file", "receipt-clean.txt", "text/plain", bytes);
        mockMvc.perform(multipart("/receipts").file(file)).andExpect(status().isCreated());
        return receipts.findAll().get(0).getId();
    }

    @Test
    void getExistingReceipt_returnsStoredFields() throws Exception {
        String id = uploadCleanReceipt();

        mockMvc.perform(get("/receipts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.filename").value("receipt-clean.txt"))
                .andExpect(jsonPath("$.uploaded_at").isNotEmpty())
                .andExpect(jsonPath("$.processed").value(false))
                .andExpect(jsonPath("$.transaction_id").value(nullValue()));
    }

    @Test
    void getUnknownReceipt_returns404() throws Exception {
        mockMvc.perform(get("/receipts/{id}", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
