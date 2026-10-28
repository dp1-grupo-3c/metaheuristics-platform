package org.kindbox.core.operacion;

import org.kindbox.core.modelo.Ciudad;

public record SolicitudPedidoOperacion(String solicitudId, String codigoCliente, String nombreCliente,
        String telefonoCliente, String direccion, Integer x, Integer y, Integer cantidad,
        PrioridadPedido prioridad) {
    public SolicitudPedidoOperacion {
        solicitudId = texto(solicitudId, "solicitudId", 100);
        codigoCliente = texto(codigoCliente, "codigoCliente", 100);
        nombreCliente = texto(nombreCliente, "nombreCliente", 200);
        telefonoCliente = texto(telefonoCliente, "telefonoCliente", 40);
        direccion = texto(direccion, "direccion", 300);
        if (x == null || y == null || x < 0 || x > 70 || y < 0 || y > 50) {
            throw new IllegalArgumentException("El destino debe ser un nodo de la ciudad");
        }
        Ciudad.nodo(x, y);
        if (cantidad == null || cantidad < 1 || prioridad == null) {
            throw new IllegalArgumentException("Cantidad positiva y prioridad son obligatorias");
        }
    }

    static String texto(String valor, String campo, int limite) {
        if (valor == null || valor.isBlank() || valor.trim().length() > limite) {
            throw new IllegalArgumentException("El campo " + campo + " es obligatorio y debe respetar su longitud maxima");
        }
        return valor.trim();
    }
}
