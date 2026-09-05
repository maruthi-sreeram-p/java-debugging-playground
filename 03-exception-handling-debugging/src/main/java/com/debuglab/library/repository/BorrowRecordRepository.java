package com.debuglab.library.repository;

import com.debuglab.library.entity.BorrowRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, Long> {

    List<BorrowRecord> findByMemberName(String memberName);

    long countByBookIdAndReturnedAtIsNull(Long bookId);
}
