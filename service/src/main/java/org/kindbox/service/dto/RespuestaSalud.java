package org.kindbox.service.dto;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta de {@code GET /api/salud}: comprobacion de vida del servicio.
 *
 * <p>Ademas de responder que el proceso esta vivo informa de lo que el equipo necesita
 * verificar antes de una presentacion: que el directorio de datos existe, que motor de
 * simulacion esta enchufado, que algoritmos hay disponibles y cuantos dispositivos estan
 * conectados al canal de retransmision.</p>
 *
 * @param estado             siempre "vivo" cuando el servicio responde
 * @param instante           momento de la comprobacion
 * @param directorioDatos    directorio raiz de los datos, tal como se resolvio
 * @param datosDisponibles   si ese directorio existe y es legible
 * @param motor              motor de simulacion en uso
 * @param algoritmos         algoritmos del planificador disponibles
 * @param corridaActiva      identificador de la corrida en curso, o null si no hay ninguna
 * @param corridasConocidas  corridas que el servicio conserva en memoria
 * @param clientesConectados dispositivos conectados al canal de retransmision
 */
public record RespuestaSalud(
        String estado,
        Instant instante,
        String directorioDatos,
        boolean datosDisponibles,
        String motor,
        List<String> algoritmos,
        String corridaActiva,
        int corridasConocidas,
        int clientesConectados) {

    public RespuestaSalud {
        algoritmos = List.copyOf(algoritmos);
    }
}
