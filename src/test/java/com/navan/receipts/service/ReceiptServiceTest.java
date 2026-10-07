package com.navan.receipts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navan.receipts.domain.Receipt;
import com.navan.receipts.repository.ReceiptRepository;
import com.navan.receipts.service.ReceiptService.UploadResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    @Mock
    ReceiptRepository receipts;

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
        when(receipts.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.upload("r.txt", bytes))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storage).delete("data/files/hash"); // orphan file cleaned up
    }

    private Receipt receiptWithId(String id) {
        Receipt r = new Receipt();
        r.setId(id);
        return r;
    }
}
