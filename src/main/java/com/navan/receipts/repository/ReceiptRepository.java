package com.navan.receipts.repository;

import com.navan.receipts.domain.Receipt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {

    Optional<Receipt> findByContentHash(String contentHash);
}
