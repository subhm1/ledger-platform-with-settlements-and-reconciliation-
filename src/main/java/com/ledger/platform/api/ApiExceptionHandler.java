package com.ledger.platform.api;

import com.ledger.platform.service.PostingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PostingException.class)
    public ResponseEntity<ApiError> handlePostingException(PostingException exception) {
        HttpStatus status = exception.getStatus();

        String message = status.is5xxServerError()
                ? "An internal error occurred."
                : exception.getMessage();

        String code = status.is5xxServerError()
                ? "INTERNAL_ERROR"
                : exception.getCode();

        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "INVALID_REQUEST",
                        "Request body is missing or malformed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An internal error occurred."));
    }
}