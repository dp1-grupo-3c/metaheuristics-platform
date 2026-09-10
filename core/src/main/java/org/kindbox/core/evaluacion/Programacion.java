package org.kindbox.core.evaluacion;

import org.kindbox.core.problema.Ruta;

/**
 * Resultado de programar una secuencia de visitas sobre una unidad concreta, tal como lo
 * devuelve {@link ProgramadorRuta}.
 *
 * <p>Es la moneda de cambio entre el decodificador y los dos algoritmos: el procedimiento
 * Split de la busqueda genetica hibrida corta el cromosoma en rutas comparando estas
 * programaciones, y los operadores de insercion de la busqueda adaptativa de vecindad
 * amplia eligen la posicion de insercion por el mismo criterio. Que ambos lean el mismo
 * registro es lo que sostiene la verificacion de validez del apartado 12.4 del ISA, que
 * exige identico valor objetivo ante la misma solucion.</p>
 *
 * <p>El campo {@code desfaseMinutos} se devuelve siempre, tambien cuando la programacion
 * resulta infactible. Suma dos violaciones temporales de la misma naturaleza: los minutos
 * en que se incumplen los instantes limite de los pedidos y los minutos en que la ruta se
 * pasa del cierre del turno de la unidad. Es exactamente el termino que HGS penaliza en su
 * subpoblacion infactible (apartado 6.3.1): una ruta que llega diez minutos tarde es un
 * punto de partida util, mientras que descartarla sin mas privaria a la busqueda de la
 * informacion de cuanto le falta para ser factible. Ambas violaciones van en el mismo
 * termino porque, si el exceso de turno quedase fuera, una ruta que se pasa saldria
 * infactible con desfase cero y la penalizacion no distinguiria pasarse un minuto de
 * pasarse tres horas. Por eso, salvo que la secuencia no admita programacion alguna, se
 * devuelve la mejor ruta hallada junto con su desfase.</p>
 *
 * @param factible          indica si la ruta cumple plazos y cierre de turno
 * @param desfaseMinutos    minutos totales de violacion temporal: incumplimiento de
 *                          instantes limite de pedido mas exceso sobre el cierre del turno
 * @param kilometros        kilometros recorridos, incluidos los desvios de abastecimiento
 * @param costo             costo de operacion en soles, kilometros por el costo del tipo
 * @param atendidos         numero de visitas de entrega programadas
 * @param unidadesEntregadas unidades del producto P entregadas por la ruta
 * @param minutoFin         instante en que la unidad termina la ruta
 * @param ruta              ruta concreta con todas sus paradas, o {@code null} si no hubo programacion
 */
public record Programacion(
        boolean factible,
        int desfaseMinutos,
        int kilometros,
        double costo,
        int atendidos,
        int unidadesEntregadas,
        long minutoFin,
        Ruta ruta) {

    /**
     * Desfase con el que se marca una secuencia que no admite programacion alguna. No es
     * {@code Integer.MAX_VALUE} para que las penalizaciones que lo multiplican no
     * desborden.
     */
    public static final int DESFASE_INFINITO = Integer.MAX_VALUE / 4;

    /**
     * Caso sin programacion posible: la secuencia no puede recorrerse con esta unidad, sea
     * porque una visita supera su capacidad, porque ningun almacen alcanzable tiene
     * inventario suficiente o porque un destino es inalcanzable con los bloqueos vigentes.
     * No lleva ruta asociada, de modo que el consumidor debe descartar la secuencia entera
     * en lugar de intentar repararla.
     */
    public static final Programacion INFACTIBLE = new Programacion(
            false, DESFASE_INFINITO, 0, 0.0, 0, 0, Long.MAX_VALUE / 4, null);

    /** Indica si no se pudo construir ninguna ruta para la secuencia. */
    public boolean sinProgramacion() {
        return ruta == null;
    }

    /** Indica si la ruta respeta todos los instantes limite de sus pedidos. */
    public boolean sinDesfase() {
        return desfaseMinutos == 0;
    }

    /**
     * Costo mas la penalizacion del desfase, que es el valor con el que HGS compara rutas
     * de su subpoblacion infactible. El peso lo fija el algoritmo, no el decodificador,
     * porque HGS lo adapta durante la corrida segun la proporcion de descendientes
     * factibles (apartado 6.3.1 del ISA).
     */
    public double costoPenalizado(double pesoPorMinutoDeDesfase) {
        return costo + pesoPorMinutoDeDesfase * desfaseMinutos;
    }

    @Override
    public String toString() {
        if (sinProgramacion()) {
            return "Programacion[sin programacion posible]";
        }
        return String.format("Programacion[%s km=%d costo=%.2f entregas=%d desfase=%d fin=%d]",
                factible ? "factible" : "infactible", kilometros, costo, atendidos, desfaseMinutos, minutoFin);
    }
}
