package org.kindbox.core.operacion;

public enum PrioridadPedido {
    NORMAL(36), PRIORITARIO_4(18), PRIORITARIO_3(12), PRIORITARIO_2(8), PRIORITARIO_1(4);

    private final int plazoHoras;

    PrioridadPedido(int plazoHoras) {
        this.plazoHoras = plazoHoras;
    }

    public int plazoHoras() {
        return plazoHoras;
    }
}
