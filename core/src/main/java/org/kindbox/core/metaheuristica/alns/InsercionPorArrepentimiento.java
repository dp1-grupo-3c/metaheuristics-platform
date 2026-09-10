package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.util.Aleatorio;

/**
 * Insercion por arrepentimiento de orden k del apartado 7.3.2 del ISA: prioriza los pedidos
 * cuya diferencia entre la mejor posicion y la k-esima mejor es mayor.
 *
 * <p>El arrepentimiento de un pedido mide lo que costaria aplazarlo: si su mejor ruta es
 * mucho mejor que las siguientes, dejarlo para despues sale caro, de modo que conviene
 * colocarlo primero. Es el remedio clasico de Ropke y Pisinger (2006) a la miopia de la
 * insercion voraz, y en PaqRap tiene un efecto adicional sobre el nivel 1 del objetivo: un
 * pedido que solo cabe en una o dos unidades recibe un arrepentimiento dominante y se coloca
 * antes de que esas unidades se llenen, con lo que el banco queda mas pequeno.</p>
 *
 * <p>El orden k se sortea en cada invocacion dentro del rango del apartado 7.4, de modo que
 * la capa adaptativa mide el operador y no una eleccion fija de k.</p>
 */
public final class InsercionPorArrepentimiento implements OperadorReconstruccion {

    private final MotorInsercion motor;
    private final int ordenMinimo;
    private final int ordenMaximo;

    public InsercionPorArrepentimiento(MotorInsercion motor, ParametrosAlns parametros) {
        this.motor = motor;
        this.ordenMinimo = parametros.ordenArrepentimientoMinimo();
        this.ordenMaximo = parametros.ordenArrepentimientoMaximo();
    }

    @Override
    public String nombre() {
        return "insercion-por-arrepentimiento";
    }

    @Override
    public void reconstruir(EstadoAlns estado, Aleatorio aleatorio, PresupuestoComputo presupuesto) {
        int orden = ordenMinimo == ordenMaximo
                ? ordenMinimo
                : aleatorio.siguienteEntero(ordenMinimo, ordenMaximo);
        motor.reconstruir(estado, aleatorio, presupuesto, orden, 0.0);
    }
}
