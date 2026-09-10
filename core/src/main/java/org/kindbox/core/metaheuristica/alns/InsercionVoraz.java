package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.util.Aleatorio;

/**
 * Insercion voraz del apartado 7.3.2 del ISA: coloca cada pedido del banco en la posicion
 * factible de menor costo incremental.
 *
 * <p>En cada paso se elige, entre todos los pedidos que quedan por colocar, el que admite la
 * insercion mas barata, y se coloca ahi. Es el operador de reconstruccion de referencia:
 * rapido, determinista salvo por el orden de los empates y de buena calidad local. Su
 * debilidad conocida es que deja para el final los pedidos dificiles, que ya no encuentran
 * sitio; los otros dos operadores del conjunto atacan justamente ese punto.</p>
 */
public final class InsercionVoraz implements OperadorReconstruccion {

    private final MotorInsercion motor;

    public InsercionVoraz(MotorInsercion motor) {
        this.motor = motor;
    }

    @Override
    public String nombre() {
        return "insercion-voraz";
    }

    @Override
    public void reconstruir(EstadoAlns estado, Aleatorio aleatorio, PresupuestoComputo presupuesto) {
        motor.reconstruir(estado, aleatorio, presupuesto, 1, 0.0);
    }
}
