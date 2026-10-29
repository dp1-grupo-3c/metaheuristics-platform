package org.kindbox.service.cliente;

import java.time.Instant;

/**
 * Cliente registrado por el operador.
 *
 * <p>El documento (DNI de la persona o RUC de la empresa) lo identifica y es el
 * {@code codigoCliente} que viaja en cada pedido de la operacion diaria. La direccion y el
 * nodo {@code (x, y)} son el destino de entrega habitual; cada pedido puede cambiarlos.</p>
 *
 * @param contacto persona de contacto de una empresa; {@code null} si no se indico
 */
public record Cliente(TipoCliente tipo, String documento, String nombre, String telefono,
                      String contacto, String direccion, int x, int y,
                      Instant registrado, Instant actualizado) { }
