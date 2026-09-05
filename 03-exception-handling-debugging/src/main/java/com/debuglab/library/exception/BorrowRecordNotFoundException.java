package com.debuglab.library.exception;

public class BorrowRecordNotFoundException extends RuntimeException {

    public BorrowRecordNotFoundException(Long id) {
        super("No borrow record exists with id " + id);
    }
}
