package org.kindbox.service.simulacion;

import java.util.List;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.springframework.stereotype.Component;

/**
 * Unico punto del servicio que construye el motor de simulacion del nucleo.
 *
 * <p>Hubo aqui una interfaz propia del modulo ({@code MotorDeSimulacion}) mientras
 * {@code org.kindbox.core.simulacion.MotorSimulacion} no existia. Publicado ya el motor
 * real, esa costura se retiro: el servicio consume directamente el motor del nucleo, con
 * lo que gana los metodos que la interfaz no reproducia y que el visualizador necesita
 * ({@code minutoActual()}, {@code estadoCorrida()}, {@code parametros()} y
 * {@code agregarObservador(..)}).</p>
 */
@Component
public class FabricaMotor {

    /** Nombre del motor en uso, que informa la comprobacion de vida. */
    public String descripcion() {
        return MotorSimulacion.class.getName();
    }

    /**
     * Crea el motor de la corrida.
     *
     * @param datos         escenario cargado del directorio de datos
     * @param configuracion parametros de la corrida
     * @param parametros    parametros de operacion vivos, modificables en caliente
     * @param algoritmo     planificador que resuelve cada fotografia
     * @param observadores  receptores de fotografias y avisos
     */
    public MotorSimulacion crear(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                                 ParametrosOperacion parametros, Algoritmo algoritmo,
                                 List<ObservadorSimulacion> observadores) {
        return new MotorSimulacion(datos, configuracion, parametros, algoritmo, observadores);
    }
}
