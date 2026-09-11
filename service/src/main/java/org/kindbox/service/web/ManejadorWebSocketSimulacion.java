package org.kindbox.service.web;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.kindbox.service.dto.MensajeVisualizador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Extremo {@code /ws/simulacion}. Mantiene las sesiones abiertas y les retransmite el
 * mensaje que le entrega el difusor.
 *
 * <p>El enunciado pide que al visualizador pueda conectarse cualquier dispositivo en tiempo
 * real. Por eso lo que viaja es siempre una {@code InstantaneaSimulacion} completa y nunca
 * un delta: un cliente que llega a mitad de una corrida reconstruye la pantalla entera con
 * la primera fotografia que recibe.</p>
 *
 * <p><b>Estado inicial.</b> Para que esa primera pantalla sea inmediata y no haya que esperar
 * a la siguiente cadencia, el manejador conserva dos mensajes y se los envia al conectar, en
 * este orden: la cabecera {@code corrida}, que dice cual se esta ejecutando y con que
 * configuracion, y la ultima {@code instantanea} o, si ya termino, el {@code resultado}. Los
 * dos pertenecen siempre a la misma corrida: al anunciarse una nueva se descarta lo de la
 * anterior, de modo que nadie recibe al conectarse la fotografia de una corrida vieja como si
 * fuese el estado del momento. Mientras no arranque ninguna corrida nueva, lo conservado es lo
 * ultimo de la ultima, que es justo lo que el visualizador necesita para pintar la pantalla de
 * una corrida ya terminada.</p>
 *
 * <p>Un mensaje rezagado de la corrida anterior, que llegase por detras de la cabecera nueva,
 * se retransmite a quien ya esta conectado, porque lleva su propio identificador de corrida y
 * el cliente sabe descartarlo, pero no sustituye al estado inicial.</p>
 *
 * <p>El envio por una sesion de WebSocket no es seguro desde varios hilos a la vez, de modo
 * que cada escritura se sincroniza sobre su propia sesion. Toda la difusion la hace un solo
 * hilo del difusor, nunca el hilo de la simulacion.</p>
 */
@Component
public class ManejadorWebSocketSimulacion extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorWebSocketSimulacion.class);

    private final List<WebSocketSession> sesiones = new CopyOnWriteArrayList<>();

    /**
     * Lo que recibe un cliente al conectarse. Es inmutable y se sustituye entero en cada
     * difusion, de modo que quien lo lee ve siempre una pareja coherente y nunca la cabecera
     * de una corrida junto a la fotografia de otra.
     *
     * @param idCorrida corrida a la que pertenecen los dos mensajes
     * @param cabecera  mensaje de tipo corrida, o {@code null} si el difusor aun no lo mando
     * @param ultimo    ultima instantanea o resultado, o {@code null} si aun no hay ninguna
     */
    private record EstadoInicial(String idCorrida, String cabecera, String ultimo) {
    }

    private volatile EstadoInicial estadoInicial;

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        sesiones.add(sesion);
        LOG.info("Cliente conectado al visualizador: {} ({} en total)", sesion.getId(), sesiones.size());
        EstadoInicial inicial = estadoInicial;
        if (inicial == null) {
            return;
        }
        if (inicial.cabecera() != null) {
            enviar(sesion, inicial.cabecera());
        }
        if (inicial.ultimo() != null) {
            enviar(sesion, inicial.ultimo());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        sesiones.remove(sesion);
        LOG.info("Cliente desconectado del visualizador: {} ({} restantes)", sesion.getId(), sesiones.size());
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        LOG.debug("Error de transporte en la sesion {}: {}", sesion.getId(), error.toString());
        sesiones.remove(sesion);
        cerrar(sesion);
    }

    /**
     * Retransmite un mensaje ya serializado a todas las sesiones abiertas y actualiza con el
     * el estado inicial de las que lleguen despues.
     *
     * @param tipo      naturaleza del mensaje, de las que declara {@link MensajeVisualizador}
     * @param idCorrida corrida a la que pertenece el mensaje
     * @param texto     mensaje JSON completo
     */
    public void difundir(String tipo, String idCorrida, String texto) {
        recordar(tipo, idCorrida, texto);
        for (WebSocketSession sesion : sesiones) {
            enviar(sesion, texto);
        }
    }

    /** Numero de clientes conectados, que informa {@code GET /api/salud}. */
    public int clientesConectados() {
        return sesiones.size();
    }

    /** Actualiza el estado que recibiran los clientes que se conecten a partir de ahora. */
    private void recordar(String tipo, String idCorrida, String texto) {
        if (MensajeVisualizador.TIPO_CORRIDA.equals(tipo)) {
            // Arranca una corrida: lo conservado de la anterior deja de describir nada vigente.
            estadoInicial = new EstadoInicial(idCorrida, texto, null);
            return;
        }
        EstadoInicial vigente = estadoInicial;
        if (vigente == null) {
            estadoInicial = new EstadoInicial(idCorrida, null, texto);
        } else if (Objects.equals(vigente.idCorrida(), idCorrida)) {
            estadoInicial = new EstadoInicial(idCorrida, vigente.cabecera(), texto);
        }
    }

    private void enviar(WebSocketSession sesion, String texto) {
        if (!sesion.isOpen()) {
            sesiones.remove(sesion);
            return;
        }
        try {
            synchronized (sesion) {
                sesion.sendMessage(new TextMessage(texto));
            }
        } catch (IOException | IllegalStateException e) {
            // Un cliente que se cae no puede detener la corrida ni afectar a los demas.
            LOG.debug("No se pudo escribir en la sesion {}: {}", sesion.getId(), e.toString());
            sesiones.remove(sesion);
            cerrar(sesion);
        }
    }

    private static void cerrar(WebSocketSession sesion) {
        try {
            sesion.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignorada) {
            // La sesion ya estaba rota; no hay nada que recuperar.
        }
    }
}
