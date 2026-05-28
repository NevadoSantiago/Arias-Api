package com.arias.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Excepción base para errores de regla de negocio (no de auth).
 * El {@link com.arias.common.exception.GlobalExceptionHandler} la mapea a problem+json.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public BusinessException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static BusinessException conflict(String code, String msg) {
        return new BusinessException(HttpStatus.CONFLICT, code, msg);
    }

    public static BusinessException notFound(String code, String msg) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, msg);
    }

    public static BusinessException badRequest(String code, String msg) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, msg);
    }

    public static BusinessException forbidden(String code, String msg) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, msg);
    }
}
