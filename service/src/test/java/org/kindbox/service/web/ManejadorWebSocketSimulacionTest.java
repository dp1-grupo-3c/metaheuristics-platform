package org.kindbox.service.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.kindbox.service.dto.MensajeVisualizador;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Estado que recibe un cliente al conectarse al canal {@code /ws/simulacion}.
 *
 * <p>El manejador conservaba el ultimo mensaje difundido y se lo enviaba a todo el que se
 * conectase, aunque fuese la fotografia de una corrida terminada hacia rato y aunque despues
 * hubiese arrancado otra, y nunca lo borraba. Ahora conserva una pareja coherente de una sola
 * corrida, la cabecera y su ultima fotografia o su resultado, y al anunciarse una corrida nueva
 * descarta lo de la anterior.</p>
 */
class ManejadorWebSocketSimulacionTest {

    private static final String CABECERA_1 = "{\"tipo\":\"corrida\",\"corrida\":\"sim-001\"}";
    private static final String FOTO_1 = "{\"tipo\":\"instantanea\",\"corrida\":\"sim-001\",\"n\":1}";
    private static final String FOTO_2 = "{\"tipo\":\"instantanea\",\"corrida\":\"sim-001\",\"n\":2}";
    private static final String RESULTADO_1 = "{\"tipo\":\"resultado\",\"corrida\":\"sim-001\"}";
    private static final String CABECERA_2 = "{\"tipo\":\"corrida\",\"corrida\":\"sim-002\"}";

    /** Sesion abierta que registra lo que se le escribe. */
    private static WebSocketSession sesion(String id) {
        WebSocketSession sesion = mock(WebSocketSession.class);
        given(sesion.getId()).willReturn(id);
        given(sesion.isOpen()).willReturn(true);
        return sesion;
    }

    /** Mensajes que el manejador escribio en la sesion, en orden. */
    private static List<String> recibidoPor(WebSocketSession sesion, int cuantos) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(sesion, org.mockito.Mockito.times(cuantos)).sendMessage(captor.capture());
        List<String> textos = new ArrayList<>(cuantos);
        for (TextMessage mensaje : captor.getAllValues()) {
            textos.add(mensaje.getPayload());
        }
        return textos;
    }

    @Test
    @DisplayName("Sin corrida alguna, quien se conecta no recibe nada")
    void sinCorridaNoRecibeNada() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        WebSocketSession cliente = sesion("s1");

        manejador.afterConnectionEstablished(cliente);

        verify(cliente, never()).sendMessage(any());
        assertEquals(1, manejador.clientesConectados(), "la sesion queda registrada igualmente");
    }

    @Test
    @DisplayName("Quien se conecta recibe la cabecera y la ultima fotografia de la corrida vigente")
    void recibeCabeceraYUltimaFotografia() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-001", CABECERA_1);
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_1);
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_2);

        WebSocketSession cliente = sesion("s1");
        manejador.afterConnectionEstablished(cliente);

        assertEquals(List.of(CABECERA_1, FOTO_2), recibidoPor(cliente, 2),
                "primero la cabecera y despues la ultima fotografia, sin las intermedias");
    }

    @Test
    @DisplayName("Terminada la corrida, quien se conecta recibe la cabecera y el resultado final")
    void recibeElResultadoDeLaCorridaTerminada() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-001", CABECERA_1);
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_1);
        manejador.difundir(MensajeVisualizador.TIPO_RESULTADO, "sim-001", RESULTADO_1);

        WebSocketSession cliente = sesion("s1");
        manejador.afterConnectionEstablished(cliente);

        assertEquals(List.of(CABECERA_1, RESULTADO_1), recibidoPor(cliente, 2),
                "la ultima corrida conocida sigue siendo la que se ofrece al conectar");
    }

    @Test
    @DisplayName("Al arrancar una corrida nueva se descarta lo de la anterior")
    void laCorridaNuevaDescartaLaAnterior() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-001", CABECERA_1);
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_1);
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-002", CABECERA_2);

        WebSocketSession cliente = sesion("s1");
        manejador.afterConnectionEstablished(cliente);

        assertEquals(List.of(CABECERA_2), recibidoPor(cliente, 1),
                "solo la cabecera de la corrida nueva, que aun no tiene fotografia");
    }

    @Test
    @DisplayName("Un mensaje rezagado de la corrida anterior no sustituye al estado inicial")
    void elMensajeRezagadoNoSustituyeElEstado() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-001", CABECERA_1);
        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-002", CABECERA_2);
        // Fotografia de la corrida vieja que el difusor tenia ya fuera de la cola.
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_2);

        WebSocketSession cliente = sesion("s1");
        manejador.afterConnectionEstablished(cliente);

        assertEquals(List.of(CABECERA_2), recibidoPor(cliente, 1),
                "el estado inicial sigue siendo el de la corrida vigente");
    }

    @Test
    @DisplayName("La difusion llega a las sesiones ya conectadas")
    void ladifusionLlegaALosConectados() throws Exception {
        ManejadorWebSocketSimulacion manejador = new ManejadorWebSocketSimulacion();
        WebSocketSession cliente = sesion("s1");
        manejador.afterConnectionEstablished(cliente);

        manejador.difundir(MensajeVisualizador.TIPO_CORRIDA, "sim-001", CABECERA_1);
        manejador.difundir(MensajeVisualizador.TIPO_INSTANTANEA, "sim-001", FOTO_1);

        assertEquals(List.of(CABECERA_1, FOTO_1), recibidoPor(cliente, 2),
                "el cliente conectado recibe todo lo que se difunde, no solo lo conservado");
    }
}
