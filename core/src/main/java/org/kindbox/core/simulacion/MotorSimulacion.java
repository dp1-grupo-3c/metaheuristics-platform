package org.kindbox.core.simulacion;

import java.util.List;
import org.kindbox.core.io.FuenteDias;
import org.kindbox.core.io.FuenteTramos;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.modelo.ParametrosOperacion;

public final class MotorSimulacion extends MotorEjecucion {
    public MotorSimulacion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores) {
        super(datos, configuracion, parametros, algoritmo, observadores);
    }

    /** Escenario abierto: arranca con el mes de {@code datos} y pide los siguientes a {@code fuente}. */
    public MotorSimulacion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores, FuenteTramos fuente) {
        super(datos, configuracion, parametros, algoritmo, observadores, fuente);
    }

    // Horizonte fijo que arranca con el primer dia de datos y pide los demas a dias.
    public MotorSimulacion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores, FuenteDias dias) {
        super(datos, configuracion, parametros, algoritmo, observadores, dias);
    }
}
