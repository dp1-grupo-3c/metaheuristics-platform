package org.kindbox.core.simulacion;

/**
 * Los tres escenarios que exige el enunciado para la evaluacion del curso.
 *
 * <p>El requisito (a) de los no funcionales pide que el planificador resuelva los tres
 * mediante parametros, de modo que la unica diferencia entre ellos es la configuracion del
 * reloj y la condicion de parada, nunca el algoritmo ni el modelo.</p>
 */
public enum TipoEscenario {

    /** Operacion en tiempo real. El reloj simulado avanza a la par del reloj de pared. */
    DIA_A_DIA,
    /**
     * Cinco dias simulados que deben ejecutarse en entre 30 y 60 minutos de reloj real.
     * De aqui se deriva el presupuesto de computo del apartado 2.3 del ISA.
     */
    SIMULACION_5D,
    /**
     * Corre sin horizonte hasta el colapso logistico, es decir hasta el primer instante en
     * que un pedido no puede entregarse dentro de su plazo. Se prefiere el algoritmo que
     * posterga mas ese instante.
     */
    COLAPSO
}
