package org.kindbox.service.error;

/**
 * La operacion pedida choca con el estado actual del servicio: arrancar una simulacion
 * cuando ya hay una en curso, cancelar una que ya termino o pedir el resumen final de una
 * que sigue corriendo. Se traduce en un 409.
 */
public class ConflictoDeEstado extends RuntimeException {

    public ConflictoDeEstado(String mensaje) {
        super(mensaje);
    }
}
