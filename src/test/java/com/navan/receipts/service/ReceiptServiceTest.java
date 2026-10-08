package com.navan.receipts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navan.receipts.domain.Receipt;
import com.navan.receipts.domain.Transaction;
import com.navan.receipts.error.ConflictException;
import com.navan.receipts.error.ResourceNotFoundException;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.service.ReceiptService.UploadResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    @Mock
    ReceiptRepository receipts;

    @Mock
    com.navan.receipts.repository.TransactionRepository transactions;

    @Mock
    FileStorageService storage;

    @InjectMocks
    ReceiptService service;

    private final byte[] bytes = "receipt-bytes".getBytes();

    @Test
    void duplicateBytes_returnExistingWithoutStoring() {
        Receipt existing = receiptWithId("r-1");
        when(receipts.findByContentHash(any())).thenReturn(Optional.of(existing));

        UploadResult result = service.upload("r.txt", bytes);

        assertThat(result.created()).isFalse();
        assertThat(result.receipt().getId()).isEqualTo("r-1");
        verify(storage, never()).store(any(), any());
    }

    @Test
    void newBytes_storeThenSave_returnCreated() {
        when(receipts.findByContentHash(any())).thenReturn(Optional.empty());
        when(storage.store(any(), any())).thenReturn("data/files/hash");
        when(receipts.save(any())).thenReturn(receiptWithId("r-2"));

        UploadResult result = service.upload("r.txt", bytes);

        assertThat(result.created()).isTrue();
        assertThat(result.receipt().getId()).isEqualTo("r-2");
    }

    @Test
    void concurrentRace_returnWinnerAndKeepSharedFile() {
        Receipt winner = receiptWithId("r-3");
        when(receipts.findByContentHash(any()))
                .thenReturn(Optional.empty())   // initial dedup check
                .thenReturn(Optional.of(winner)); // re-read after the constraint violation
        when(storage.store(any(), any())).thenReturn("data/files/hash");
        when(receipts.save(any())).thenThrow(new DataIntegrityViolationException("unique"));

        UploadResult result = service.upload("r.txt", bytes);

        assertThat(result.created()).isFalse();
        assertThat(result.receipt().getId()).isEqualTo("r-3");
        verify(storage, never()).delete(any()); // shared file must NOT be deleted
    }

    @Test
    void dbFailure_compensateByDeletingOrphanFile() {
        when(receipts.findByContentHash(any())).thenReturn(Optional.empty());
        when(storage.store(any(), any())).thenReturn("data/files/hash");
        when(receipts.save(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.upload("r.txt", bytes))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("db down");

        verify(storage).delete("data/files/hash"); // orphan file cleaned up
    }

    @Test
    void getReceipt_found_returnsReceipt() {
        Receipt existing = receiptWithId("r-9");
        when(receipts.findById("r-9")).thenReturn(Optional.of(existing));

        assertThat(service.getReceipt("r-9")).isSameAs(existing);
    }

    @Test
    void getReceipt_missing_throwsNotFound() {
        when(receipts.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReceipt("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void delete_whenTransactionExists_throwsConflict_andDeletesNothing() {
        Receipt receipt = receiptWithId("r-1");
        receipt.setStoragePath("data/files/hash");
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt));
        when(transactions.findByReceipt_Id("r-1")).thenReturn(Optional.of(new Transaction()));

        assertThatThrownBy(() -> service.delete("r-1"))
                .isInstanceOf(ConflictException.class);

        verify(receipts, never()).delete(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void delete_whenNoTransaction_deletesRowAndFile() {
        Receipt receipt = receiptWithId("r-1");
        receipt.setStoragePath("data/files/hash");
        when(receipts.findById("r-1")).thenReturn(Optional.of(receipt));
        when(transactions.findByReceipt_Id("r-1")).thenReturn(Optional.empty());

        service.delete("r-1");

        verify(receipts).delete(receipt);
        verify(storage).delete("data/files/hash");
    }

    private Receipt receiptWithId(String id) {
        Receipt r = new Receipt();
        r.setId(id);
        return r;
    }
}
