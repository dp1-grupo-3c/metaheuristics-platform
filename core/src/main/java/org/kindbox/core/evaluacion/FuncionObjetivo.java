package org.kindbox.core.evaluacion;

import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Funcion objetivo jerarquica del apartado 2.5 del ISA, implementada una sola vez y
 * consumida por los dos algoritmos a traves de esta misma interfaz.
 *
 * <p>Que ambos algoritmos produzcan identico valor ante la misma solucion es la segunda
 * verificacion de validez del apartado 12.4: una discrepancia indicaria implementaciones
 * distintas del problema y haria que el experimento comparase modelos en lugar de
 * algoritmos.</p>
 */
public interface FuncionObjetivo {

    /**
     * Peso del nivel 1 del objetivo. Domina cualquier ahorro de costo posible, de modo que
     * una solucion con un pedido menos en el banco siempre se prefiere.
     */
    double PENALIZACION_PEDIDO_NO_ATENDIDO = 1_000_000.0;

    /**
     * Peso del termino blando de estabilidad del apartado 11.4. Se fija muy por debajo del
     * peso de infactibilidad, de modo que la estabilidad nunca prevalezca sobre el
     * cumplimiento del plazo.
     */
    double PESO_ESTABILIDAD = 1.0;

    /** Evalua un plan completo. */
    ValorObjetivo evaluar(InstanciaPlanificacion instancia, Solucion solucion);

    /** Costo de operacion de una ruta aislada, en soles. */
    double costoRuta(Ruta ruta);

    /**
     * Termino de estabilidad del plan nuevo respecto del vigente, medido como numero de
     * pedidos que cambian de unidad asignada.
     */
    int desviacionDelPlanVigente(InstanciaPlanificacion instancia, Solucion solucion);
}
