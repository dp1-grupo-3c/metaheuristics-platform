package org.kindbox.service.error;

/**
 * La corrida, la unidad o el recurso pedido no existe. Se traduce en un 404 con mensaje
 * en espanol.
 */
public class RecursoNoEncontrado extends RuntimeException {

    public RecursoNoEncontrado(String mensaje) {
        super(mensaje);
    }
}
