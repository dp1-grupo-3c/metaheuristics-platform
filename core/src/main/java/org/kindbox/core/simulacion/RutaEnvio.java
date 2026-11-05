package org.kindbox.core.simulacion;

// Envio de un pedido y el camino que le falta a su unidad hasta entregarlo, como pares x,y.
public record RutaEnvio(EnvioPedido envio, int[] camino) {

    public RutaEnvio {
        camino = camino == null ? new int[0] : camino.clone();
    }

    @Override
    public int[] camino() {
        return camino.clone();
    }
}
