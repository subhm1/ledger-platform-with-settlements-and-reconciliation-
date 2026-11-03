package com.ledger.platform.api;

import com.ledger.platform.service.SettlementImportException;
import com.ledger.platform.service.SettlementImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/settlement-batches")
public class SettlementImportController {
    private final SettlementImportService importService;

    public SettlementImportController(SettlementImportService importService) {
        this.importService = importService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<SettlementImportResponse> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceCode") String sourceCode,
            @RequestParam("reportId") String reportId,
            @RequestParam("reportingPeriodStart") OffsetDateTime reportingPeriodStart,
            @RequestParam("reportingPeriodEnd") OffsetDateTime reportingPeriodEnd) {
        SettlementImportResponse response = importService.importReport(
                file, sourceCode, reportId, reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.status("IMPORTED".equals(response.status())
                ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @ExceptionHandler(SettlementImportException.class)
    public ResponseEntity<ApiError> handleImportException(SettlementImportException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleImportParameterTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "INVALID_REQUEST_PARAMETER",
                        "A request parameter has an invalid format."));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedImportException(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "An internal error occurred."));
    }
}
