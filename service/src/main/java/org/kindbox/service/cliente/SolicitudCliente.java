package org.kindbox.service.cliente;

/**
 * Cuerpo del alta y de la edicion de un cliente. La validacion vive en
 * {@link ServicioClientes} y no en el constructor: asi un dato inadmisible responde un 400
 * con su motivo y no el error generico de JSON mal formado.
 */
public record SolicitudCliente(TipoCliente tipo, String documento, String nombre, String telefono,
                               String contacto, String direccion, Integer x, Integer y) { }
