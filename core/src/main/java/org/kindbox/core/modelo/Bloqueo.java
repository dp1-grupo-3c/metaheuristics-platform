package org.kindbox.core.modelo;

import java.util.Arrays;

/**
 * Tramo de calle bloqueado, segun la respuesta 7 del cuestionario.
 *
 * <p>El bloqueo se describe como una poligonal abierta de nodos consecutivos de la
 * reticula. Cada par de nodos contiguos define un tramo intransitable en ambos sentidos,
 * porque todas las calles son de doble sentido (requisito no funcional (c)). Una unidad
 * que alcanza un extremo bloqueado no puede atravesarlo ni girar, de modo que debe
 * regresar por donde vino; ese comportamiento surge de forma natural al retirar las
 * aristas del grafo de recorridos.</p>
 *
 * <p>Para el curso 1INF54 2026-2 solo se trabaja con poligonales abiertas, de modo que
 * siempre existe un camino hacia cualquier nodo de la ciudad.</p>
 *
 * @param nodos        secuencia de nodos de la poligonal, en orden
 * @param minutoInicio instante de activacion, en minutos desde el inicio del escenario
 * @param minutoFin    instante de desactivacion, exclusivo
 */
public record Bloqueo(int[] nodos, long minutoInicio, long minutoFin) {

    public Bloqueo {
        if (nodos == null || nodos.length < 2) {
            throw new IllegalArgumentException("Un bloqueo necesita al menos dos nodos");
        }
        if (minutoFin <= minutoInicio) {
            throw new IllegalArgumentException("Ventana de bloqueo vacia: [" + minutoInicio + "," + minutoFin + ")");
        }
        nodos = nodos.clone();
    }

    @Override
    public int[] nodos() {
        return nodos.clone();
    }

    /** Numero de tramos (aristas) que componen la poligonal. */
    public int cantidadTramos() {
        return nodos.length - 1;
    }

    /** Nodo inicial del tramo {@code i}. */
    public int nodoOrigenTramo(int i) {
        return nodos[i];
    }

    /** Nodo final del tramo {@code i}. */
    public int nodoDestinoTramo(int i) {
        return nodos[i + 1];
    }

    /** Indica si el bloqueo esta vigente en el instante dado. */
    public boolean vigenteEn(long minuto) {
        return minuto >= minutoInicio && minuto < minutoFin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Bloqueo b
                && minutoInicio == b.minutoInicio
                && minutoFin == b.minutoFin
                && Arrays.equals(nodos, b.nodos);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(nodos) * 31 + Long.hashCode(minutoInicio) * 7 + Long.hashCode(minutoFin);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Bloqueo[").append(minutoInicio).append("..").append(minutoFin).append("] ");
        for (int i = 0; i < nodos.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(Ciudad.texto(nodos[i]));
        }
        return sb.toString();
    }
}
