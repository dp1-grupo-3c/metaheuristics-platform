package org.kindbox.core.operacion;

import java.time.LocalDateTime;
import java.util.List;
import org.kindbox.core.simulacion.EnvioPedido;

public record VistaPedidoOperacion(int id, String codigoCliente, String nombreCliente,
        String telefonoCliente, String direccion, int x, int y, int cantidad, int pendiente,
        PrioridadPedido prioridad, EstadoPedido estado, boolean incumplido,
        LocalDateTime registro, LocalDateTime limite, List<Transicion> transiciones,
        // Codigos de las unidades que lo descargaron, en orden de descarga.
        List<String> unidadesEntrega,
        // Unidades que llevan el pedido, con sus horarios, segun la fotografia publicada.
        List<EnvioPedido> envios) {
    public VistaPedidoOperacion {
        transiciones = List.copyOf(transiciones);
        unidadesEntrega = unidadesEntrega == null ? List.of() : List.copyOf(unidadesEntrega);
        envios = envios == null ? List.of() : List.copyOf(envios);
    }

    public VistaPedidoOperacion conEnvios(List<EnvioPedido> nuevos) {
        return new VistaPedidoOperacion(id, codigoCliente, nombreCliente, telefonoCliente, direccion, x, y,
                cantidad, pendiente, prioridad, estado, incumplido, registro, limite, transiciones, unidadesEntrega, nuevos);
    }

    public record Transicion(EstadoPedido estado, LocalDateTime fechaHora) { }
}
