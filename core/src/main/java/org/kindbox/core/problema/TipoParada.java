package org.kindbox.core.problema;

/** Naturaleza de una parada dentro de la ruta de una unidad de transporte. */
public enum TipoParada {
    /** Abastecimiento en un almacen, central o intermedio. El tiempo de carga es despreciable. */
    ABASTECIMIENTO,
    /** Entrega a un destinatario. Consume el tiempo de acondicionamiento del producto P. */
    ENTREGA,
    /** Hora continua de alimentacion del conductor dentro de la jornada. */
    ALIMENTACION
}
