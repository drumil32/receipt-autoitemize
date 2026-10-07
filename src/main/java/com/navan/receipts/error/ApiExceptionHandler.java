package com.navan.receipts.error;

import java.io.UncheckedIOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into consistent error responses. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    /** Deliberate API errors carry their own status (404, 400, 409, ...). */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        HttpStatus status = ex.getStatus();
        if (status.is5xxServerError()) {
            log.error("{}: {}", status.value(), ex.getMessage(), ex);
        } else {
            log.debug("{}: {}", status.value(), ex.getMessage());
        }
        return response(status, ex.getMessage());
    }

    /** File I/O failures surface as a generic 500 without leaking internals. */
    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleIo(UncheckedIOException ex) {
        log.error("500: file I/O failure", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process upload file");
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
