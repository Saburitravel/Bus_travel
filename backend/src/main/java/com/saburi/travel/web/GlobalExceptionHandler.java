package com.saburi.travel.web;

import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.J;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

/** Every error is returned as {"message": "..."} which is what the frontend displays. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(J.map("message", message));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) { return body(HttpStatusCode.valueOf(e.status), e.getMessage()); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) { return body(HttpStatus.BAD_REQUEST, "Invalid request."); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "That conflicts with existing data (duplicate record, or the seat was just taken).");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof ErrorResponse er) {
            int code = er.getStatusCode().value();
            return body(er.getStatusCode(), code == 404 ? "Not found." : code == 405 ? "Method not allowed." : "Request failed.");
        }
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server. Please try again.");
    }
}
