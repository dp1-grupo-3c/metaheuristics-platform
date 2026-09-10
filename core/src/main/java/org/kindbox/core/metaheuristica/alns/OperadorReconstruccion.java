package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.util.Aleatorio;

/**
 * Operador de reconstruccion de la busqueda adaptativa de vecindad amplia, apartado 7.3.2
 * del ISA.
 *
 * <p>Saca pedidos del banco y los coloca en las rutas. <b>Aqui es donde se verifica la
 * factibilidad de las siete restricciones duras del apartado 2.6</b>: una posicion candidata
 * solo se acepta cuando {@code ProgramadorRuta} declara factible la ruta resultante, de modo
 * que ninguna solucion infactible en sentido duro llega nunca a evaluarse. La unica
 * infactibilidad que el metodo admite es la del banco, que es la que mide el nivel 1 del
 * objetivo.</p>
 *
 * <p>Las posiciones candidatas se valoran con el modo del decodificador que no consume
 * inventario, porque tantear un movimiento no debe gastar un recurso compartido por todas
 * las rutas del plan.</p>
 *
 * <p>Un operador puede dejar pedidos en el banco: son los que no admiten ninguna posicion
 * factible en ninguna unidad.</p>
 */
public interface OperadorReconstruccion {

    /** Nombre del operador, tal como aparece en los reportes de la experimentacion. */
    String nombre();

    /**
     * Coloca en las rutas cuantos pedidos del banco admitan posicion factible.
     *
     * @param estado      representacion de trabajo, que se modifica en el lugar
     * @param aleatorio   generador de la corrida
     * @param presupuesto control de tiempo; el operador lo consulta y sale de inmediato si
     *                    se agota, dejando el estado coherente aunque incompleto
     */
    void reconstruir(EstadoAlns estado, Aleatorio aleatorio, PresupuestoComputo presupuesto);
}
