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
 * <p>{@code urgencia} es un nivel intermedio que solo desempata soluciones con la misma
 * {@code H}: la suma, sobre los pedidos no atendidos, de {@code 1 / (holgura + 1)}, con la
 * holgura en minutos desde la fotografia. A igual numero de pedidos sin atender, se prefiere
 * dejar fuera los que todavia pueden esperar a una replanificacion posterior.</p>
 *
 * @param pedidosNoAtendidos numero de pedidos sin asignacion factible dentro de plazo
 * @param urgencia           suma de 1/(holgura+1) de los pedidos no atendidos
 * @param costo              costo de operacion en soles
 * @param penalizacion       suma de terminos blandos, en unidades de costo
 */
public record ValorObjetivo(int pedidosNoAtendidos, double urgencia, double costo, double penalizacion)
        implements Comparable<ValorObjetivo> {

    /** Diferencia de urgencia por debajo de la cual dos valores se consideran iguales. */
    public static final double TOLERANCIA_URGENCIA = 1e-9;

    /** Valor sin nivel de urgencia, el de las soluciones que no lo calculan. */
    public ValorObjetivo(int pedidosNoAtendidos, double costo, double penalizacion) {
        this(pedidosNoAtendidos, 0.0, costo, penalizacion);
    }

    /** Urgencia de un pedido no atendido con la holgura dada, en minutos. */
    public static double urgenciaDe(long holgura) {
        return 1.0 / (Math.max(0L, holgura) + 1.0);
    }

    /** Valor peor posible, util como elemento neutro de una minimizacion. */
    public static final ValorObjetivo PEOR =
            new ValorObjetivo(Integer.MAX_VALUE, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY);

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
     * Comparacion lexicografica: primero H, luego la urgencia de lo no atendido y por ultimo
     * el costo penalizado.
     * Devuelve un valor negativo si este objetivo es mejor.
     */
    @Override
    public int compareTo(ValorObjetivo otro) {
        int porH = Integer.compare(pedidosNoAtendidos, otro.pedidosNoAtendidos);
        if (porH != 0) {
            return porH;
        }
        if (Math.abs(urgencia - otro.urgencia) > TOLERANCIA_URGENCIA) {
            return Double.compare(urgencia, otro.urgencia);
        }
        return Double.compare(costoPenalizado(), otro.costoPenalizado());
    }

    /** Indica si este valor es estrictamente mejor que el otro. */
    public boolean mejorQue(ValorObjetivo otro) {
        return compareTo(otro) < 0;
    }

    @Override
    public String toString() {
        return String.format("H=%d U=%.4f S=%.2f pen=%.2f", pedidosNoAtendidos, urgencia, costo, penalizacion);
    }
}
