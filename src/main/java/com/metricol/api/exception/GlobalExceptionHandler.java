package com.metricol.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.metricol.api.models.response.ApiResponse;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, ConstraintViolationException.class })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "Revisa los datos ingresados."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformed(HttpMessageNotReadableException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("MALFORMED_REQUEST", "La solicitud no es válida."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "Parámetro inválido: " + ex.getName()));
    }

    /**
     * Cuota agotada: el espacio del workspace o el tope diario de una red.
     *
     * <p>413 y no 400 a propósito. Lo que se pidió es válido —la app no tiene
     * nada que corregir en los datos—, simplemente ya no cabe, y el código de
     * error propio es lo que le permite ofrecer la salida correcta: borrar
     * contenido, o esperar a que el contador se reinicie.
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleQuota(QuotaExceededException ex) {
        log.warn("Cuota agotada ({}): {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("FILE_TOO_LARGE", "El archivo supera el tamaño máximo permitido."));
    }

    /**
     * Le falta un permiso dentro de su propio workspace.
     *
     * <p>Se responde con el mensaje de la excepción y no con uno genérico: ya
     * viene escrito para quien lo va a leer ("No tienes permiso para publicar
     * en este espacio") y es lo que le dice qué pedirle a su administrador.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        log.warn("Permiso denegado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "No tienes permisos para realizar esta acción."));
    }

    /**
     * Entró con Google alguien que no tiene cuenta aquí.
     *
     * <p>404 y con código propio a propósito: no es un fallo que arreglar sino
     * una bifurcación del camino, y la app lo usa para llevar al registro con
     * los datos que Google ya dio. Con un 400 genérico saldría un mensaje de
     * error y ahí terminaría todo.
     */
    @ExceptionHandler(GoogleSinCuentaException.class)
    public ResponseEntity<ApiResponse<Void>> handleGoogleSinCuenta(GoogleSinCuentaException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("GOOGLE_SIN_CUENTA", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("UNAUTHORIZED", "Correo o contraseña incorrectos."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("INTERNAL_ERROR", "Ocurrió un error inesperado. Intenta de nuevo."));
    }
}
