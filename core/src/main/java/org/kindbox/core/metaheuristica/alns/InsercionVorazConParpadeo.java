package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.util.Aleatorio;

/**
 * Insercion voraz con parpadeo de Christiaens y Vanden Berghe (2020), recogida en el apartado
 * 7.3.2 del ISA: la insercion voraz descartando cada posicion candidata con una probabilidad
 * fija.
 *
 * <p>El parpadeo reduce la codicia del operador sin sustituirla por azar completo. La
 * insercion voraz pura devuelve siempre la misma reconstruccion ante el mismo banco, de modo
 * que la busqueda cicla; una insercion aleatoria rompe el ciclo pero destruye la calidad. Con
 * una probabilidad de parpadeo del orden del uno al diez por ciento el operador sigue siendo
 * voraz en lo esencial y aun asi produce una reconstruccion distinta cada vez, que es lo que
 * hace de el el operador de reconstruccion mas eficaz del conjunto en los resultados que
 * reportan sus autores.</p>
 *
 * <p>La probabilidad se sortea en cada invocacion dentro del rango del apartado 7.4.</p>
 */
public final class InsercionVorazConParpadeo implements OperadorReconstruccion {

    private final MotorInsercion motor;
    private final double parpadeoMinimo;
    private final double parpadeoMaximo;

    public InsercionVorazConParpadeo(MotorInsercion motor, ParametrosAlns parametros) {
        this.motor = motor;
        this.parpadeoMinimo = parametros.parpadeoMinimo();
        this.parpadeoMaximo = parametros.parpadeoMaximo();
    }

    @Override
    public String nombre() {
        return "insercion-voraz-con-parpadeo";
    }

    @Override
    public void reconstruir(EstadoAlns estado, Aleatorio aleatorio, PresupuestoComputo presupuesto) {
        double parpadeo = parpadeoMinimo >= parpadeoMaximo
                ? parpadeoMinimo
                : aleatorio.siguienteDouble(parpadeoMinimo, parpadeoMaximo);
        motor.reconstruir(estado, aleatorio, presupuesto, 1, parpadeo);
    }
}
