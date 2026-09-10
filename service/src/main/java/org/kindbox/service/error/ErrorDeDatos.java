package org.kindbox.service.error;

/**
 * No se pudo leer el escenario desde el directorio de datos configurado. Es un fallo del
 * servidor y no de la peticion, de modo que se traduce en un 500 con el detalle de que
 * archivo falto o vino mal formado.
 */
public class ErrorDeDatos extends RuntimeException {

    public ErrorDeDatos(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
