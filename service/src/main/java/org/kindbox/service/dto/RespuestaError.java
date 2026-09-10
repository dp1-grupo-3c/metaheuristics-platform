package org.kindbox.service.dto;

import java.time.Instant;

/**
 * Cuerpo unico de toda respuesta de error del servicio.
 *
 * <p>El mensaje va en espanol y explica que hacer, no solo que fallo, porque quien lo lee
 * durante una presentacion es el operador del visualizador y no un programador.</p>
 *
 * @param codigo   codigo HTTP
 * @param error    nombre corto de la condicion
 * @param mensaje  explicacion en espanol
 * @param ruta     ruta de la peticion que fallo
 * @param instante momento en que se produjo
 */
public record RespuestaError(
        int codigo,
        String error,
        String mensaje,
        String ruta,
        Instant instante) {

    /** Construye la respuesta fechada en el instante actual. */
    public static RespuestaError de(int codigo, String error, String mensaje, String ruta) {
        return new RespuestaError(codigo, error, mensaje, ruta, Instant.now());
    }
}
