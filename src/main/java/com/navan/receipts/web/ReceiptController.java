package com.navan.receipts.web;

import com.navan.receipts.service.ReceiptService;
import com.navan.receipts.service.ReceiptService.UploadResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/receipts")
@Slf4j
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    /** Upload a receipt. New bytes -> 201; identical bytes seen before -> 200 with the existing id. */
    @PostMapping
    public ResponseEntity<ReceiptUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            log.warn("Rejected empty upload: {}", file.getOriginalFilename());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        UploadResult result = receiptService.upload(file.getOriginalFilename(), readBytes(file));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ReceiptUploadResponse(result.receipt().getId()));
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload", e);
        }
    }
}
