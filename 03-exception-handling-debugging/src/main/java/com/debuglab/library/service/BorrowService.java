package com.debuglab.library.service;

import com.debuglab.library.dto.BorrowRequest;
import com.debuglab.library.dto.BorrowResponse;
import com.debuglab.library.entity.Book;
import com.debuglab.library.entity.BorrowRecord;
import com.debuglab.library.exception.AlreadyReturnedException;
import com.debuglab.library.exception.BookOutOfStockException;
import com.debuglab.library.exception.BorrowRecordNotFoundException;
import com.debuglab.library.repository.BookRepository;
import com.debuglab.library.repository.BorrowRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class BorrowService {

    private static final Logger log = LoggerFactory.getLogger(BorrowService.class);

    private final BookRepository bookRepository;
    private final BorrowRecordRepository borrowRecordRepository;

    public BorrowService(BookRepository bookRepository, BorrowRecordRepository borrowRecordRepository) {
        this.bookRepository = bookRepository;
        this.borrowRecordRepository = borrowRecordRepository;
    }

    @Transactional
    public BorrowResponse borrow(BorrowRequest request) {
        Book book = bookRepository.findById(request.getBookId()).orElseThrow();

        if (book.getAvailableCopies() <= 0) {
            throw new BookOutOfStockException(book.getTitle());
        }

        book.setAvailableCopies(book.getAvailableCopies() - 1);
        bookRepository.save(book);

        BorrowRecord record = new BorrowRecord();
        record.setBookId(book.getId());
        record.setMemberName(request.getMemberName());
        record.setBorrowedAt(LocalDateTime.now());
        BorrowRecord saved = borrowRecordRepository.save(record);

        log.info("Member {} borrowed '{}' (record {})",
                request.getMemberName(), book.getTitle(), saved.getId());

        return toResponse(saved, book);
    }

    @Transactional
    public BorrowResponse returnBook(Long borrowId) {
        try {
            BorrowRecord record = borrowRecordRepository.findById(borrowId)
                    .orElseThrow(() -> new BorrowRecordNotFoundException(borrowId));

            if (record.getReturnedAt() != null) {
                throw new AlreadyReturnedException(borrowId, record.getReturnedAt());
            }

            Book book = bookRepository.findById(record.getBookId())
                    .orElseThrow(() -> new BorrowRecordNotFoundException(borrowId));

            record.setReturnedAt(LocalDateTime.now());
            borrowRecordRepository.save(record);

            book.setAvailableCopies(book.getAvailableCopies() + 1);
            bookRepository.save(book);

            log.info("Borrow record {} returned", borrowId);
            return toResponse(record, book);
        } catch (Exception e) {
            log.warn("Could not complete return for borrow record {}", borrowId);
            return null;
        }
    }

    public List<BorrowResponse> historyFor(String memberName) {
        List<BorrowResponse> responses = new ArrayList<>();
        for (BorrowRecord record : borrowRecordRepository.findByMemberName(memberName)) {
            Book book = bookRepository.findById(record.getBookId()).orElse(null);
            responses.add(toResponse(record, book));
        }
        return responses;
    }

    private BorrowResponse toResponse(BorrowRecord record, Book book) {
        BorrowResponse response = new BorrowResponse();
        response.setBorrowId(record.getId());
        response.setBookId(record.getBookId());
        response.setMemberName(record.getMemberName());
        response.setBorrowedAt(record.getBorrowedAt());
        response.setReturnedAt(record.getReturnedAt());
        if (book != null) {
            response.setBookTitle(book.getTitle());
            response.setAvailableCopies(book.getAvailableCopies());
        }
        return response;
    }
}
