package org.kindbox.core.problema;

/**
 * Valor de la funcion objetivo jerarquica del apartado 2.5 del ISA.
 *
 * <p>El nivel 1, dominante, minimiza {@code H}, el numero de pedidos sin asignacion
 * factible dentro de plazo. El nivel 2, subordinado, minimiza {@code costo}, la suma
 * sobre las unidades de la distancia recorrida por el costo por kilometro de su tipo.
 * Una solucion con {@code H} mayor que cero nunca puede preferirse a una con {@code H}
 * igual a cero, con independencia de su costo.</p>
 *
 * <p>{@code penalizacion} recoge los terminos blandos: la desviacion respecto del plan
 * vigente del apartado 11.4 y, en la subpoblacion infactible de HGS, las violaciones de
 * restricciones relajadas. Nunca participa en la comparacion jerarquica de dos soluciones
 * factibles, solo en la aptitud interna de cada algoritmo.</p>
 *
 * @param pedidosNoAtendidos numero de pedidos sin asignacion factible dentro de plazo
 * @param costo              costo de operacion en soles
 * @param penalizacion       suma de terminos blandos, en unidades de costo
 */
public record ValorObjetivo(int pedidosNoAtendidos, double costo, double penalizacion)
        implements Comparable<ValorObjetivo> {

    /** Valor peor posible, util como elemento neutro de una minimizacion. */
    public static final ValorObjetivo PEOR =
            new ValorObjetivo(Integer.MAX_VALUE, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    /** Valor sin pedidos pendientes ni costo. */
    public static ValorObjetivo cero() {
        return new ValorObjetivo(0, 0.0, 0.0);
    }

    /** Alias de {@code pedidosNoAtendidos}, que el ISA denomina H. */
    public int h() {
        return pedidosNoAtendidos;
    }

    /** Alias de {@code costo}, que el ISA denomina S. */
    public double s() {
        return costo;
    }

    /** Indica si la solucion cumple el nivel 1 del objetivo. */
    public boolean sinPedidosPendientes() {
        return pedidosNoAtendidos == 0;
    }

    /** Costo mas penalizaciones blandas. Es el valor que guia la busqueda dentro de cada algoritmo. */
    public double costoPenalizado() {
        return costo + penalizacion;
    }

    /**
     * Comparacion lexicografica: primero H, luego el costo penalizado.
     * Devuelve un valor negativo si este objetivo es mejor.
     */
    @Override
    public int compareTo(ValorObjetivo otro) {
        int porH = Integer.compare(pedidosNoAtendidos, otro.pedidosNoAtendidos);
        if (porH != 0) {
            return porH;
        }
        return Double.compare(costoPenalizado(), otro.costoPenalizado());
    }

    /** Indica si este valor es estrictamente mejor que el otro. */
    public boolean mejorQue(ValorObjetivo otro) {
        return compareTo(otro) < 0;
    }

    @Override
    public String toString() {
        return String.format("H=%d S=%.2f pen=%.2f", pedidosNoAtendidos, costo, penalizacion);
    }
}
