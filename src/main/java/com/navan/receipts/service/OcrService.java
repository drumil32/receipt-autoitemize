package com.navan.receipts.service;

import com.navan.receipts.domain.Receipt;
import org.springframework.stereotype.Service;

/** Stubbed OCR: the stored fixture file is already text, so we return it verbatim. */
@Service
public class OcrService {

    private final FileStorageService storage;

    public OcrService(FileStorageService storage) {
        this.storage = storage;
    }

    public String readText(Receipt receipt) {
        return storage.read(receipt.getStoragePath());
    }
}
