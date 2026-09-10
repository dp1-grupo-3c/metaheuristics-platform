package org.kindbox.core.simulacion;

/**
 * Sucesos que hacen avanzar la simulacion. El motor es dirigido por eventos y no por pasos
 * de reloj: consume los datos del escenario en lugar de muestrear el tiempo, de modo que un
 * periodo sin actividad no cuesta nada.
 *
 * <p>El orden de declaracion fija el desempate cuando dos eventos caen en el mismo minuto
 * simulado. Primero se abre el dia y se actualiza el entorno, luego se mueven las unidades,
 * despues se replanifica con el estado ya actualizado y por ultimo se cierra el escenario.</p>
 */
public enum TipoEvento {

    /** Recarga diaria de los almacenes intermedios a capacidad plena, a las 23:59:59. */
    RECARGA_ALMACENES,
    /** Un tramo de calle pasa a estar bloqueado. */
    ACTIVACION_BLOQUEO,
    /** Un tramo de calle deja de estar bloqueado. */
    DESACTIVACION_BLOQUEO,
    /** Arranca un turno: 07:00, 15:00 o 23:00. */
    CAMBIO_TURNO,
    /** Una unidad entra en mantenimiento preventivo, a las 00:00 del dia programado. */
    INICIO_MANTENIMIENTO,
    /** Una unidad sale de mantenimiento preventivo, a las 23:59 del dia programado. */
    FIN_MANTENIMIENTO,
    /** Llega un pedido nuevo desde el archivo mensual de ventas. */
    LLEGADA_PEDIDO,
    /** Una unidad se averia. */
    AVERIA,
    /** Una unidad averiada y su carga remanente se trasladan al almacen central. */
    TRASLADO_A_CENTRAL,
    /** Una unidad averiada vuelve a estar disponible para la planificacion. */
    FIN_AVERIA,
    /** Una unidad alcanza la siguiente parada de su ruta. */
    LLEGADA_A_PARADA,
    /** Una unidad termina el servicio de una parada y puede continuar. */
    FIN_SERVICIO,
    /** Se ejecuta el planificador sobre la totalidad de los pedidos pendientes. */
    REPLANIFICACION,
    /** Se toma una fotografia del estado para el visualizador. */
    FOTOGRAFIA,
    /** Fin del horizonte del escenario. */
    FIN_ESCENARIO
}
