package com.navan.receipts.web;

import com.navan.receipts.service.TransactionService;
import com.navan.receipts.service.TransactionService.ItemInput;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Re-run auto-itemize from stored OCR, replacing line items only. 404 if unknown. */
    @PostMapping("/{id}/itemize")
    public TransactionResponse itemize(@PathVariable String id) {
        return TransactionResponse.from(transactionService.reitemize(id));
    }

    /** User override of line items. 409 + mismatch payload (persists nothing) if it doesn't reconcile. */
    @PatchMapping("/{id}/items")
    public TransactionResponse patchItems(
            @PathVariable String id, @Valid @RequestBody ItemsPatchRequest body) {
        List<ItemInput> items = (body.lineItems() == null ? List.<ItemsPatchRequest.LineItemPatch>of() : body.lineItems())
                .stream()
                .map(i -> new ItemInput(i.description(), i.amount(), i.taxAmount(), i.quantity()))
                .toList();
        return TransactionResponse.from(transactionService.replaceItems(id, items));
    }
}
