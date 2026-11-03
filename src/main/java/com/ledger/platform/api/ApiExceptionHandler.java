package com.ledger.platform.api;

import com.ledger.platform.service.PostingException;
import com.ledger.platform.service.ProviderPaymentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProviderPaymentException.class)
    public ResponseEntity<ApiError> handleProviderPaymentException(ProviderPaymentException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }
    @ExceptionHandler(ReadApiException.class)
    public ResponseEntity<ApiError> handleReadApiException(ReadApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

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

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleMethodArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "INVALID_REQUEST_PARAMETER",
                        "A request parameter has an invalid format."));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "INVALID_REQUEST",
                        "Request body is missing or malformed."));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleMissingRoute(Exception exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "Endpoint unavailable."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An internal error occurred."));
    }
}

