package org.kindbox.core.evaluacion;

/**
 * Restricciones duras del apartado 2.6 del ISA. El verificador de factibilidad las
 * comprueba tanto en la aceptacion de soluciones dentro de cada algoritmo como en la
 * verificacion de validez del apartado 12.4.
 */
public enum Infraccion {

    /** La entrega de un pedido ocurre despues de su fecha y hora limite. */
    PLAZO_INCUMPLIDO("La entrega de cada pedido ocurre en un instante anterior o igual a su fecha y hora limite"),
    /** La carga a bordo excede la capacidad de la unidad en algun tramo. */
    CAPACIDAD_EXCEDIDA("La carga a bordo de una unidad no excede su capacidad en ningun tramo de su ruta"),
    /** La ruta se extiende mas alla del cierre del turno. */
    TURNO_EXCEDIDO("Ninguna ruta se extiende mas alla del cierre del turno de la unidad"),
    /** Falta la hora de alimentacion o esta mal ubicada dentro de la jornada. */
    ALIMENTACION_INVALIDA("Cada unidad dispone de una hora continua de alimentacion dentro de la jornada, "
            + "separada al menos una hora de cada cambio de turno"),
    /** Se intento abastecer en un almacen sin inventario suficiente. */
    INVENTARIO_INSUFICIENTE("Una unidad no puede abastecerse en un almacen intermedio cuyo inventario "
            + "disponible sea insuficiente"),
    /** La ruta atraviesa un tramo bloqueado durante su ventana de vigencia. */
    TRAMO_BLOQUEADO("Los tramos bloqueados no son transitables durante su ventana de vigencia"),
    /** El tiempo de servicio de una hora por visita no se contabilizo. */
    SERVICIO_NO_CONTABILIZADO("El tiempo de servicio de una hora se contabiliza por visita"),
    /** Se entregaron mas unidades de las solicitadas por el pedido. */
    ENTREGA_EXCEDIDA("La suma de entregas de un pedido no supera la cantidad solicitada"),
    /** Se entregaron unidades que la unidad no tenia a bordo. */
    CARGA_INCONSISTENTE("Una unidad solo entrega producto que efectivamente lleva a bordo");

    private final String descripcion;

    Infraccion(String descripcion) {
        this.descripcion = descripcion;
    }

    /** Enunciado de la restriccion, tal como aparece en el apartado 2.6 del ISA. */
    public String descripcion() {
        return descripcion;
    }
}
