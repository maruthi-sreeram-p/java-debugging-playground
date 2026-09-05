package com.debuglab.library.exception;

import java.time.LocalDateTime;

public class AlreadyReturnedException extends RuntimeException {

    public AlreadyReturnedException(Long id, LocalDateTime returnedAt) {
        super("Borrow record " + id + " was already returned at " + returnedAt);
    }
}
