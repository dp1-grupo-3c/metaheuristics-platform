package org.kindbox.service.web;

import jakarta.servlet.http.HttpServletRequest;
import org.kindbox.service.dto.RespuestaError;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.error.SolicitudInvalida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Traduce las excepciones del servicio a codigos HTTP y mensajes en espanol.
 *
 * <p>El reparto es el que espera el visualizador: 404 cuando la corrida no existe, 409
 * cuando la operacion choca con el estado actual (arrancar con una corrida ya en curso,
 * cancelar una terminada o pedir el resumen final de una que sigue viva) y 400 cuando los
 * parametros de la peticion no son admisibles. Todo lo que no encaje en esas tres
 * categorias es un fallo del servidor y se registra con su traza.</p>
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(RecursoNoEncontrado.class)
    public ResponseEntity<RespuestaError> noEncontrado(RecursoNoEncontrado e, HttpServletRequest peticion) {
        return respuesta(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage(), peticion);
    }

    @ExceptionHandler(ConflictoDeEstado.class)
    public ResponseEntity<RespuestaError> conflicto(ConflictoDeEstado e, HttpServletRequest peticion) {
        return respuesta(HttpStatus.CONFLICT, "Conflicto de estado", e.getMessage(), peticion);
    }

    @ExceptionHandler({SolicitudInvalida.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<RespuestaError> solicitudInvalida(Exception e, HttpServletRequest peticion) {
        String mensaje = e instanceof HttpMessageNotReadableException
                ? "El cuerpo de la peticion no se pudo interpretar como JSON valido"
                : e.getMessage();
        return respuesta(HttpStatus.BAD_REQUEST, "Solicitud invalida", mensaje, peticion);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<RespuestaError> rutaDesconocida(NoHandlerFoundException e, HttpServletRequest peticion) {
        return respuesta(HttpStatus.NOT_FOUND, "No encontrado",
                "No existe el extremo " + e.getRequestURL(), peticion);
    }

    @ExceptionHandler(ErrorDeDatos.class)
    public ResponseEntity<RespuestaError> errorDeDatos(ErrorDeDatos e, HttpServletRequest peticion) {
        LOG.error("Fallo al leer el escenario", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Error de datos", e.getMessage(), peticion);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespuestaError> errorInterno(Exception e, HttpServletRequest peticion) {
        LOG.error("Error interno en {}", peticion.getRequestURI(), e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "El servicio no pudo completar la operacion: " + e, peticion);
    }

    private static ResponseEntity<RespuestaError> respuesta(HttpStatus estado, String error,
                                                            String mensaje, HttpServletRequest peticion) {
        String texto = mensaje == null || mensaje.isBlank() ? error : mensaje;
        return ResponseEntity.status(estado)
                .body(RespuestaError.de(estado.value(), error, texto, peticion.getRequestURI()));
    }
}
