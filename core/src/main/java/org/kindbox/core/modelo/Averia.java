package org.kindbox.core.modelo;

/**
 * Incidencia registrada sobre una unidad de transporte.
 *
 * <p>Las averias llegan por dos vias: el registro manual desde el visualizador
 * (respuesta 3, nota 1) y las reglas de generacion del motor de simulacion
 * (respuesta 3, nota 2).</p>
 *
 * @param codigoUnidad codigo TTNN de la unidad afectada
 * @param tipo         tipo de averia
 * @param minutoAveria instante del incidente, en minutos desde el inicio del escenario
 * @param nodo         nodo de la reticula donde quedo inmovilizada la unidad
 */
public record Averia(String codigoUnidad, TipoAveria tipo, long minutoAveria, int nodo) {

    /** Instante en que la unidad vuelve a estar disponible para la planificacion. */
    public long minutoReincorporacion() {
        return tipo.minutoReincorporacion(minutoAveria);
    }

    /**
     * Instante en que la unidad y su carga remanente se trasladan al almacen central.
     * Solo aplica a las averias de tipo 2 y 3.
     */
    public long minutoTrasladoAlmacenCentral() {
        return minutoAveria + tipo.minutosEnElLugar();
    }
}
