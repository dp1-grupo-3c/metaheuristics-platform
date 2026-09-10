package org.kindbox.service.dto;

import java.util.List;
import org.kindbox.core.simulacion.InstantaneaSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;

/**
 * Respuesta de {@code GET /api/simulaciones/{id}}: cabecera, fotografia actual y, si la
 * corrida ya termino, su resumen final.
 *
 * <p>Es la via de reserva del visualizador cuando el WebSocket no esta disponible: contiene
 * lo mismo que viaja por el canal, de modo que un cliente puede sondear este extremo y
 * pintar la pantalla completa igual que si hubiese recibido la fotografia empujada.</p>
 *
 * @param corrida     cabecera de la corrida
 * @param instantanea fotografia completa del estado
 * @param resultado   resumen final, o {@code null} si la corrida sigue en curso
 * @param averias     registro de las averias ya aplicadas, de la mas reciente a la mas
 *                    antigua, que es lo que lista el panel de averias del prototipo
 */
public record DetalleCorrida(
        ResumenCorrida corrida,
        InstantaneaSimulacion instantanea,
        ResultadoSimulacion resultado,
        List<String> averias) {

    public DetalleCorrida {
        averias = List.copyOf(averias);
    }
}
