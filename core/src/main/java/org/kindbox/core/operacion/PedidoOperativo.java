package org.kindbox.core.operacion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Pedido;

public final class PedidoOperativo {
    private final Pedido pedido;
    private final SolicitudPedidoOperacion solicitud;
    private final CalendarioEscenario calendario;
    private final List<VistaPedidoOperacion.Transicion> transiciones = new ArrayList<>();
    private EstadoPedido estado;
    private int pendiente;
    private boolean incumplido;

    public PedidoOperativo(int id, SolicitudPedidoOperacion solicitud, long minuto, CalendarioEscenario calendario) {
        this.solicitud = solicitud;
        this.calendario = calendario;
        pedido = new Pedido(id, solicitud.codigoCliente(), Ciudad.nodo(solicitud.x(), solicitud.y()),
                solicitud.cantidad(), minuto, solicitud.prioridad().plazoHoras());
        pendiente = solicitud.cantidad();
        cambiar(EstadoPedido.REGISTRADO, minuto);
    }

    public Pedido pedido() { return pedido; }

    public void cambiar(EstadoPedido nuevo, long minuto) {
        if (nuevo == null) throw new IllegalArgumentException("El estado es obligatorio");
        if (nuevo == estado) return;
        if (!activo()) throw new IllegalStateException("El pedido ya no admite transiciones");
        boolean permitido = estado == null && nuevo == EstadoPedido.REGISTRADO
                || estado == EstadoPedido.REGISTRADO && (nuevo == EstadoPedido.PLANIFICADO || nuevo == EstadoPedido.CANCELADO)
                || estado == EstadoPedido.PLANIFICADO && (nuevo == EstadoPedido.EN_TRANSITO
                    || nuevo == EstadoPedido.EN_REPLANIFICACION || nuevo == EstadoPedido.CANCELADO)
                || estado == EstadoPedido.EN_TRANSITO && (nuevo == EstadoPedido.EN_REPLANIFICACION
                    || nuevo == EstadoPedido.ENTREGADO_PARCIALMENTE || nuevo == EstadoPedido.ENTREGADO)
                || estado == EstadoPedido.EN_REPLANIFICACION && (nuevo == EstadoPedido.PLANIFICADO
                    || nuevo == EstadoPedido.EN_TRANSITO || nuevo == EstadoPedido.ENTREGADO_PARCIALMENTE
                    || nuevo == EstadoPedido.ENTREGADO)
                || estado == EstadoPedido.ENTREGADO_PARCIALMENTE && (nuevo == EstadoPedido.EN_REPLANIFICACION
                    || nuevo == EstadoPedido.ENTREGADO);
        if (!permitido) throw new IllegalStateException("La transicion del pedido no esta permitida");
        estado = nuevo;
        LocalDateTime fecha = calendario.aFecha(minuto);
        transiciones.add(new VistaPedidoOperacion.Transicion(nuevo, fecha));
    }

    public void descargar(int remanente, long minuto) {
        if (remanente < 0 || remanente > pendiente) throw new IllegalArgumentException("La descarga es invalida");
        if (!activo() || estado == EstadoPedido.REGISTRADO) {
            throw new IllegalStateException("El pedido no admite una descarga");
        }
        if (estado == EstadoPedido.PLANIFICADO) cambiar(EstadoPedido.EN_TRANSITO, minuto);
        pendiente = remanente;
        cambiar(remanente == 0 ? EstadoPedido.ENTREGADO : EstadoPedido.ENTREGADO_PARCIALMENTE, minuto);
    }

    public void incumplir() { incumplido = true; }

    public boolean activo() {
        return estado != EstadoPedido.ENTREGADO && estado != EstadoPedido.CANCELADO && !incumplido;
    }

    public VistaPedidoOperacion vista() {
        return new VistaPedidoOperacion(pedido.id(), solicitud.codigoCliente(), solicitud.nombreCliente(),
                solicitud.telefonoCliente(), solicitud.direccion(), pedido.x(), pedido.y(), pedido.cantidad(),
                pendiente, solicitud.prioridad(), estado, incumplido, calendario.aFecha(pedido.minutoRegistro()),
                calendario.aFecha(pedido.minutoLimite()), transiciones);
    }
}
