package org.kindbox.core.metaheuristica;

import org.kindbox.core.problema.Solucion;

/**
 * Salida de una ejecucion del planificador.
 *
 * @param algoritmo        nombre del algoritmo que la produjo
 * @param solucion         mejor solucion factible conocida al agotarse el presupuesto
 * @param perfil           perfil de convergencia, o {@code null} si no se registro
 * @param milisegundos     reloj de pared consumido
 * @param iteraciones      iteraciones o generaciones completadas
 * @param semilla          semilla del generador de numeros aleatorios de la corrida
 */
public record ResultadoPlanificacion(
        String algoritmo,
        Solucion solucion,
        PerfilConvergencia perfil,
        long milisegundos,
        long iteraciones,
        long semilla) {

    /** Iteraciones por segundo alcanzadas. */
    public double iteracionesPorSegundo() {
        return milisegundos <= 0 ? 0.0 : iteraciones * 1000.0 / milisegundos;
    }

    @Override
    public String toString() {
        return String.format("%s -> %s en %d ms (%d iter)",
                algoritmo, solucion.valor(), milisegundos, iteraciones);
    }
}
