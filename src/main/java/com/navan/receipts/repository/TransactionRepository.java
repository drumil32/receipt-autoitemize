package com.navan.receipts.repository;

import com.navan.receipts.domain.Transaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    Optional<Transaction> findByReceipt_Id(String receiptId);
}
