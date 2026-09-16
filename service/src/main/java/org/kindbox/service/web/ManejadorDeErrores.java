package org.kindbox.service.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.kindbox.service.dto.RespuestaError;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.error.SolicitudInvalida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce las excepciones del servicio a codigos HTTP y mensajes en espanol.
 *
 * <p>El reparto es el que espera el visualizador: 404 cuando la corrida o la ruta no existen,
 * 409 cuando la operacion choca con el estado actual (arrancar con una corrida ya en curso,
 * cancelar una terminada, pedir el resumen final de una que sigue viva o averiar una unidad
 * que ya esta parada) y 400 cuando los parametros de la peticion no son admisibles.</p>
 *
 * <p>Los errores que levanta el propio contenedor antes de llegar al controlador tienen aqui
 * su manejador, y no en el generico: el metodo que la ruta no admite responde 405, el tipo de
 * contenido que no se sabe leer responde 415, una carga de archivo mal formada responde 400 y
 * una que supera el limite responde 413. Sin ellos el visualizador recibia un 500 con el
 * nombre de una clase Java de Spring en el cuerpo, que no es un contrato de API ni un mensaje
 * que el operador pueda entender.</p>
 *
 * <p>Todo lo que no encaje en esas categorias es un fallo del servidor: responde 500 con un
 * mensaje neutro y se registra con su traza, que es donde debe leerse el detalle tecnico.</p>
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(RecursoNoEncontrado.class)
    public ResponseEntity<RespuestaError> noEncontrado(RecursoNoEncontrado e, HttpServletRequest peticion) {
        return respuesta(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", "RECURSO",
                "No encontrado", e.getMessage(), peticion);
    }

    @ExceptionHandler(ConflictoDeEstado.class)
    public ResponseEntity<RespuestaError> conflicto(ConflictoDeEstado e, HttpServletRequest peticion) {
        return respuesta(HttpStatus.CONFLICT, "CONFLICTO_ESTADO", "ESTADO",
                "Conflicto de estado", e.getMessage(), peticion);
    }

    @ExceptionHandler({SolicitudInvalida.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<RespuestaError> solicitudInvalida(Exception e, HttpServletRequest peticion) {
        String mensaje = e instanceof HttpMessageNotReadableException
                ? "El cuerpo de la peticion no se pudo interpretar como JSON valido"
                : e.getMessage();
        return respuesta(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "VALIDACION",
                "Solicitud invalida", mensaje, peticion);
    }

    /**
     * Ruta inexistente. Llegan aqui las dos formas en que el despachador la senala: sin
     * manejador cuando no hay recursos estaticos publicados, y sin recurso cuando si los hay.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<RespuestaError> rutaDesconocida(Exception e, HttpServletRequest peticion) {
        LOG.debug("Ruta desconocida: {}", e.toString());
        return respuesta(HttpStatus.NOT_FOUND, "RUTA_NO_ENCONTRADA", "RECURSO",
                "No encontrado", "No existe el extremo " + peticion.getRequestURI(), peticion);
    }

    /** El verbo no esta mapeado en esa ruta, por ejemplo un PUT sobre una corrida. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RespuestaError> metodoNoPermitido(HttpRequestMethodNotSupportedException e,
                                                            HttpServletRequest peticion) {
        return respuesta(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO", "PROTOCOLO",
                "Metodo no permitido",
                "El metodo " + e.getMethod() + " no esta permitido en " + peticion.getRequestURI()
                        + ". Metodos admitidos: " + metodosAdmitidos(e), peticion);
    }

    /** El cuerpo llega con un tipo que el extremo no sabe leer, por ejemplo texto en vez de JSON. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<RespuestaError> tipoNoSoportado(HttpMediaTypeNotSupportedException e,
                                                          HttpServletRequest peticion) {
        MediaType recibido = e.getContentType();
        return respuesta(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "TIPO_CONTENIDO_NO_SOPORTADO", "PROTOCOLO",
                "Tipo de contenido no soportado",
                "El tipo de contenido " + (recibido == null ? "ausente" : recibido.toString())
                        + " no se admite en " + peticion.getRequestURI()
                        + ". Tipos admitidos: " + tiposAdmitidos(e), peticion);
    }

    /** La carga masiva de averias llego sin ser multipart o sin la parte que se espera. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<RespuestaError> cargaInvalida(MultipartException e, HttpServletRequest peticion) {
        LOG.debug("Carga de archivo mal formada en {}: {}", peticion.getRequestURI(), e.toString());
        return respuesta(HttpStatus.BAD_REQUEST, "CARGA_MULTIPARTE_INVALIDA", "VALIDACION",
                "Solicitud invalida",
                "La peticion no es una carga de archivo valida. Envie un formulario "
                        + "multipart/form-data con el archivo de averias en la parte 'archivo'", peticion);
    }

    /** El archivo de averias supera el limite configurado en {@code application.properties}. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RespuestaError> archivoDemasiadoGrande(MaxUploadSizeExceededException e,
                                                                 HttpServletRequest peticion) {
        long maximo = e.getMaxUploadSize();
        String limite = maximo > 0 ? " El maximo admitido es de " + maximo + " bytes." : "";
        return respuesta(HttpStatus.PAYLOAD_TOO_LARGE, "ARCHIVO_DEMASIADO_GRANDE", "VALIDACION",
                "Archivo demasiado grande",
                "El archivo supera el tamano maximo admitido por el servicio." + limite
                        + " Divida la carga de averias en varios archivos.", peticion);
    }

    @ExceptionHandler(ErrorDeDatos.class)
    public ResponseEntity<RespuestaError> errorDeDatos(ErrorDeDatos e, HttpServletRequest peticion) {
        LOG.error("Fallo al leer el escenario", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DATOS_SERVIDOR", "DATOS",
                "Error de datos", e.getMessage(), peticion);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespuestaError> errorInterno(Exception e, HttpServletRequest peticion) {
        LOG.error("Error interno en {}", peticion.getRequestURI(), e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "INTERNO",
                "Error interno",
                "El servicio no pudo completar la operacion. El detalle del fallo esta en el "
                        + "registro del servidor.", peticion);
    }

    private static String metodosAdmitidos(HttpRequestMethodNotSupportedException e) {
        Set<HttpMethod> admitidos = e.getSupportedHttpMethods();
        if (admitidos == null || admitidos.isEmpty()) {
            return "ninguno";
        }
        List<String> nombres = new ArrayList<>(admitidos.size());
        for (HttpMethod metodo : admitidos) {
            nombres.add(metodo.name());
        }
        return String.join(", ", nombres);
    }

    private static String tiposAdmitidos(HttpMediaTypeNotSupportedException e) {
        List<MediaType> admitidos = e.getSupportedMediaTypes();
        if (admitidos == null || admitidos.isEmpty()) {
            return "ninguno";
        }
        List<String> nombres = new ArrayList<>(admitidos.size());
        for (MediaType tipo : admitidos) {
            nombres.add(tipo.toString());
        }
        return String.join(", ", nombres);
    }

    private static ResponseEntity<RespuestaError> respuesta(HttpStatus estado, String codigoError,
                                                            String tipo, String error, String mensaje,
                                                            HttpServletRequest peticion) {
        String texto = mensaje == null || mensaje.isBlank() ? error : mensaje;
        return ResponseEntity.status(estado)
                .body(RespuestaError.de(estado.value(), codigoError, tipo, error, texto,
                        peticion.getRequestURI()));
    }
}
