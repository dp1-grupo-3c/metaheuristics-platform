package org.kindbox.core.operacion;

import java.time.Instant;

public final class ComandoOperacion {
    public enum Estado { ACEPTADO, APLICADO, RECHAZADO }
    public record Resultado(String comandoId, Estado estado, Instant aceptado, Instant aplicado,
                            Integer pedidoId, String error) { }

    private final String id;
    private final Object contenido;
    private final Instant aceptado;
    private Estado estado = Estado.ACEPTADO;
    private Instant aplicado;
    private Integer pedidoId;
    private String error;

    public ComandoOperacion(String id, Object contenido, Instant aceptado) {
        this.id = id;
        this.contenido = contenido;
        this.aceptado = aceptado;
    }

    public Object contenido() { return contenido; }
    public String id() { return id; }

    public void aplicar(Instant instante, Integer pedidoId) {
        estado = Estado.APLICADO;
        aplicado = instante;
        this.pedidoId = pedidoId;
    }

    public void rechazar(Instant instante, String mensaje) {
        estado = Estado.RECHAZADO;
        aplicado = instante;
        error = mensaje;
    }

    public Resultado resultado() {
        return new Resultado(id, estado, aceptado, aplicado, pedidoId, error);
    }
}
