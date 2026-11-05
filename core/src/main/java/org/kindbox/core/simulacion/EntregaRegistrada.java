package org.kindbox.core.simulacion;

import java.time.LocalDateTime;

// Descarga hecha en un cliente: cuando, que pedido, que unidad, desde que almacen se cargo y cuantos paquetes.
public record EntregaRegistrada(long minuto, LocalDateTime fechaHora, int idPedido, String codigoUnidad,
                                int idAlmacenCarga, int cantidad) {
}
