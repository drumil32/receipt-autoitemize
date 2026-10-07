package com.navan.receipts.error;

import org.springframework.http.HttpStatus;

/** Thrown when a requested resource does not exist; mapped to HTTP 404. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
