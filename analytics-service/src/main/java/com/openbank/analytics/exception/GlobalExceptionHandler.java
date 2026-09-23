package com.openbank.analytics.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized client-error handling (Phase 23, Slice 4). The internal
 * analytics endpoint returns simple HTTP 400 responses for any invalid input;
 * neither the request nor any details are echoed back, keeping responses free
 * of sensitive material.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Void> handleMethodArgumentNotValid() {
        return badRequest();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> handleHttpMessageNotReadable() {
        return badRequest();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleIllegalArgument() {
        return badRequest();
    }

    private static ResponseEntity<Void> badRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
}