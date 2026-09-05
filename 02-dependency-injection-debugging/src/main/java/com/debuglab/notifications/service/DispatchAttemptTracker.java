package com.debuglab.notifications.service;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Counts how many delivery attempts a single dispatch needed.
 */
@Component
@Scope("prototype")
public class DispatchAttemptTracker {

    private int attempts;

    public int recordAttempt() {
        attempts = attempts + 1;
        return attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
