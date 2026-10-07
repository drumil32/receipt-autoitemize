package com.navan.receipts.error;

import org.springframework.http.HttpStatus;

/** Thrown when a request conflicts with the resource's current state; mapped to HTTP 409. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
