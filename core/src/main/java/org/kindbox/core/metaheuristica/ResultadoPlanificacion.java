package org.kindbox.core.metaheuristica;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.kindbox.core.problema.Solucion;

/**
 * Salida de una ejecucion del planificador.
 *
 * <p>Los pesos de los operadores son los de la capa adaptativa del apartado 7.3.3 del ISA al
 * cerrarse la ejecucion. El apartado afirma que se exponen para los reportes del apartado 12,
 * porque su evolucion informa sobre que operadores trabajan en cada regimen de presupuesto, y
 * este es el punto por el que salen de la busqueda: la capa vive dentro de cada ejecucion y
 * sin este campo se perdia al terminarla. El mapa es inmutable y su orden de iteracion es
 * estable, el mismo en todas las ejecuciones de un mismo algoritmo, de modo que dos
 * resultados se comparan entrada a entrada y una tabla de reporte sale siempre con las mismas
 * columnas.</p>
 *
 * @param algoritmo        nombre del algoritmo que la produjo
 * @param solucion         mejor solucion factible conocida al agotarse el presupuesto
 * @param perfil           perfil de convergencia, o {@code null} si no se registro
 * @param milisegundos     reloj de pared consumido
 * @param iteraciones      iteraciones o generaciones completadas
 * @param semilla          semilla del generador de numeros aleatorios de la corrida
 * @param pesosOperadores  peso final de cada operador de la capa adaptativa, por nombre de
 *                         operador: primero los de destruccion y despues los de
 *                         reconstruccion, en el orden en que la busqueda los declara. Vacio
 *                         en HGS, que no tiene capa adaptativa, y en una ALNS que no llego a
 *                         buscar porque la fotografia no tenia pedidos o unidades; con
 *                         {@code null} se toma vacio
 */
public record ResultadoPlanificacion(
        String algoritmo,
        Solucion solucion,
        PerfilConvergencia perfil,
        long milisegundos,
        long iteraciones,
        long semilla,
        Map<String, Double> pesosOperadores) {

    public ResultadoPlanificacion {
        // Copia defensiva que conserva el orden de insercion; Map.copyOf no lo conserva.
        pesosOperadores = pesosOperadores == null || pesosOperadores.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pesosOperadores));
    }

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
