package org.kindbox.core.operacion;

import org.kindbox.core.modelo.TipoAveria;

public record SolicitudAveriaOperacion(String solicitudId, String placa, Integer tipo) {
    public SolicitudAveriaOperacion {
        solicitudId = SolicitudPedidoOperacion.texto(solicitudId, "solicitudId", 100);
        placa = SolicitudPedidoOperacion.texto(placa, "placa", 20);
        if (tipo == null) throw new IllegalArgumentException("El tipo de averia es obligatorio");
        TipoAveria.porCodigo(tipo);
    }
}
