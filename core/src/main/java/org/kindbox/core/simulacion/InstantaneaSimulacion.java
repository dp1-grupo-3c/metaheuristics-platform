package org.kindbox.core.simulacion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fotografia completa del estado de la simulacion en un instante, que es lo que viaja al
 * componente visualizador.
 *
 * <p>El enunciado pide que al visualizador pueda conectarse cualquier dispositivo en tiempo
 * real, de modo que esta fotografia debe bastarse a si misma: un cliente que se conecta a
 * mitad de una corrida reconstruye la pantalla completa con la primera que recibe, sin
 * necesitar el historial.</p>
 *
 * @param minutoSimulado    instante simulado, en minutos desde el inicio del escenario
 * @param fechaHoraSimulada mismo instante en el calendario real
 * @param estado            situacion de la corrida
 * @param milisegundosReales reloj de pared transcurrido desde el arranque
 * @param unidades          estado de cada unidad de la flota
 * @param almacenes         estado de cada almacen
 * @param bloqueosVigentes  tramos bloqueados en este instante
 * @param pedidosPendientes numero de pedidos aun sin completar
 * @param metricas          indicadores acumulados
 */
public record InstantaneaSimulacion(
        long minutoSimulado,
        LocalDateTime fechaHoraSimulada,
        EstadoCorrida estado,
        long milisegundosReales,
        List<VistaUnidad> unidades,
        List<VistaAlmacen> almacenes,
        List<VistaBloqueo> bloqueosVigentes,
        int pedidosPendientes,
        MetricasSimulacion metricas) {

    public InstantaneaSimulacion {
        unidades = List.copyOf(unidades);
        almacenes = List.copyOf(almacenes);
        bloqueosVigentes = List.copyOf(bloqueosVigentes);
    }

    /** Unidades activas por tipo, que es el conteo de la barra superior del prototipo. */
    public int unidadesActivasDeTipo(org.kindbox.core.modelo.TipoUnidad tipo) {
        int n = 0;
        for (VistaUnidad u : unidades) {
            if (u.tipo() == tipo && u.estado() != org.kindbox.core.modelo.EstadoUnidad.DISPONIBLE
                    && !u.averiada()) {
                n++;
            }
        }
        return n;
    }
}
