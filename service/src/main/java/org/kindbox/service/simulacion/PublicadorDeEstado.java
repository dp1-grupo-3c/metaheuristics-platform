package org.kindbox.service.simulacion;

import org.kindbox.core.simulacion.ObservadorSimulacion;

/**
 * Salida del servicio de aplicacion hacia los clientes conectados en tiempo real.
 *
 * <p>Existe para que el ciclo de vida de las corridas no dependa del transporte: el servicio
 * pide un observador y publica avisos, y quien implementa esta interfaz decide si eso viaja
 * por WebSocket, por otro canal o por ninguno. La implementacion vigente es el difusor del
 * paquete {@code web}.</p>
 */
public interface PublicadorDeEstado {

    /**
     * Observador que retransmite a los clientes todo lo que ocurra en una corrida.
     *
     * <p>El motor lo invoca desde el hilo de la simulacion, de modo que la implementacion
     * debe limitarse a encolar.</p>
     *
     * @param idCorrida identificador que acompana a cada mensaje
     */
    ObservadorSimulacion observadorDe(String idCorrida);

    /**
     * Publica un mensaje suelto, por ejemplo la cabecera de una corrida recien arrancada.
     *
     * @param tipo      naturaleza del mensaje
     * @param idCorrida corrida a la que pertenece
     * @param carga     contenido serializable
     */
    void publicar(String tipo, String idCorrida, Object carga);
}
