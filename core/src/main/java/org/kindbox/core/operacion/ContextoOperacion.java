package org.kindbox.core.operacion;

import org.kindbox.core.modelo.ParametrosOperacion;

public final class ContextoOperacion {
    private final String id;
    private final ParametrosOperacion parametros = new ParametrosOperacion();
    private final RegistroPedidosOperacion pedidos = new RegistroPedidosOperacion();
    private long revision;
    private VistaOperacion.Estado estado = VistaOperacion.Estado.EN_CURSO;

    public ContextoOperacion(String id) {
        this.id = java.util.Objects.requireNonNull(id);
    }

    public String id() { return id; }
    public ParametrosOperacion parametros() { return parametros; }
    public RegistroPedidosOperacion pedidos() { return pedidos; }
    public long siguienteRevision() { return ++revision; }
    public VistaOperacion.Estado estado() { return estado; }
    public void estado(VistaOperacion.Estado estado) { this.estado = estado; }
}
