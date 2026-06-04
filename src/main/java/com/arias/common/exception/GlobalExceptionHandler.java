package com.arias.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Mapeo central de excepciones a respuestas HTTP siguiendo RFC 7807 (problem+json).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setTitle("Email o contraseña incorrectos");
        pd.setType(URI.create("https://arias.com/errors/invalid-credentials"));
        return pd;
    }

    /**
     * Falla de validación de @Valid (campos del request). Sin este handler,
     * la excepción queda sin manejar → Spring forwardea a /error → Security
     * lo corta con 401 (no 400), lo que en el front dispara un refresh inútil.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            detail.isBlank() ? "Datos inválidos" : detail
        );
        pd.setTitle("Datos inválidos");
        pd.setType(URI.create("https://arias.com/errors/validation"));
        return pd;
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle(ex.getErrorCode());
        pd.setType(URI.create("https://arias.com/errors/" + ex.getErrorCode()));
        return pd;
    }
}
