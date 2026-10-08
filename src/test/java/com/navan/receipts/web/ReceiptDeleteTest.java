package com.navan.receipts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ReceiptDeleteTest {

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

    private String uploadClean() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("fixtures/task-a/receipt-clean.txt"));
        var result = mockMvc.perform(multipart("/receipts")
                        .file(new MockMultipartFile("file", "receipt-clean.txt", "text/plain", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.receipt_id");
    }

    @Test
    void deleteUnprocessed_removesRowAndFile_returns204() throws Exception {
        String id = uploadClean();
        String storagePath = receipts.findById(id).orElseThrow().getStoragePath();
        assertThat(Files.exists(Path.of(storagePath))).isTrue();

        mockMvc.perform(delete("/receipts/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(receipts.existsById(id)).isFalse();
        assertThat(Files.exists(Path.of(storagePath))).isFalse();
    }

    // Required test #8
    @Test
    void deleteProcessed_returns409_andKeepsEverything() throws Exception {
        String id = uploadClean();
        mockMvc.perform(post("/receipts/{id}/process", id)).andExpect(status().isCreated());

        mockMvc.perform(delete("/receipts/{id}", id))
                .andExpect(status().isConflict());

        assertThat(receipts.existsById(id)).isTrue();
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void deleteUnknown_returns404() throws Exception {
        mockMvc.perform(delete("/receipts/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
