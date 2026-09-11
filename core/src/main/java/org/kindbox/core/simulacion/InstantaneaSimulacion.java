package org.kindbox.core.simulacion;

import java.time.LocalDateTime;
import java.util.List;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoUnidad;

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

    /**
     * Unidades activas por tipo, que es el conteo de la barra superior del prototipo.
     *
     * <p>Activa quiere decir en operacion. Quedan fuera las tres situaciones en que la unidad
     * no esta trabajando: la que espera en un almacen sin ruta asignada, la inmovilizada por
     * una averia y la que esta en mantenimiento preventivo, que el enunciado retira de la
     * flota durante todo el dia programado.</p>
     */
    public int unidadesActivasDeTipo(TipoUnidad tipo) {
        int n = 0;
        for (VistaUnidad u : unidades) {
            if (u.tipo() == tipo && activa(u)) {
                n++;
            }
        }
        return n;
    }

    /** Indica si la unidad esta en operacion y por tanto cuenta como activa. */
    private static boolean activa(VistaUnidad u) {
        EstadoUnidad estado = u.estado();
        return estado != EstadoUnidad.DISPONIBLE && estado != EstadoUnidad.AVERIADA
                && estado != EstadoUnidad.EN_MANTENIMIENTO && !u.averiada();
    }
}
