package com.debuglab.library.exception;

public class BookOutOfStockException extends RuntimeException {

    public BookOutOfStockException(String title) {
        super("All copies of '" + title + "' are currently on loan");
    }
}
