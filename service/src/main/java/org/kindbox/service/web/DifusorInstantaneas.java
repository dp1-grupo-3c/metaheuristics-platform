package org.kindbox.service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.kindbox.core.simulacion.InstantaneaSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.kindbox.service.dto.MensajeVisualizador;
import org.kindbox.service.simulacion.PublicadorDeEstado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puente entre el motor de simulacion y el canal {@code /ws/simulacion}.
 *
 * <p>El contrato de {@code ObservadorSimulacion} advierte de que sus metodos se invocan
 * desde el hilo de la simulacion y de que bloquear ahi retrasa el reloj simulado. Por eso el
 * observador que produce esta clase solo encola: ni serializa a JSON ni escribe en ningun
 * socket. Un unico hilo propio saca de la cola, serializa y retransmite.</p>
 *
 * <p>La cola es acotada y, cuando se llena, descarta la fotografia mas antigua en lugar de
 * bloquear al motor. Es la politica correcta para este trafico: cada fotografia es completa
 * y sustituye por si sola a todas las anteriores, de modo que perder una intermedia no deja
 * al visualizador en un estado incoherente, mientras que frenar la simulacion por un cliente
 * lento si arruinaria la corrida.</p>
 *
 * <p>Por la misma razon, la cabecera de una corrida recien arrancada vacia la cola: las
 * fotografias de la corrida anterior que aun no se hubieran retransmitido ya no describen
 * nada vigente y solo servirian para pintar en el visualizador un estado que no existe.</p>
 */
@Component
public class DifusorInstantaneas implements PublicadorDeEstado {

    private static final Logger LOG = LoggerFactory.getLogger(DifusorInstantaneas.class);

    private final ManejadorWebSocketSimulacion manejador;
    private final ObjectMapper mapeador;
    private final BlockingQueue<MensajeVisualizador> cola;

    private Thread hilo;
    private volatile boolean activo = true;

    public DifusorInstantaneas(ManejadorWebSocketSimulacion manejador, ObjectMapper mapeador,
                               PropiedadesKindBox propiedades) {
        this.manejador = manejador;
        this.mapeador = mapeador;
        this.cola = new ArrayBlockingQueue<>(Math.max(2, propiedades.getCapacidadColaDifusion()));
    }

    @PostConstruct
    void arrancar() {
        hilo = Thread.ofPlatform().name("difusor-visualizador").daemon(true).start(this::bucle);
    }

    @PreDestroy
    void detener() {
        activo = false;
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    /**
     * Observador que empuja al canal todo lo que ocurra en una corrida concreta.
     *
     * @param idCorrida identificador que acompana a cada mensaje
     */
    @Override
    public ObservadorSimulacion observadorDe(String idCorrida) {
        return new ObservadorDifusor(idCorrida);
    }

    /** Encola un mensaje arbitrario, por ejemplo la cabecera de una corrida recien arrancada. */
    @Override
    public void publicar(String tipo, String idCorrida, Object carga) {
        if (MensajeVisualizador.TIPO_CORRIDA.equals(tipo)) {
            // Arranca una corrida nueva: lo que quede en la cola es de la anterior y ya no
            // describe nada vigente, de modo que se descarta antes de anunciar la nueva.
            cola.clear();
        }
        encolar(new MensajeVisualizador(tipo, idCorrida, carga));
    }

    private void encolar(MensajeVisualizador mensaje) {
        // Sin espera: si la cola esta llena se descarta la fotografia mas antigua, que ya
        // quedo obsoleta en cuanto llego esta.
        while (!cola.offer(mensaje)) {
            cola.poll();
        }
    }

    private void bucle() {
        while (activo) {
            try {
                MensajeVisualizador mensaje = cola.take();
                manejador.difundir(mensaje.tipo(), mensaje.corrida(), mapeador.writeValueAsString(mensaje));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                LOG.warn("No se pudo retransmitir un mensaje al visualizador: {}", e.toString());
            }
        }
    }

    /** Observador ligado a una corrida, que se limita a encolar. */
    private final class ObservadorDifusor implements ObservadorSimulacion {

        private final String idCorrida;

        private ObservadorDifusor(String idCorrida) {
            this.idCorrida = idCorrida;
        }

        @Override
        public void alTomarFotografia(InstantaneaSimulacion instantanea) {
            encolar(new MensajeVisualizador(MensajeVisualizador.TIPO_INSTANTANEA, idCorrida, instantanea));
        }

        @Override
        public void alTerminar(ResultadoSimulacion resultado) {
            encolar(new MensajeVisualizador(MensajeVisualizador.TIPO_RESULTADO, idCorrida, resultado));
        }
    }
}
