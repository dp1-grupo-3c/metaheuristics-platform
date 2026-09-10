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
 * @param minutoPrimerIncumplimiento instante en que vencio el plazo del primer pedido que
 *                            no pudo entregarse, o -1 si no hubo ninguno. Se registra en los
 *                            tres escenarios, tambien en los que culminan: un incumplimiento
 *                            aislado no es un colapso
 * @param pedidoDelPrimerIncumplimiento identificador de ese pedido, o -1
 * @param mensaje             explicacion legible del desenlace
 */
public record ResultadoSimulacion(
        ConfiguracionEscenario configuracion,
        EstadoCorrida estado,
        long minutoFinal,
        LocalDateTime fechaHoraFinal,
        long milisegundosReales,
        MetricasSimulacion metricas,
        long minutoPrimerIncumplimiento,
        int pedidoDelPrimerIncumplimiento,
        String mensaje) {

    /** Indica si la corrida termino por colapso logistico. */
    public boolean colapso() {
        return estado == EstadoCorrida.COLAPSADA;
    }

    /**
     * Instante del colapso logistico, o {@code -1} si la corrida no colapso.
     *
     * <p>Es la metrica del apartado 12.1 del ISA, que prefiere el algoritmo que la posterga
     * mas. Se distingue a proposito de {@link #minutoPrimerIncumplimiento()}: una corrida
     * puede incumplir un plazo aislado y aun asi culminar su horizonte, y presentar ese
     * instante como colapso haria que el modal de fin de simulacion anunciase un colapso en
     * una corrida que termino bien.</p>
     */
    public long minutoColapso() {
        return estado == EstadoCorrida.COLAPSADA ? minutoPrimerIncumplimiento : -1L;
    }

    /** Pedido que provoco el colapso, o {@code -1} si la corrida no colapso. */
    public int pedidoDelColapso() {
        return estado == EstadoCorrida.COLAPSADA ? pedidoDelPrimerIncumplimiento : -1;
    }

    /** Indica si algun pedido vencio sin completarse, haya colapsado la corrida o no. */
    public boolean huboIncumplimientos() {
        return minutoPrimerIncumplimiento >= 0;
    }

    /** Dias simulados completos que alcanzo la corrida. */
    public double diasSimulados() {
        return minutoFinal / 1440.0;
    }
}
