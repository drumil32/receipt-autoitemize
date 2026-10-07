package com.navan.receipts.web;

import com.navan.receipts.service.TransactionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** Fetch a transaction by id. 404 if unknown. */
    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable String id) {
        return TransactionResponse.from(transactionService.getTransaction(id));
    }

    /** Fetch the one transaction for a receipt. 404 if none. Never a list. */
    @GetMapping(params = "receipt_id")
    public TransactionResponse getByReceiptId(@RequestParam("receipt_id") String receiptId) {
        return TransactionResponse.from(transactionService.getByReceiptId(receiptId));
    }
}
