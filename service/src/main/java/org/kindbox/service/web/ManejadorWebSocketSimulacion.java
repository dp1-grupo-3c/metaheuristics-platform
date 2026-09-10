package org.kindbox.service.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * la primera fotografia que recibe. Para que esa primera fotografia sea inmediata y no haya
 * que esperar a la siguiente cadencia, el manejador conserva la ultima difundida y se la
 * envia al conectar.</p>
 *
 * <p>El envio por una sesion de WebSocket no es seguro desde varios hilos a la vez, de modo
 * que cada escritura se sincroniza sobre su propia sesion. Toda la difusion la hace un solo
 * hilo del difusor, nunca el hilo de la simulacion.</p>
 */
@Component
public class ManejadorWebSocketSimulacion extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorWebSocketSimulacion.class);

    private final List<WebSocketSession> sesiones = new CopyOnWriteArrayList<>();

    private volatile String ultimoMensaje;

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        sesiones.add(sesion);
        LOG.info("Cliente conectado al visualizador: {} ({} en total)", sesion.getId(), sesiones.size());
        String inicial = ultimoMensaje;
        if (inicial != null) {
            enviar(sesion, inicial);
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
     * Retransmite un mensaje ya serializado a todas las sesiones abiertas y lo conserva
     * como estado inicial de las que lleguen despues.
     *
     * @param texto mensaje JSON completo
     */
    public void difundir(String texto) {
        this.ultimoMensaje = texto;
        for (WebSocketSession sesion : sesiones) {
            enviar(sesion, texto);
        }
    }

    /** Numero de clientes conectados, que informa {@code GET /api/salud}. */
    public int clientesConectados() {
        return sesiones.size();
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
