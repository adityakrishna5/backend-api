package com.store.common.exception;

import com.store.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<String> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return ApiResponse.<String>builder()
            .status("VALIDATION_ERROR").data(message).build();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<String> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .collect(Collectors.joining("; "));
        return ApiResponse.<String>builder()
            .status("VALIDATION_ERROR").data(message).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<String> handleBadRequest(IllegalArgumentException ex) {
        return ApiResponse.<String>builder()
            .status("BAD_REQUEST").data(ex.getMessage()).build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<String> handleNotFound(NoSuchElementException ex) {
        return ApiResponse.<String>builder()
            .status("NOT_FOUND").data(ex.getMessage()).build();
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<String> handleConflict(IllegalStateException ex) {
        return ApiResponse.<String>builder()
            .status("CONFLICT").data(ex.getMessage()).build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<String> handleForbidden(AccessDeniedException ex) {
        return ApiResponse.<String>builder()
            .status("FORBIDDEN").data(ex.getMessage()).build();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<String> handleGeneral(Exception ex) {
        return ApiResponse.<String>builder()
            .status("ERROR").data(ex.getMessage()).build();
    }
}
