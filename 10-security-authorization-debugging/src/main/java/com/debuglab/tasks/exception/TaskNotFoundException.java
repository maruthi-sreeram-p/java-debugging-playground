package com.debuglab.tasks.exception;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long id) {
        super("No task exists with id " + id);
    }
}
