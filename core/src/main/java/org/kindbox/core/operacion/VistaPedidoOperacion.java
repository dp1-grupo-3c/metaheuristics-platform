package org.kindbox.core.operacion;

import java.time.LocalDateTime;
import java.util.List;

public record VistaPedidoOperacion(int id, String codigoCliente, String nombreCliente,
        String telefonoCliente, String direccion, int x, int y, int cantidad, int pendiente,
        PrioridadPedido prioridad, EstadoPedido estado, boolean incumplido,
        LocalDateTime registro, LocalDateTime limite, List<Transicion> transiciones) {
    public VistaPedidoOperacion {
        transiciones = List.copyOf(transiciones);
    }

    public record Transicion(EstadoPedido estado, LocalDateTime fechaHora) { }
}
