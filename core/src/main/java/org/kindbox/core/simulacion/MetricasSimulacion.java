package org.kindbox.core.simulacion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indicadores de desempeno acumulados de una corrida.
 *
 * <p>Cubre el requisito (b) de los escenarios del enunciado, que pide presentar de manera
 * grafica informacion relevante del desempeno de las operaciones, y reproduce los bloques
 * que el prototipo del visualizador muestra en el panel de metricas y en el modal de fin de
 * simulacion: tiempo de entrega promedio por prioridad con su plazo de referencia, costo
 * acumulado, kilometros por tipo de vehiculo, total de pedidos entregados y registro
 * cronologico de activaciones del semaforo de almacenes.</p>
 *
 * <p><b>Reparto de los pedidos.</b> La particion de {@code pedidosRegistrados} en conjuntos
 * disjuntos es {@code entregados + pendientes + incumplidos}. El campo
 * {@code pedidosConEntregaParcial} <b>no</b> forma parte de esa particion: es un
 * subconjunto de los pendientes y de los incumplidos, de modo que sumarlo a los otros tres
 * cuenta de mas. Se expone aparte porque el operador necesita saber cuantos pedidos van a
 * medias, no porque sea una categoria propia. Use {@link #particionCoherente()} para
 * comprobarlo.</p>
 *
 * @param pedidosRegistrados        pedidos que han llegado hasta el instante
 * @param pedidosEntregados         pedidos entregados por completo
 * @param pedidosPendientes         pedidos aun sin completar y todavia dentro de plazo
 * @param pedidosConEntregaParcial  pedidos con alguna entrega y sin completar. Es un
 *                                  SUBCONJUNTO de pendientes e incumplidos, no una
 *                                  categoria disjunta
 * @param unidadesEntregadas        paquetes del producto P entregados
 * @param pedidosIncumplidos        pedidos cuyo plazo vencio sin completarse
 * @param costoAcumulado            costo de operacion en soles
 * @param kilometrosPorTipo         kilometros recorridos por tipo de unidad, con el nombre del
 *                                  enumerado como clave: AUTO, MOTO y BICICLETA
 * @param minutosEntregaPorPlazo    tiempo de entrega promedio en minutos, por plazo comprometido
 * @param entregasPorPlazo          numero de entregas computadas en cada plazo
 * @param activacionesSemaforo      registro cronologico de cambios a ambar o rojo
 * @param averiasPorTipo            averias ocurridas, por tipo
 * @param ejecucionesPlanificador   veces que se ejecuto el planificador
 * @param milisegundosPlanificador  reloj de pared total consumido por el planificador
 */
public record MetricasSimulacion(
        int pedidosRegistrados,
        int pedidosEntregados,
        int pedidosPendientes,
        int pedidosConEntregaParcial,
        int unidadesEntregadas,
        int pedidosIncumplidos,
        double costoAcumulado,
        Map<String, Integer> kilometrosPorTipo,
        Map<Integer, Double> minutosEntregaPorPlazo,
        Map<Integer, Integer> entregasPorPlazo,
        List<ActivacionSemaforo> activacionesSemaforo,
        Map<Integer, Integer> averiasPorTipo,
        long ejecucionesPlanificador,
        long milisegundosPlanificador) {

    /**
     * Entrada del registro cronologico de semaforos que muestra el modal de fin de
     * simulacion del prototipo.
     *
     * @param minuto        instante simulado
     * @param nombreAlmacen almacen implicado
     * @param color         color al que paso
     * @param disponible    inventario en ese instante
     */
    public record ActivacionSemaforo(long minuto, String nombreAlmacen, ColorSemaforo color, int disponible) {
    }

    /**
     * Copia defensiva que conserva el orden de recorrido del mapa recibido.
     *
     * <p>{@code Map.copyOf} no vale aqui: su orden de iteracion depende de una semilla que la
     * maquina virtual sortea en cada arranque, de modo que el JSON del visualizador cambiaba
     * de orden entre dos ejecuciones del servicio con los mismos numeros. Quien construye
     * estas metricas las entrega ya ordenadas, por tipo de unidad en el orden del enumerado y
     * por plazo y tipo de averia en orden creciente, y ese es el orden que llega al cliente.</p>
     */
    public MetricasSimulacion {
        kilometrosPorTipo = mapaEstable(kilometrosPorTipo);
        minutosEntregaPorPlazo = mapaEstable(minutosEntregaPorPlazo);
        entregasPorPlazo = mapaEstable(entregasPorPlazo);
        activacionesSemaforo = List.copyOf(activacionesSemaforo);
        averiasPorTipo = mapaEstable(averiasPorTipo);
    }

    private static <C, V> Map<C, V> mapaEstable(Map<C, V> mapa) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mapa));
    }

    /**
     * Comprueba que el reparto de pedidos sea coherente, es decir que entregados, pendientes
     * e incumplidos sumen los registrados. Los parciales quedan fuera de la suma a proposito,
     * por ser un subconjunto. Es una invariante del motor y una buena asercion de prueba.
     */
    public boolean particionCoherente() {
        return pedidosEntregados + pedidosPendientes + pedidosIncumplidos == pedidosRegistrados;
    }

    /** Kilometros totales recorridos por la flota. */
    public int kilometrosTotales() {
        int total = 0;
        for (int km : kilometrosPorTipo.values()) {
            total += km;
        }
        return total;
    }

    /** Milisegundos promedio por ejecucion del planificador. */
    public double milisegundosPorEjecucion() {
        return ejecucionesPlanificador == 0 ? 0.0
                : (double) milisegundosPlanificador / ejecucionesPlanificador;
    }
}
