package com.rally;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var error=e.getBindingResult().getFieldErrors().getFirst();
        return api(ApiException.bad(error.getField()+" "+error.getDefaultMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() { return api(new ApiException(409,"CONFLICT","That username or email is already in use, or this item changed. Please try again.")); }
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed() { return api(ApiException.bad("Check the request fields and try again.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return api(ApiException.bad("Images must be 5 MB or smaller.")); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> notFound() { return api(ApiException.missing()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        LoggerFactory.getLogger(Errors.class).error("Request failed",e);
        return ResponseEntity.internalServerError().body(Map.of("code","SERVER_ERROR","message","Something went wrong. Please try again."));
    }
}
