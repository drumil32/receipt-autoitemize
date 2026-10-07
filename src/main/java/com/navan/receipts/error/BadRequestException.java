package com.navan.receipts.error;

import org.springframework.http.HttpStatus;

/** Thrown for an invalid client request; mapped to HTTP 400. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
