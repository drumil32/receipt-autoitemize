package com.navan.receipts.error;

import java.io.UncheckedIOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /** Reconciliation mismatch (409) with the offending totals so the caller sees why. */
    @ExceptionHandler(MismatchException.class)
    public ResponseEntity<MismatchResponse> handleMismatch(MismatchException ex) {
        log.debug("409 mismatch: items={} taxes={} total={}",
                ex.getItemsTotal(), ex.getTaxesTotal(), ex.getGrandTotal());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MismatchResponse(409, "Conflict", ex.getMessage(),
                        ex.getItemsTotal(), ex.getTaxesTotal(), ex.getGrandTotal(), ex.getDifference()));
    }

    /** Bean-validation failures on request bodies -> 400 in our standard shape. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("Validation failed");
        log.debug("400 validation: {}", message);
        return response(HttpStatus.BAD_REQUEST, message);
    }

    /** Multipart upload missing the required `file` part -> 400 in our standard shape. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        log.debug("400: missing request part '{}'", ex.getRequestPartName());
        return response(HttpStatus.BAD_REQUEST, "Required file part '" + ex.getRequestPartName() + "' is missing");
    }

    /** A non-multipart (or malformed multipart) upload is a client error, not a 500. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
        log.debug("400: {}", ex.getMessage());
        return response(HttpStatus.BAD_REQUEST, "Expected a multipart file upload");
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
