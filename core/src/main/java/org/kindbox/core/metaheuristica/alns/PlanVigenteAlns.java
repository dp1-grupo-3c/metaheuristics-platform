package org.kindbox.core.metaheuristica.alns;

import java.util.Arrays;
import java.util.Map;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Plan vigente del apartado 11.4 del ISA, resuelto una sola vez al espacio de indices locales
 * de la fotografia.
 *
 * <p>{@link InstanciaPlanificacion#asignacionVigente()} entrega la asignacion en las
 * coordenadas del mundo exterior: identificador global de pedido contra codigo {@code TTNN} de
 * unidad. El termino blando de estabilidad se consulta dentro del bucle mas caliente de la
 * busqueda, de modo que resolver esa tabla asociativa o comparar cadenas alli seria
 * inadmisible con el presupuesto de 2 a 18 segundos del apartado 2.3. Esta clase hace la
 * traduccion completa al arrancar la corrida y deja un unico arreglo primitivo indexado por
 * indice local de pedido, con el indice local de la unidad que lo tenia asignado o {@code -1}
 * si no lo tenia.</p>
 *
 * <p>Se descartan en la traduccion, y quedan por tanto como {@code -1}, dos casos que la
 * busqueda no puede corregir y que aportan por ello una constante a la desviacion:</p>
 * <ul>
 *   <li>el pedido ya no esta pendiente en esta fotografia, y</li>
 *   <li>la unidad que lo atendia ya no esta disponible por averia, mantenimiento o trasvase,
 *       que es el caso que {@code FuncionObjetivoJerarquica.desviacionDelPlanVigente} cuenta
 *       siempre como desviacion.</li>
 * </ul>
 * <p>Una constante no cambia el orden entre dos soluciones candidatas, de modo que excluirla
 * del termino incremental no altera ninguna decision de la busqueda.</p>
 *
 * <p>El recorrido de la tabla asociativa no compromete la reproducibilidad: cada pedido
 * aparece como clave una sola vez, de suerte que el arreglo resultante no depende del orden en
 * que se visiten las entradas.</p>
 *
 * <p>La clase es inmutable una vez construida y los tres estados de una corrida comparten una
 * sola instancia.</p>
 */
public final class PlanVigenteAlns {

    /** Indice local de la unidad que el plan vigente asignaba a cada pedido, o {@code -1}. */
    private final int[] unidadDePedido;
    private final int pedidosConUnidad;

    /**
     * Resuelve el plan vigente de la fotografia.
     *
     * @param instancia fotografia del problema, que trae la asignacion del plan anterior
     */
    public PlanVigenteAlns(InstanciaPlanificacion instancia) {
        this.unidadDePedido = new int[Math.max(1, instancia.cantidadPedidos())];
        Arrays.fill(unidadDePedido, -1);
        int conUnidad = 0;
        for (Map.Entry<Integer, String> asignacion : instancia.asignacionVigente().entrySet()) {
            int pedido = instancia.indiceDePedido(asignacion.getKey());
            if (pedido < 0) {
                continue;
            }
            int unidad = instancia.indiceDeUnidad(asignacion.getValue());
            if (unidad < 0) {
                continue;
            }
            if (unidadDePedido[pedido] < 0) {
                conUnidad++;
            }
            unidadDePedido[pedido] = unidad;
        }
        this.pedidosConUnidad = conUnidad;
    }

    private PlanVigenteAlns(int cantidadPedidos) {
        this.unidadDePedido = new int[Math.max(1, cantidadPedidos)];
        Arrays.fill(unidadDePedido, -1);
        this.pedidosConUnidad = 0;
    }

    /** Plan vacio, que es el de la primera planificacion de una corrida. */
    public static PlanVigenteAlns ninguno(int cantidadPedidos) {
        return new PlanVigenteAlns(cantidadPedidos);
    }

    /** Indice local de la unidad que el plan vigente asignaba al pedido, o {@code -1}. */
    public int unidadDe(int pedido) {
        return unidadDePedido[pedido];
    }

    /**
     * Pedidos de la fotografia con unidad vigente resoluble. Es la cota superior de la
     * desviacion y el valor con que arranca el contador de un estado vacio.
     */
    public int pedidosConUnidad() {
        return pedidosConUnidad;
    }

    /** Indica si no hay plan anterior que respetar, en cuyo caso el termino no interviene. */
    public boolean vacio() {
        return pedidosConUnidad == 0;
    }

    /**
     * Arreglo interno, para que el estado lo lea en el bucle caliente sin indireccion. No debe
     * modificarse: lo comparten los tres estados de la corrida.
     */
    int[] unidades() {
        return unidadDePedido;
    }

    @Override
    public String toString() {
        return "PlanVigenteAlns[pedidosConUnidad=" + pedidosConUnidad
                + " de " + unidadDePedido.length + "]";
    }
}
