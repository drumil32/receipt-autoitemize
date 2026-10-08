package com.navan.receipts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class ReceiptUploadTest {

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

    private byte[] cleanReceipt() throws Exception {
        return Files.readAllBytes(Path.of("fixtures/task-a/receipt-clean.txt"));
    }

    @Test
    void upload_newReceipt_returns201WithId() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "receipt-clean.txt", "text/plain", cleanReceipt());

        mockMvc.perform(multipart("/receipts").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt_id").isNotEmpty());

        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void upload_identicalBytesTwice_returnsSameIdAndOneRow() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "receipt-clean.txt", "text/plain", cleanReceipt());

        mockMvc.perform(multipart("/receipts").file(file))
                .andExpect(status().isCreated());
        assertThat(receipts.count()).isEqualTo(1);
        String firstId = receipts.findAll().get(0).getId();

        mockMvc.perform(multipart("/receipts").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt_id").value(firstId));

        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void upload_emptyFile_returns400() throws Exception {
        MockMultipartFile empty =
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/receipts").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(receipts.count()).isZero();
    }

    @Test
    void upload_nonMultipartRequest_returns400() throws Exception {
        mockMvc.perform(post("/receipts").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(receipts.count()).isZero();
    }
}
