package org.kindbox.service.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.TipoEscenario;

/**
 * Cabecera de una corrida. Alimenta la barra superior del prototipo durante la simulacion y
 * la lista de {@code GET /api/simulaciones}.
 *
 * <p>La barra superior pide fecha y hora simuladas, tiempo transcurrido, duracion
 * configurada, conteo de vehiculos activos por tipo y total de productos entregados; los
 * cinco campos estan aqui, ya calculados, para que el visualizador no tenga que recorrer la
 * fotografia entera solo para pintar la cabecera.</p>
 *
 * @param id                      identificador de la corrida
 * @param tipo                    escenario en ejecucion
 * @param algoritmo               algoritmo del planificador
 * @param estado                  situacion de la corrida
 * @param primerDia               primer dia del horizonte
 * @param ultimoDia               ultimo dia del horizonte, incluido
 * @param duracionMinutosReales   duracion objetivo de la corrida en minutos de reloj real
 * @param saltoMinutos            SA, minutos simulados entre dos ejecuciones del planificador
 * @param factorAceleracion       K, minutos simulados por minuto real
 * @param minutosEntreFotografias cadencia de las fotografias
 * @param semilla                 semilla del generador de la corrida
 * @param minutoSimulado          instante simulado alcanzado
 * @param minutosHorizonte        minutos simulados que cubre el escenario completo
 * @param fechaHoraSimulada       instante simulado en el calendario real
 * @param milisegundosReales      reloj de pared transcurrido desde el arranque
 * @param pedidosDelEscenario     pedidos que trae el escenario cargado
 * @param unidadesDeLaFlota       unidades que trae el escenario cargado
 * @param unidadesActivasPorTipo  vehiculos activos por tipo, para el conteo de la barra
 * @param productosEntregados     paquetes del producto P entregados hasta el instante
 * @param motor                   motor de simulacion en uso
 * @param avisos                  archivos o registros que la carga del escenario no pudo leer
 */
public record ResumenCorrida(
        String id,
        TipoEscenario tipo,
        String algoritmo,
        EstadoCorrida estado,
        LocalDate primerDia,
        LocalDate ultimoDia,
        int duracionMinutosReales,
        int saltoMinutos,
        double factorAceleracion,
        int minutosEntreFotografias,
        long semilla,
        long minutoSimulado,
        long minutosHorizonte,
        LocalDateTime fechaHoraSimulada,
        long milisegundosReales,
        int pedidosDelEscenario,
        int unidadesDeLaFlota,
        Map<String, Integer> unidadesActivasPorTipo,
        int productosEntregados,
        String motor,
        List<String> avisos) {

    /**
     * Copia defensiva que conserva el orden del mapa recibido, que es el del enumerado de
     * tipos. Con {@code Map.copyOf} el orden lo decide una semilla que la maquina virtual
     * sortea en cada arranque, y el conteo de la barra superior cambiaba de columnas entre dos
     * ejecuciones del servicio.
     */
    public ResumenCorrida {
        unidadesActivasPorTipo = Collections.unmodifiableMap(new LinkedHashMap<>(unidadesActivasPorTipo));
        avisos = List.copyOf(avisos);
    }

    /** Fraccion del horizonte ya recorrida, acotada a {@code [0,1]}. */
    public double avance() {
        if (minutosHorizonte <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) minutoSimulado / minutosHorizonte));
    }
}
