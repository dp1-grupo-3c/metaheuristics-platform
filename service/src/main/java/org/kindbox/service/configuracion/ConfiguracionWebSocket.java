package org.kindbox.service.configuracion;

import org.kindbox.service.web.ManejadorWebSocketSimulacion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Publica el canal {@code /ws/simulacion} por el que viajan las fotografias del estado.
 *
 * <p>Se usa WebSocket a secas y no STOMP: el trafico es de un solo sentido y de un solo
 * tema, el servidor empuja fotografias completas y el cliente no publica nada, de modo que
 * una capa de mensajeria solo anadiria dependencias al frontend.</p>
 *
 * <p>Los origenes quedan abiertos por la misma razon que en la politica de CORS: durante el
 * desarrollo el visualizador corre en otro puerto y desde otros dispositivos de la red.</p>
 */
@Configuration
@EnableWebSocket
public class ConfiguracionWebSocket implements WebSocketConfigurer {

    /** Ruta del canal de retransmision de instantaneas. */
    public static final String RUTA_SIMULACION = "/ws/simulacion";

    /**
     * Tamano del buffer de texto de cada sesion, en bytes.
     *
     * <p>Una fotografia completa de 37 unidades con sus trazos recorridos y pendientes
     * supera con holgura los 8 KB por defecto del contenedor, y al superarlos el mensaje
     * viaja troceado en fragmentos. Los navegadores los reensamblan solos, pero un cliente
     * de escritorio escrito a mano tendria que hacerlo, de modo que se amplia el buffer para
     * que cada fotografia viaje en un unico marco.</p>
     *
     * <p>Hacen falta las dos mitades: {@code ServletServerContainerFactoryBean} solo fija el
     * buffer de <b>entrada</b>, es decir el mensaje mas grande que el servidor acepta
     * recibir. El troceado de los mensajes que el servidor <b>envia</b> lo gobierna la
     * propiedad de sistema {@value #PROPIEDAD_BUFFER_SALIDA}, que vale 8192 por defecto y
     * que fija {@code AplicacionKindBox.main}. Medido con un cliente propio que cuenta
     * marcos: sin ella cada fotografia llegaba en dos o tres marcos (media 2.07 sobre 60
     * fotografias de unos 11 KB).</p>
     */
    public static final int BUFFER_TEXTO_BYTES = 512 * 1024;

    /**
     * Propiedad de sistema de Tomcat que fija el buffer con que se ESCRIBEN los mensajes y
     * por tanto en cuantos marcos se trocea cada fotografia. Su valor por defecto es 8192.
     * Se lee una sola vez, al cargar las clases del contenedor, de modo que hay que fijarla
     * antes de arrancar Spring: lo hace {@code AplicacionKindBox.main}.
     */
    public static final String PROPIEDAD_BUFFER_SALIDA = "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE";

    private final ManejadorWebSocketSimulacion manejador;

    public ConfiguracionWebSocket(ManejadorWebSocketSimulacion manejador) {
        this.manejador = manejador;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registro) {
        registro.addHandler(manejador, RUTA_SIMULACION).setAllowedOriginPatterns("*");
    }

    /** Amplia el buffer de texto del contenedor para que cada fotografia viaje entera. */
    @Bean
    public ServletServerContainerFactoryBean contenedorWebSocket() {
        ServletServerContainerFactoryBean contenedor = new ServletServerContainerFactoryBean();
        contenedor.setMaxTextMessageBufferSize(BUFFER_TEXTO_BYTES);
        contenedor.setMaxBinaryMessageBufferSize(BUFFER_TEXTO_BYTES);
        return contenedor;
    }
}
