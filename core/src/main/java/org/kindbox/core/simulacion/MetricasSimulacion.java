package org.kindbox.core.simulacion;

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
 * @param pedidosRegistrados        pedidos que han llegado hasta el instante
 * @param pedidosEntregados         pedidos entregados por completo
 * @param pedidosParcialmenteEntregados pedidos con alguna entrega pero no completos
 * @param unidadesEntregadas        paquetes del producto P entregados
 * @param pedidosIncumplidos        pedidos cuyo plazo vencio sin completarse
 * @param costoAcumulado            costo de operacion en soles
 * @param kilometrosPorTipo         kilometros recorridos por tipo de unidad
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
        int pedidosParcialmenteEntregados,
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

    public MetricasSimulacion {
        kilometrosPorTipo = Map.copyOf(kilometrosPorTipo);
        minutosEntregaPorPlazo = Map.copyOf(minutosEntregaPorPlazo);
        entregasPorPlazo = Map.copyOf(entregasPorPlazo);
        activacionesSemaforo = List.copyOf(activacionesSemaforo);
        averiasPorTipo = Map.copyOf(averiasPorTipo);
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
