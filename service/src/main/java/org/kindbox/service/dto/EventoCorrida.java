package org.kindbox.service.dto;

import java.time.LocalDateTime;

/**
 * Renglon de la bitacora de una corrida, que el resumen final del visualizador lista en
 * "Eventos de la simulacion" y copia en el reporte de texto.
 *
 * @param fecha   instante simulado del evento
 * @param tono    gravedad, con los colores del semaforo
 * @param mensaje texto listo para mostrar
 */
public record EventoCorrida(LocalDateTime fecha, Tono tono, String mensaje) {

    /** Gravedad del evento: verde informa, ambar advierte y rojo es una falla. */
    public enum Tono { VERDE, AMBAR, ROJO }
}
