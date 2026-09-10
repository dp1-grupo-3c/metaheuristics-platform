package org.kindbox.core.modelo;

/** Situacion operativa de una unidad de transporte dentro de la simulacion. */
public enum EstadoUnidad {
    /** En un almacen, sin ruta asignada. */
    DISPONIBLE,
    /** Recorriendo un tramo de su ruta. */
    EN_RUTA,
    /** Detenida en un cliente durante la hora de acondicionamiento del producto P. */
    ENTREGANDO,
    /** Detenida durante la hora de alimentacion del conductor. */
    EN_ALIMENTACION,
    /** Cargando en un almacen. El tiempo de carga es despreciable. */
    ABASTECIENDO,
    /** Inmovilizada por una averia. */
    AVERIADA,
    /** No disponible por mantenimiento preventivo programado. */
    EN_MANTENIMIENTO
}
