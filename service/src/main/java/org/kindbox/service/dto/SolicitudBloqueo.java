package org.kindbox.service.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/datos/bloqueos/manual}.
 *
 * @param nodos  esquinas de la poligonal, en orden; los tramos son horizontales o verticales
 * @param inicio instante de activacion, al minuto
 * @param fin    instante de desactivacion, al minuto y posterior al inicio
 */
public record SolicitudBloqueo(List<Nodo> nodos, LocalDateTime inicio, LocalDateTime fin) {

    /** Esquina de la reticula. */
    public record Nodo(Integer x, Integer y) {
    }
}
