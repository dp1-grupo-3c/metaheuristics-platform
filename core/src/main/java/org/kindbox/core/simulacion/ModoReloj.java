package org.kindbox.core.simulacion;

/** Relacion entre el reloj simulado y el reloj de pared. */
public enum ModoReloj {
    /**
     * El motor se acompasa al reloj de pared segun el factor de aceleracion, de modo que la
     * corrida dure lo previsto y el visualizador reciba una progresion suave. Es el modo de
     * las presentaciones y el de los tres escenarios del enunciado.
     */
    ACOMPASADO,
    /**
     * El motor consume los datos tan rapido como puede, sin esperar al reloj de pared. Es el
     * modo de la experimentacion numerica, donde acompasar solo anadiria espera.
     */
    LIBRE
}
