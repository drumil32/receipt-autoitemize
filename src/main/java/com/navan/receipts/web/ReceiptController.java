package com.navan.receipts.web;

import com.navan.receipts.error.BadRequestException;
import com.navan.receipts.service.ProcessingService;
import com.navan.receipts.service.ReceiptService;
import com.navan.receipts.service.ReceiptService.UploadResult;
import com.navan.receipts.service.TransactionService.UpsertResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/receipts")
@Slf4j
public class ReceiptController {

    private final ReceiptService receiptService;
    private final ProcessingService processingService;

    public ReceiptController(ReceiptService receiptService, ProcessingService processingService) {
        this.receiptService = receiptService;
        this.processingService = processingService;
    }

    /** Upload a receipt. New bytes -> 201; identical bytes seen before -> 200 with the existing id. */
    @PostMapping
    public ResponseEntity<ReceiptUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            log.warn("Rejected empty upload: {}", file.getOriginalFilename());
            throw new BadRequestException("Uploaded file is empty");
        }
        UploadResult result = receiptService.upload(file.getOriginalFilename(), readBytes(file));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ReceiptUploadResponse(result.receipt().getId()));
    }

    /** Fetch a stored receipt by id. 404 if unknown. */
    @GetMapping("/{id}")
    public ReceiptResponse get(@PathVariable String id) {
        return ReceiptResponse.from(receiptService.getReceipt(id));
    }

    /** Run OCR + extraction and create-or-update the one transaction. 201 create / 200 update. */
    @PostMapping("/{id}/process")
    public ResponseEntity<TransactionResponse> process(@PathVariable String id) {
        UpsertResult result = processingService.process(id);
        TransactionResponse body = TransactionResponse.from(result.transaction());
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(HttpHeaders.LOCATION, "/transactions/" + result.transaction().getId())
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload", e);
        }
    }
}
