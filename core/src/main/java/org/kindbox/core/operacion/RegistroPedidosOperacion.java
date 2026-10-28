package org.kindbox.core.operacion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.io.CalendarioEscenario;

public final class RegistroPedidosOperacion {
    private final Map<Integer, PedidoOperativo> pedidos = new LinkedHashMap<>();
    private int siguienteId;

    public PedidoOperativo crear(SolicitudPedidoOperacion solicitud, long minuto, CalendarioEscenario calendario) {
        PedidoOperativo pedido = new PedidoOperativo(Math.incrementExact(siguienteId), solicitud, minuto, calendario);
        siguienteId = pedido.pedido().id();
        pedidos.put(siguienteId, pedido);
        return pedido;
    }

    public PedidoOperativo buscar(int id) { return pedidos.get(id); }

    public List<VistaPedidoOperacion> vistas() {
        return pedidos.values().stream().map(PedidoOperativo::vista).toList();
    }
}
