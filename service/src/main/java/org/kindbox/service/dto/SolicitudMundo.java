package org.kindbox.service.dto;

import java.util.List;

/**
 * Cuerpo de {@code PUT /api/parametros/mundo}. Solo lleva lo que el motor ya lee de los
 * parametros del mundo: el tamano del mapa y la ubicacion y capacidad de los almacenes.
 *
 * @param mapa      dimensiones de la ciudad
 * @param almacenes un renglon por cada almacen existente; no se agregan ni se quitan
 */
public record SolicitudMundo(Mapa mapa, List<AlmacenMundo> almacenes) {

    public record Mapa(Integer largoKm, Integer anchoKm) {
    }

    /**
     * @param id        almacen que se modifica
     * @param x         coordenada X en km
     * @param y         coordenada Y en km
     * @param capacidad capacidad en paquetes; se ignora en el central, que es ilimitado
     */
    public record AlmacenMundo(Integer id, Integer x, Integer y, Integer capacidad) {
    }
}
