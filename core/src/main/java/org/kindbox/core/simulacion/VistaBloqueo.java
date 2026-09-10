package org.kindbox.core.simulacion;

/**
 * Tramo bloqueado vigente, tal como lo dibuja el visualizador.
 *
 * @param nodos        poligonal como pares x,y consecutivos
 * @param minutoInicio instante de activacion
 * @param minutoFin    instante de desactivacion
 */
public record VistaBloqueo(int[] nodos, long minutoInicio, long minutoFin) {

    public VistaBloqueo {
        nodos = nodos.clone();
    }

    @Override
    public int[] nodos() {
        return nodos.clone();
    }
}
