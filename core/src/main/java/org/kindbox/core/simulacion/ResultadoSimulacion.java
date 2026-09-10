package org.kindbox.core.simulacion;

import java.time.LocalDateTime;

/**
 * Resumen de una corrida terminada. Alimenta el modal de fin de simulacion del prototipo,
 * cuyo encabezado varia segun el desenlace, y la comparacion entre algoritmos del apartado
 * 12 del ISA, donde el instante de colapso es una de las cuatro metricas.
 *
 * @param configuracion       parametros con los que se ejecuto
 * @param estado              desenlace de la corrida
 * @param minutoFinal         ultimo instante simulado alcanzado
 * @param fechaHoraFinal      mismo instante en el calendario real
 * @param milisegundosReales  duracion de la corrida en reloj de pared
 * @param metricas            indicadores acumulados
 * @param minutoColapso       instante del colapso, o -1 si no se produjo
 * @param pedidoDelColapso    identificador del pedido que no pudo entregarse, o -1
 * @param mensaje             explicacion legible del desenlace
 */
public record ResultadoSimulacion(
        ConfiguracionEscenario configuracion,
        EstadoCorrida estado,
        long minutoFinal,
        LocalDateTime fechaHoraFinal,
        long milisegundosReales,
        MetricasSimulacion metricas,
        long minutoColapso,
        int pedidoDelColapso,
        String mensaje) {

    /** Indica si la corrida termino por colapso logistico. */
    public boolean colapso() {
        return estado == EstadoCorrida.COLAPSADA;
    }

    /** Dias simulados completos que alcanzo la corrida. */
    public double diasSimulados() {
        return minutoFinal / 1440.0;
    }
}
