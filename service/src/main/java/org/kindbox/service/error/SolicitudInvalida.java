package org.kindbox.service.error;

/**
 * Los parametros de la peticion no son admisibles: fechas invertidas, salto no positivo,
 * algoritmo desconocido o archivo de averias mal formado. Se traduce en un 400.
 */
public class SolicitudInvalida extends RuntimeException {

    public SolicitudInvalida(String mensaje) {
        super(mensaje);
    }

    public SolicitudInvalida(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
