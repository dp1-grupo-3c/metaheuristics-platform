package org.kindbox.core.simulacion;

/**
 * Situacion de una corrida de simulacion. Los tres desenlaces finales son los que el
 * prototipo del visualizador distingue en el modal de fin de simulacion.
 */
public enum EstadoCorrida {
    /** Configurada pero aun sin arrancar. */
    PREPARADA,
    /** En ejecucion. */
    EN_CURSO,
    /** Completo el horizonte configurado sin incidencias que comprometieran la operacion. */
    CULMINADA,
    /** Detenida a mano por el usuario antes de completar el horizonte. */
    CANCELADA,
    /**
     * Terminada por colapso logistico: un pedido no pudo entregarse dentro de su plazo. En
     * el escenario de colapso es el desenlace esperado y su instante es la metrica.
     */
    COLAPSADA,
    /**
     * El escenario abierto se quedo sin datos antes de colapsar: falta el archivo de ventas
     * de un mes y los pedidos que quedaban se entregaron sin que ninguno venciera. No hay
     * instante de colapso que medir, solo la cota de que la flota aguanto hasta ahi.
     */
    DATOS_AGOTADOS,
    /** Terminada por un error interno. */
    FALLIDA
}
