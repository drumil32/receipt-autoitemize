package com.navan.receipts.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PatchItemsTest {

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

    private String processClean() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("fixtures/task-a/receipt-clean.txt"));
        var upload = mockMvc.perform(multipart("/receipts")
                        .file(new MockMultipartFile("file", "receipt-clean.txt", "text/plain", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        String receiptId = JsonPath.read(upload.getResponse().getContentAsString(), "$.receipt_id");
        var process = mockMvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(process.getResponse().getContentAsString(), "$.id");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchItems(
            String txnId, String json) {
        return patch("/transactions/{id}/items", txnId).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void patch_reconcilingEdit_replacesItems_andCompletes() throws Exception {
        String txnId = processClean();

        // merge the three net items (15.00) into one; 15.00 + 2.85 VAT == 17.85
        String body = "{\"line_items\":[{\"description\":\"Combined\",\"amount\":15.00}]}";
        mockMvc.perform(patchItems(txnId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line_items.length()").value(1))
                .andExpect(jsonPath("$.grand_total").value(17.85))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"))
                // optional fields omitted -> null
                .andExpect(jsonPath("$.line_items[0].tax_amount").value(nullValue()))
                .andExpect(jsonPath("$.line_items[0].quantity").value(nullValue()));
    }

    @Test
    void patch_withOptionalFields_storesTaxAmountAndQuantity() throws Exception {
        String txnId = processClean();

        String body = "{\"line_items\":[{\"description\":\"Combined\",\"amount\":15.00,"
                + "\"tax_amount\":2.85,\"quantity\":3}]}";
        mockMvc.perform(patchItems(txnId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line_items[0].tax_amount").value(2.85))
                .andExpect(jsonPath("$.line_items[0].quantity").value(3));
    }

    // Required test #4
    @Test
    void patch_nonReconciling_returns409_withPayload_andPersistsNothing() throws Exception {
        String txnId = processClean();

        String body = "{\"line_items\":[{\"description\":\"Too little\",\"amount\":5.00}]}";
        mockMvc.perform(patchItems(txnId, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.items_total").value(5.00))
                .andExpect(jsonPath("$.taxes_total").value(2.85))
                .andExpect(jsonPath("$.grand_total").value(17.85))
                .andExpect(jsonPath("$.difference").value(-10.00));

        // persisted items unchanged (still the 3 originals), total unchanged, status unchanged
        mockMvc.perform(get("/transactions/{id}", txnId))
                .andExpect(jsonPath("$.line_items.length()").value(3))
                .andExpect(jsonPath("$.grand_total").value(17.85))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void patch_emptyItems_returns409() throws Exception {
        String txnId = processClean();
        mockMvc.perform(patchItems(txnId, "{\"line_items\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patch_invalidAmount_returns400() throws Exception {
        String txnId = processClean();
        String body = "{\"line_items\":[{\"description\":\"Bad\",\"amount\":-1.00}]}";
        mockMvc.perform(patchItems(txnId, body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_unknownTransaction_returns404() throws Exception {
        String body = "{\"line_items\":[{\"description\":\"X\",\"amount\":1.00}]}";
        mockMvc.perform(patchItems("does-not-exist", body))
                .andExpect(status().isNotFound());
    }
}
