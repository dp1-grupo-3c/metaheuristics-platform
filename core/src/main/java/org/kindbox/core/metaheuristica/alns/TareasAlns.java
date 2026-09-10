package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Descomposicion de los pedidos en tareas de entrega, que es el espacio de indices sobre el
 * que trabaja la busqueda adaptativa de vecindad amplia.
 *
 * <p>El decodificador rechaza toda visita cuya cantidad supere la capacidad de la unidad, de
 * modo que un pedido de mas unidades que la mayor capacidad de la flota no cabe en ninguna
 * ruta como visita unica. El enunciado admite entregas parciales: ese pedido se atiende con
 * varias visitas, posiblemente de unidades distintas. Esta clase materializa ese reparto una
 * sola vez, al arrancar la corrida, y expone las visitas resultantes como <b>tareas</b>. Los
 * operadores de destruccion y reconstruccion mueven tareas; el banco guarda tareas; el
 * decodificador sigue recibiendo indices de pedido y cantidades por visita.</p>
 *
 * <p>El corte es el mismo que emplean la heuristica constructiva y la busqueda genetica
 * hibrida: {@code ceil(cantidad / capacidadMaxima)} partes lo mas parejas posible. El reparto
 * parejo, y no el llenado codicioso, baja mas veces por debajo de la capacidad de moto o
 * bicicleta, que son los tipos baratos. La capacidad de corte se deriva de los tipos que
 * tienen al menos una unidad presente en la fotografia, no de una constante cableada.</p>
 *
 * <p>La inmensa mayoria de los pedidos produce una sola tarea. En ese caso el espacio de
 * indices de tareas coincide con el de pedidos y el comportamiento del algoritmo es
 * exactamente el que tenia antes de existir esta clase.</p>
 *
 * <p>La clase es inmutable una vez construida y puede compartirse entre los operadores de una
 * misma corrida.</p>
 */
public final class TareasAlns {

    private final InstanciaPlanificacion instancia;
    /** Pedido al que pertenece cada tarea. */
    private final int[] pedidoDe;
    /** Unidades del producto P que entrega cada tarea. */
    private final int[] cantidadDe;
    /** Primera tarea de cada pedido. Las tareas de un pedido son consecutivas. */
    private final int[] primeraDe;
    /** Numero de tareas de cada pedido. */
    private final int[] cuantasDe;
    private final int cantidad;
    private final int capacidadCorte;

    /**
     * Construye la descomposicion de una fotografia.
     *
     * @param instancia fotografia estatica del problema
     */
    public TareasAlns(InstanciaPlanificacion instancia) {
        this.instancia = instancia;
        final int pedidos = instancia.cantidadPedidos();
        this.capacidadCorte = capacidadDeCorte(instancia);
        this.primeraDe = new int[pedidos];
        this.cuantasDe = new int[pedidos];
        int total = 0;
        for (int p = 0; p < pedidos; p++) {
            primeraDe[p] = total;
            cuantasDe[p] = partes(instancia.pedidoCantidad(p), capacidadCorte);
            total += cuantasDe[p];
        }
        this.cantidad = total;
        this.pedidoDe = new int[Math.max(1, total)];
        this.cantidadDe = new int[Math.max(1, total)];
        for (int p = 0; p < pedidos; p++) {
            int partes = cuantasDe[p];
            int q = instancia.pedidoCantidad(p);
            int base = q / partes;
            int resto = q - base * partes;
            for (int k = 0; k < partes; k++) {
                int t = primeraDe[p] + k;
                pedidoDe[t] = p;
                cantidadDe[t] = base + (k < resto ? 1 : 0);
            }
        }
    }

    /**
     * Mayor capacidad entre los tipos con al menos una unidad presente. Es el tamano maximo
     * que puede tener una visita, de modo que ningun corte por debajo de el es necesario y
     * ninguno por encima de el es servible.
     */
    private static int capacidadDeCorte(InstanciaPlanificacion instancia) {
        int mayor = 0;
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            int capacidad = instancia.unidadCapacidad(u);
            if (capacidad > mayor) {
                mayor = capacidad;
            }
        }
        if (mayor > 0) {
            return mayor;
        }
        // Fotografia sin unidades: el corte no llega a usarse, pero debe ser positivo.
        for (TipoUnidad tipo : TipoUnidad.values()) {
            if (tipo.capacidad() > mayor) {
                mayor = tipo.capacidad();
            }
        }
        return Math.max(1, mayor);
    }

    private static int partes(int cantidadPedido, int capacidad) {
        if (cantidadPedido <= capacidad) {
            return 1;
        }
        return (cantidadPedido + capacidad - 1) / capacidad;
    }

    /** Numero total de tareas de la fotografia. */
    public int cantidad() {
        return cantidad;
    }

    /** Numero de pedidos de la fotografia. */
    public int cantidadPedidos() {
        return instancia.cantidadPedidos();
    }

    /** Capacidad con que se cortan los pedidos grandes, en unidades del producto P. */
    public int capacidadDeCorte() {
        return capacidadCorte;
    }

    /** Pedido al que pertenece la tarea. */
    public int pedido(int tarea) {
        return pedidoDe[tarea];
    }

    /** Unidades del producto P que entrega la tarea. */
    public int unidades(int tarea) {
        return cantidadDe[tarea];
    }

    /** Punto de la matriz de distancias en que se entrega la tarea. */
    public int punto(int tarea) {
        return instancia.puntoPedido(pedidoDe[tarea]);
    }

    /** Instante limite de entrega de la tarea, heredado de su pedido. */
    public long limite(int tarea) {
        return instancia.pedidoMinutoLimite(pedidoDe[tarea]);
    }

    /** Holgura de la tarea respecto del instante de la fotografia. */
    public long holgura(int tarea) {
        return instancia.pedidoHolgura(pedidoDe[tarea]);
    }

    /** Primera tarea del pedido. Las tareas de un pedido ocupan indices consecutivos. */
    public int primeraDePedido(int pedido) {
        return primeraDe[pedido];
    }

    /** Numero de tareas en que se reparte el pedido. */
    public int tareasDePedido(int pedido) {
        return cuantasDe[pedido];
    }

    /** Indica si algun pedido de la fotografia necesita mas de una tarea. */
    public boolean hayPedidosPartidos() {
        return cantidad > instancia.cantidadPedidos();
    }

    @Override
    public String toString() {
        return "TareasAlns[pedidos=" + instancia.cantidadPedidos() + " tareas=" + cantidad
                + " corte=" + capacidadCorte + "]";
    }
}
