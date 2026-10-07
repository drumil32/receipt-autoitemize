package com.navan.receipts.error;

import org.springframework.http.HttpStatus;

/** Thrown when required fields cannot be extracted from a receipt; mapped to HTTP 422. */
public class UnprocessableEntityException extends ApiException {

    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
