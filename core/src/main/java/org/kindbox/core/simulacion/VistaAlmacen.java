package org.kindbox.core.simulacion;

/**
 * Estado de un almacen tal como lo consume el visualizador. El prototipo cambia el icono
 * segun el nivel de ocupacion, de ahi el color de semaforo del requisito no funcional (d).
 *
 * @param id          identificador del almacen
 * @param nombre      nombre para presentacion
 * @param x           coordenada X
 * @param y           coordenada Y
 * @param central     indica si es el almacen central, de inventario ilimitado
 * @param disponible  unidades del producto P disponibles, o -1 si es ilimitado
 * @param capacidad   capacidad maxima, o -1 si es ilimitada
 * @param color       color del semaforo segun los umbrales vigentes
 */
public record VistaAlmacen(
        int id,
        String nombre,
        int x,
        int y,
        boolean central,
        int disponible,
        int capacidad,
        ColorSemaforo color) {
}
