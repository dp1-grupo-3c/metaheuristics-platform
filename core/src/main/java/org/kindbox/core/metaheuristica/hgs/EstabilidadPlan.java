package org.kindbox.core.metaheuristica.hgs;

import java.util.Arrays;
import java.util.Map;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Termino blando de estabilidad del plan entre replanificaciones, del apartado 11.4 del ISA,
 * resuelto en tiempo constante por tarea.
 *
 * <p>El apartado 11.4 mide la distancia entre el plan nuevo y el vigente como el <b>numero de
 * pedidos que cambian de unidad asignada</b>, y pide penalizarla con un peso muy inferior al
 * de la infactibilidad, de modo que la estabilidad nunca prevalezca sobre el cumplimiento del
 * plazo. {@code FuncionObjetivoJerarquica} ya calcula ese numero sobre el plan terminado; esta
 * clase es lo que hace falta para que el termino <b>guie la busqueda</b> y no solo aparezca en
 * la evaluacion final.</p>
 *
 * <h2>Por que un arreglo primitivo y no el mapa de la instancia</h2>
 * <p>{@code InstanciaPlanificacion.asignacionVigente()} devuelve un mapa de identificador de
 * pedido a codigo TTNN. Consultarlo dentro de la busqueda local costaria un sondeo de tabla
 * asociativa y una comparacion de cadenas por movimiento evaluado, en el bucle mas caliente de
 * un algoritmo que dispone de entre 2 y 18 segundos. Por eso el mapa se resuelve <b>una sola
 * vez al arrancar la corrida</b> a un arreglo primitivo indexado por indice local de tarea con
 * el indice local de la unidad que tenia el pedido, o {@link #SIN_UNIDAD}. A partir de ahi
 * consultar la estabilidad de una tarea es un acceso a un {@code int[]}.</p>
 *
 * <h2>La desviacion se mide por pedido, no por tarea</h2>
 * <p>Un pedido cuya cantidad supera la capacidad de la unidad mas grande se descompone en
 * varias tareas ({@link TareasEntrega}), pero el apartado 11.4 cuenta <b>pedidos</b>. Para que
 * el termino siga siendo aditivo por ruta, que es lo que permite valorar un movimiento de la
 * busqueda local sin recorrer la solucion entera, la carga la lleva una sola tarea del pedido:
 * la primera. Las demas conocen su unidad vigente, y por eso sirven para sesgar la eleccion de
 * unidad, pero no vuelven a pagar.</p>
 *
 * <h2>Que no se penaliza</h2>
 * <p>Un pedido cuya unidad vigente ya no figura en la fotografia, por averia, mantenimiento o
 * trasvase, no aporta nada: su reasignacion es forzada y penalizarla solo anadiria un termino
 * constante que ninguna solucion puede evitar. Tampoco se penaliza el pedido que queda en el
 * banco, porque dejarlo sin atender ya cuesta
 * {@link ParametrosHgs#PENALIZACION_TAREA_NO_ATENDIDA}, seis ordenes de magnitud por encima de
 * este peso.</p>
 *
 * <p>El objeto es inmutable y de solo lectura, de modo que el Split y la educacion lo comparten
 * sin copiarlo.</p>
 */
public final class EstabilidadPlan {

    /** Marca de tarea sin unidad asignada en el plan vigente. */
    public static final int SIN_UNIDAD = -1;

    private final double peso;
    private final int[] unidadVigente;
    private final int[] tipoVigente;
    private final boolean[] cuentaDesviacion;
    private final int pedidosConAsignacion;

    /**
     * @param instancia fotografia del problema, de la que se lee la asignacion vigente
     * @param tareas    descomposicion en tareas de entrega de esta misma fotografia
     * @param peso      costo en soles de cada pedido que cambia de unidad; con cero el termino
     *                  queda desactivado y la clase no hace trabajo alguno
     */
    public EstabilidadPlan(InstanciaPlanificacion instancia, TareasEntrega tareas, double peso) {
        this.peso = peso;
        final int cantidad = Math.max(1, tareas.cantidad());
        this.unidadVigente = new int[cantidad];
        this.tipoVigente = new int[cantidad];
        this.cuentaDesviacion = new boolean[cantidad];
        Arrays.fill(unidadVigente, SIN_UNIDAD);
        Arrays.fill(tipoVigente, SIN_UNIDAD);

        int conAsignacion = 0;
        if (peso > 0.0 && tareas.cantidad() > 0) {
            for (Map.Entry<Integer, String> asignacion : instancia.asignacionVigente().entrySet()) {
                int pedido = instancia.indiceDePedido(asignacion.getKey());
                if (pedido < 0) {
                    // El pedido ya no esta pendiente en esta fotografia.
                    continue;
                }
                int unidad = instancia.indiceDeUnidad(asignacion.getValue());
                if (unidad < 0) {
                    // La unidad desaparecio de la fotografia: la reasignacion es forzada.
                    continue;
                }
                final int primera = tareas.primeraDePedido(pedido);
                final int visitas = tareas.tareasDePedido(pedido);
                for (int k = 0; k < visitas; k++) {
                    unidadVigente[primera + k] = unidad;
                    tipoVigente[primera + k] = instancia.unidadTipo(unidad).ordinal();
                }
                cuentaDesviacion[primera] = true;
                conAsignacion++;
            }
        }
        this.pedidosConAsignacion = conAsignacion;
    }

    /** Costo en soles de cada pedido que cambia de unidad respecto del plan vigente. */
    public double peso() {
        return peso;
    }

    /**
     * Indica si hay algo que estabilizar. En la primera replanificacion de un escenario, y
     * siempre que el peso sea cero, es {@code false} y todas las consultas devuelven cero sin
     * recorrer nada.
     */
    public boolean activa() {
        return pedidosConAsignacion > 0;
    }

    /** Pedidos de esta fotografia que traen una asignacion vigente utilizable. */
    public int pedidosConAsignacion() {
        return pedidosConAsignacion;
    }

    /** Indice local de la unidad que tenia la tarea en el plan vigente, o {@link #SIN_UNIDAD}. */
    public int unidadVigente(int tarea) {
        return unidadVigente[tarea];
    }

    /** Ordinal del tipo de la unidad vigente de la tarea, o {@link #SIN_UNIDAD}. */
    public int tipoVigente(int tarea) {
        return tipoVigente[tarea];
    }

    /** Numero de pedidos del tramo que cambiarian de unidad si lo atendiese {@code unidad}. */
    public int desviacion(int unidad, int[] secuencia, int desde, int longitud) {
        if (pedidosConAsignacion == 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < longitud; i++) {
            final int tarea = secuencia[desde + i];
            if (cuentaDesviacion[tarea] && unidadVigente[tarea] != unidad) {
                total++;
            }
        }
        return total;
    }

    /** Desviacion de una secuencia que arranca en la posicion cero. */
    public int desviacion(int unidad, int[] secuencia, int longitud) {
        return desviacion(unidad, secuencia, 0, longitud);
    }

    /** Penalizacion en soles de la desviacion del tramo. */
    public double penalizacion(int unidad, int[] secuencia, int desde, int longitud) {
        if (pedidosConAsignacion == 0) {
            return 0.0;
        }
        return peso * desviacion(unidad, secuencia, desde, longitud);
    }

    /** Penalizacion en soles de una secuencia que arranca en la posicion cero. */
    public double penalizacion(int unidad, int[] secuencia, int longitud) {
        return penalizacion(unidad, secuencia, 0, longitud);
    }
}
