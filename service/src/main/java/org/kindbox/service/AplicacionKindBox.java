package org.kindbox.service;

import org.kindbox.service.configuracion.ConfiguracionWebSocket;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Punto de arranque de la API que alimenta al componente visualizador.
 *
 * <p>El requisito (b) de los escenarios del enunciado pide presentar de manera grafica la
 * informacion relevante del desempeno de las operaciones y que al visualizador pueda
 * conectarse cualquier dispositivo en tiempo real. Este modulo es la mitad de servidor de
 * ese requisito: expone la operacion por REST y retransmite el estado por WebSocket, sin
 * que el nucleo sepa nada de HTTP.</p>
 *
 * <p>El modulo {@code core} no tiene dependencias externas de runtime, conforme al
 * requisito no funcional (a); Spring vive unicamente aqui.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(PropiedadesKindBox.class)
public class AplicacionKindBox {

    public static void main(String[] args) {
        // El buffer con que el contenedor ESCRIBE los mensajes de WebSocket se lee una sola
        // vez, al cargar sus clases, de modo que hay que fijarlo antes de arrancar Spring.
        // Sin esto cada fotografia del visualizador viaja troceada en marcos de 8 KB.
        if (System.getProperty(ConfiguracionWebSocket.PROPIEDAD_BUFFER_SALIDA) == null) {
            System.setProperty(ConfiguracionWebSocket.PROPIEDAD_BUFFER_SALIDA,
                    String.valueOf(ConfiguracionWebSocket.BUFFER_TEXTO_BYTES));
        }
        SpringApplication.run(AplicacionKindBox.class, args);
    }
}
