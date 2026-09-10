package org.kindbox.service.dto;

/**
 * Sobre de todo mensaje que viaja por {@code /ws/simulacion}.
 *
 * <p>El contenido es siempre completo y nunca un delta, porque el enunciado pide que
 * cualquier dispositivo pueda conectarse en tiempo real: el cliente que llega a mitad de
 * una corrida reconstruye la pantalla entera con el primer mensaje que recibe. El campo
 * {@code tipo} solo le dice a que parte de la pantalla corresponde la carga.</p>
 *
 * @param tipo    naturaleza del mensaje: instantanea, resultado o corrida
 * @param corrida identificador de la corrida a la que pertenece
 * @param carga   contenido, que es un record del paquete {@code simulacion} del nucleo
 */
public record MensajeVisualizador(String tipo, String corrida, Object carga) {

    /** Fotografia completa del estado de la simulacion. */
    public static final String TIPO_INSTANTANEA = "instantanea";
    /** Resumen final de la corrida, que alimenta el modal de fin de simulacion. */
    public static final String TIPO_RESULTADO = "resultado";
    /** Cabecera de la corrida, que se envia al arrancarla. */
    public static final String TIPO_CORRIDA = "corrida";
}
