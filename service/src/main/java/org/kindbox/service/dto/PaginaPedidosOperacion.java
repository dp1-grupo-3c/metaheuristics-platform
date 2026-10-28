package org.kindbox.service.dto;

import java.util.List;
import org.kindbox.core.operacion.VistaPedidoOperacion;

public record PaginaPedidosOperacion(String contextoId, long revision, List<VistaPedidoOperacion> pedidos,
        int pagina, int tamanoPagina, int totalPedidos, int totalPaginas) {
    public PaginaPedidosOperacion { pedidos = List.copyOf(pedidos); }
}
