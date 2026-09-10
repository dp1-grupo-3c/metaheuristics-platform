package org.kindbox.service.dto;

import java.util.List;
import java.util.Map;

/**
 * Respuesta de {@code GET /api/parametros}: los parametros de operacion vigentes.
 *
 * <p>Cubre las dos zonas configurables de la pantalla de inicio del prototipo, la velocidad
 * por tipo de vehiculo y el semaforo de almacenes con su capacidad minima y maxima, y anade
 * la version de los parametros, que se incrementa con cada cambio. El visualizador puede
 * usar esa version para saber si el valor que muestra sigue siendo el vigente.</p>
 *
 * @param velocidades           velocidad vigente en Km/h por tipo de unidad
 * @param tipos                 ficha de cada tipo: capacidad, costo por Km y velocidad del enunciado
 * @param umbralSemaforoAmbar   umbral inferior del intervalo ambar
 * @param umbralSemaforoVerde   umbral inferior del intervalo verde
 * @param capacidadMinima       inventario minimo posible de un almacen intermedio
 * @param capacidadMaxima       inventario maximo de un almacen intermedio
 * @param minutosAcondicionamiento tiempo de entrega en el cliente, que no cuenta en el plazo
 * @param minutosAlimentacion   hora continua de alimentacion del conductor
 * @param minutosSeparacionCambioTurno separacion minima entre la pausa y un cambio de turno
 * @param minutosTrasvase       tiempo de trasvase de carga entre unidades
 * @param version               version de los parametros, que crece con cada cambio
 */
public record VistaParametros(
        Map<String, Double> velocidades,
        List<FichaTipoUnidad> tipos,
        int umbralSemaforoAmbar,
        int umbralSemaforoVerde,
        int capacidadMinima,
        int capacidadMaxima,
        int minutosAcondicionamiento,
        int minutosAlimentacion,
        int minutosSeparacionCambioTurno,
        int minutosTrasvase,
        long version) {

    /**
     * Ficha de un tipo de unidad para la pantalla de configuracion.
     *
     * @param tipo                nombre del tipo
     * @param prefijo             prefijo TT del codigo de unidad
     * @param etiqueta            nombre para presentacion
     * @param capacidad           capacidad en paquetes del producto P
     * @param velocidadVigente    velocidad en uso, modificable en caliente
     * @param velocidadDelEnunciado velocidad que fija el enunciado
     * @param costoPorKm          costo de operacion en soles por kilometro
     */
    public record FichaTipoUnidad(
            String tipo,
            String prefijo,
            String etiqueta,
            int capacidad,
            double velocidadVigente,
            double velocidadDelEnunciado,
            double costoPorKm) {
    }

    public VistaParametros {
        velocidades = Map.copyOf(velocidades);
        tipos = List.copyOf(tipos);
    }
}
