package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.util.Aleatorio;

/**
 * Remocion aleatoria del apartado 7.3.2 del ISA: retira {@code q} tareas de entrega elegidas
 * de forma uniforme entre las ya colocadas.
 *
 * <p>Es el operador de diversificacion pura del conjunto. No mira el objetivo ni la
 * geometria, de modo que es el unico capaz de deshacer una estructura que los operadores
 * guiados reconstruirian una y otra vez. Voigt (2025) lo mantiene en todos los conjuntos
 * recomendados justamente por eso, aunque su rendimiento aislado sea el mas pobre.</p>
 */
public final class RemocionAleatoria implements OperadorDestruccion {

    /** Tareas colocadas, recogidas al arrancar cada invocacion. */
    private final int[] colocados;

    /**
     * @param cantidadTareas numero de tareas de entrega de la fotografia
     */
    public RemocionAleatoria(int cantidadTareas) {
        this.colocados = new int[Math.max(1, cantidadTareas)];
    }

    @Override
    public String nombre() {
        return "remocion-aleatoria";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        int n = 0;
        for (int u = 0; u < estado.cantidadUnidades(); u++) {
            int longitud = estado.longitudRuta(u);
            for (int i = 0; i < longitud; i++) {
                colocados[n++] = estado.tareaEn(u, i);
            }
        }
        if (n == 0) {
            return 0;
        }
        int objetivo = Math.min(grado, n);
        // Barajado parcial de Fisher y Yates: solo se sortean los q primeros puestos.
        for (int i = 0; i < objetivo; i++) {
            int j = i + aleatorio.siguienteEntero(n - i);
            int t = colocados[i];
            colocados[i] = colocados[j];
            colocados[j] = t;
        }
        for (int i = 0; i < objetivo; i++) {
            estado.quitar(colocados[i]);
        }
        return objetivo;
    }
}
