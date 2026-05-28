package com.arias.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Excepción genérica para fallos de autenticación.
 *
 * <p>IMPORTANTE: el mensaje es SIEMPRE el mismo independientemente de si el problema
 * es email inexistente, password incorrecta o usuario deshabilitado. Esto evita el
 * ataque de enumeración de cuentas.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email o contraseña incorrectos.");
    }
}
