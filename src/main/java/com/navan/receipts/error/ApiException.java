package com.navan.receipts.error;

import org.springframework.http.HttpStatus;

/** Base for deliberate API errors; carries the HTTP status to return. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
