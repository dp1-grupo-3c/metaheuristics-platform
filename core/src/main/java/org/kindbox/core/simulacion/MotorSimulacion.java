package org.kindbox.core.simulacion;

import java.util.List;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.modelo.ParametrosOperacion;

public final class MotorSimulacion extends MotorEjecucion {
    public MotorSimulacion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores) {
        super(datos, configuracion, parametros, algoritmo, observadores);
    }
}
