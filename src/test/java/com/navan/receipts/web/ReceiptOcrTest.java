package com.navan.receipts.web;

import static org.hamcrest.Matchers.containsString;
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
class ReceiptOcrTest {

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
    void getOcr_beforeProcess_returns409() throws Exception {
        String id = uploadClean();
        mockMvc.perform(get("/receipts/{id}/ocr", id))
                .andExpect(status().isConflict());
    }

    @Test
    void getOcr_afterProcess_returnsStoredText() throws Exception {
        String id = uploadClean();
        mockMvc.perform(post("/receipts/{id}/process", id)).andExpect(status().isCreated());

        mockMvc.perform(get("/receipts/{id}/ocr", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ocr_text", containsString("Cafe Mitte")))
                .andExpect(jsonPath("$.ocr_text", containsString("TOTAL")));
    }

    @Test
    void getOcr_unknownReceipt_returns404() throws Exception {
        mockMvc.perform(get("/receipts/{id}/ocr", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
