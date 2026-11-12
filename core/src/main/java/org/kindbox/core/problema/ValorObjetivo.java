package org.kindbox.core.problema;

/**
 * Valor de la funcion objetivo jerarquica del apartado 2.5 del ISA.
 *
 * <p>El nivel 1, dominante, minimiza {@code H}, el numero de pedidos sin asignacion
 * factible dentro de plazo. A igual H se minimiza la urgencia y despues {@code costo}, la suma
 * sobre las unidades de la distancia recorrida por el costo por kilometro de su tipo.
 * Una solucion con {@code H} mayor que cero nunca puede preferirse a una con {@code H}
 * igual a cero, con independencia de su costo.</p>
 *
 * <p>{@code penalizacion} recoge los terminos blandos: la desviacion respecto del plan
 * vigente del apartado 11.4 y, en la subpoblacion infactible de HGS, las violaciones de
 * restricciones relajadas. Desempata junto al costo despues de H y urgencia.</p>
 *
 * <p>{@code urgencia} es un nivel intermedio que solo desempata soluciones con la misma
 * {@code H}: la suma, sobre los pedidos no atendidos, de {@code 1 / (holgura + 1)}, con la
 * holgura en minutos desde la fotografia. A igual numero de pedidos sin atender, se prefiere
 * dejar fuera los que todavia pueden esperar a una replanificacion posterior.</p>
 *
 * @param pedidosNoAtendidos numero de pedidos sin asignacion factible dentro de plazo
 * @param urgencia           suma de 1/(holgura+1) de los pedidos no atendidos
 * @param costo              costo de operacion en soles
 * <p>{@code pedidosSinMargen} es el nivel entre la urgencia y el costo: cuantos pedidos quedan
 * asignados con un margen al plazo menor que una fraccion de su plazo total (ver
 * {@code FuncionObjetivoJerarquica}). A igual H y urgencia se prefiere el plan que no deja
 * pedidos al filo de su plazo aunque cueste mas: sin este nivel la busqueda posterga un pedido
 * hasta el ultimo minuto que lo hace factible y cualquier contratiempo lo vuelve inatendible.</p>
 *
 * @param penalizacion       suma de terminos blandos, en unidades de costo
 * @param pedidosSinMargen   pedidos asignados que llegan con menos margen que el exigido
 */
public record ValorObjetivo(int pedidosNoAtendidos, double urgencia, double costo, double penalizacion,
                            int pedidosSinMargen)
        implements Comparable<ValorObjetivo> {

    /** Resolucion de urgencia; la comparacion por celdas mantiene un orden transitivo. */
    public static final double TOLERANCIA_URGENCIA = 1e-9;

    /** Valor sin nivel de margen, el de las soluciones que no lo calculan. */
    public ValorObjetivo(int pedidosNoAtendidos, double urgencia, double costo, double penalizacion) {
        this(pedidosNoAtendidos, urgencia, costo, penalizacion, 0);
    }

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
                    Double.POSITIVE_INFINITY, Integer.MAX_VALUE);

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
     * Comparacion lexicografica: primero H, luego la urgencia de lo no atendido, luego los
     * pedidos sin margen y por ultimo el costo penalizado.
     * Devuelve un valor negativo si este objetivo es mejor.
     */
    @Override
    public int compareTo(ValorObjetivo otro) {
        int porH = Integer.compare(pedidosNoAtendidos, otro.pedidosNoAtendidos);
        if (porH != 0) {
            return porH;
        }
        int porUrgencia = Long.compare(Math.round(urgencia / TOLERANCIA_URGENCIA),
                Math.round(otro.urgencia / TOLERANCIA_URGENCIA));
        if (porUrgencia != 0) return porUrgencia;
        int porMargen = Integer.compare(pedidosSinMargen, otro.pedidosSinMargen);
        if (porMargen != 0) return porMargen;
        return Double.compare(costoPenalizado(), otro.costoPenalizado());
    }

    /** Indica si este valor es estrictamente mejor que el otro. */
    public boolean mejorQue(ValorObjetivo otro) {
        return compareTo(otro) < 0;
    }

    @Override
    public String toString() {
        return String.format("H=%d U=%.4f M=%d S=%.2f pen=%.2f", pedidosNoAtendidos, urgencia, pedidosSinMargen,
                costo, penalizacion);
    }
}
