package org.kindbox.service.dto;

import java.util.List;
import org.kindbox.core.simulacion.RutaEnvio;
import org.kindbox.service.error.SolicitudInvalida;

// Respuesta de las rutas de un pedido: su destino y el camino de cada unidad que lo lleva.
public record RutasPedido(int idPedido, int destinoX, int destinoY, int cantidad, List<RutaEnvio> rutas) {

    public RutasPedido {
        rutas = List.copyOf(rutas);
    }

    // Con paquete deja solo la ruta que lo lleva; un numero fuera del pedido es un error del cliente.
    public static RutasPedido de(int idPedido, int destinoX, int destinoY, int cantidad, List<RutaEnvio> rutas,
                                 Integer paquete) {
        if (paquete == null) {
            return new RutasPedido(idPedido, destinoX, destinoY, cantidad, rutas);
        }
        if (paquete < 1 || paquete > cantidad) {
            throw new SolicitudInvalida("El paquete " + paquete + " no existe: el pedido " + idPedido
                    + " tiene paquetes del 1 al " + cantidad);
        }
        return new RutasPedido(idPedido, destinoX, destinoY, cantidad,
                rutas.stream().filter(r -> r.envio().lleva(paquete)).toList());
    }
}
