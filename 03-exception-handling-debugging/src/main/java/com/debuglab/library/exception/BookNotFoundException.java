package com.debuglab.library.exception;

public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(Long id) {
        super("No book exists with id " + id);
    }
}
