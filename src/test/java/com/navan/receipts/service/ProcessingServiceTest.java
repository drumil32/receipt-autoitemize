package com.navan.receipts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.error.UnprocessableEntityException;
import com.navan.receipts.extract.ExtractedReceipt;
import com.navan.receipts.extract.ReceiptExtractor;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.service.TransactionService.UpsertResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceTest {

    @Mock
    ReceiptRepository receipts;

    @Mock
    OcrService ocr;

    @Mock
    ReceiptExtractor extractor;

    @Mock
    TransactionService transactionService;

    @InjectMocks
    ProcessingService service;

    private Receipt receipt() {
        Receipt r = new Receipt();
        r.setId("r-1");
        r.setStoragePath("data/files/hash");
        return r;
    }

    private ExtractedReceipt extracted() {
        return new ExtractedReceipt(
                "Cafe Mitte", LocalDate.parse("2026-03-12"), "EUR", new BigDecimal("17.85"),
                List.of(), List.of(), ItemizeStatus.NEEDS_REVIEW);
    }

    private UpsertResult upsertResult(boolean created) {
        Transaction t = new Transaction();
        t.setId("t-1");
        t.setItemizeStatus(ItemizeStatus.NEEDS_REVIEW);
        return new UpsertResult(t, created);
    }

    @Test
    void process_happyPath_returnsUpsertResult() {
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt()));
        when(ocr.readText(any())).thenReturn("ocr text");
        when(extractor.extract("ocr text")).thenReturn(extracted());
        when(transactionService.upsert(eq("r-1"), eq("ocr text"), any())).thenReturn(upsertResult(true));

        UpsertResult result = service.process("r-1");

        assertThat(result.created()).isTrue();
        verify(transactionService, times(1)).upsert(eq("r-1"), eq("ocr text"), any());
    }

    @Test
    void process_unknownReceipt_throwsNotFound_andSkipsOcr() {
        when(receipts.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process("missing"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(ocr, extractor, transactionService);
    }

    @Test
    void process_unextractable_rethrows422_andSkipsUpsert() {
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt()));
        when(ocr.readText(any())).thenReturn("bad text");
        when(extractor.extract("bad text")).thenThrow(new UnprocessableEntityException("no fields"));

        assertThatThrownBy(() -> service.process("r-1"))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(transactionService, never()).upsert(any(), any(), any());
    }

    @Test
    void process_concurrentRace_retriesUpsertOnce() {
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt()));
        when(ocr.readText(any())).thenReturn("ocr text");
        when(extractor.extract("ocr text")).thenReturn(extracted());
        when(transactionService.upsert(eq("r-1"), eq("ocr text"), any()))
                .thenThrow(new DataAccessResourceFailureException("unique(receipt_id) race"))
                .thenReturn(upsertResult(false)); // retry finds winner and updates

        UpsertResult result = service.process("r-1");

        assertThat(result.created()).isFalse();
        verify(transactionService, times(2)).upsert(eq("r-1"), eq("ocr text"), any());
    }
}
