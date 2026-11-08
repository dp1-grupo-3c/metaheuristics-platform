package org.kindbox.service.simulacion;

import java.util.List;
import org.kindbox.core.io.FuenteDias;
import org.kindbox.core.io.FuenteTramos;
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

    /**
     * Crea el motor de un escenario abierto, que pide cada mes a {@code siguientes} a medida
     * que avanza en lugar de recibir el rango entero ya cargado.
     *
     * @param datos      primer mes del escenario, con la flota y el mantenimiento preventivo
     * @param siguientes fuente de los meses posteriores al primero
     */
    public MotorSimulacion crearAbierto(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                                        ParametrosOperacion parametros, Algoritmo algoritmo,
                                        List<ObservadorSimulacion> observadores, FuenteTramos siguientes) {
        return new MotorSimulacion(datos, configuracion, parametros, algoritmo, observadores, siguientes);
    }

    // Horizonte fijo que recibe los pedidos de a un dia desde dias.
    public MotorSimulacion crearPorDias(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                                        ParametrosOperacion parametros, Algoritmo algoritmo,
                                        List<ObservadorSimulacion> observadores, FuenteDias dias) {
        return new MotorSimulacion(datos, configuracion, parametros, algoritmo, observadores, dias);
    }
}
