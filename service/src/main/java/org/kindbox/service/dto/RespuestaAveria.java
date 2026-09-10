package org.kindbox.service.dto;

import java.time.LocalDateTime;

/**
 * Confirmacion del registro individual de una averia.
 *
 * <p>Devuelve el instante simulado en que se aplica y el instante en que la unidad vuelve a
 * estar disponible, que es lo que el operador necesita saber: la respuesta 3 del cuestionario
 * fija reincorporaciones muy distintas segun el tipo, desde dos horas en el mismo lugar hasta
 * dos dias de mantenimiento con reingreso en el turno de las 15:00.</p>
 *
 * @param placa               codigo TTNN de la unidad averiada
 * @param tipo                tipo de averia registrado
 * @param minutoSimulado      instante simulado en que se aplica
 * @param fechaHoraSimulada   ese instante en el calendario real
 * @param minutoReincorporacion instante en que la unidad vuelve a ser planificable
 * @param mensaje             explicacion en espanol de lo que va a ocurrir
 */
public record RespuestaAveria(
        String placa,
        int tipo,
        long minutoSimulado,
        LocalDateTime fechaHoraSimulada,
        long minutoReincorporacion,
        String mensaje) {
}
