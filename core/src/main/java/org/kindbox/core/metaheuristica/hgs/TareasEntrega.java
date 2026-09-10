package org.kindbox.core.metaheuristica.hgs;

import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Descomposicion de los pedidos pendientes en tareas de entrega, que son los genes del
 * cromosoma de la busqueda genetica hibrida (apartado 6.3.1 del ISA).
 *
 * <p>Una tarea es una visita a un destinatario con una cantidad concreta del producto P.
 * Casi siempre hay una tarea por pedido, pero el enunciado admite entregas parciales y la
 * cantidad de un pedido puede superar la capacidad de cualquier unidad de la flota, en cuyo
 * caso una sola visita seria imposible: {@code ProgramadorRuta} rechaza toda visita cuya
 * cantidad exceda la capacidad de la unidad. Por eso un pedido de cantidad mayor que la
 * capacidad mas grande de la flota disponible se reparte en varias tareas de tamano lo mas
 * parejo posible; repartir parejo, y no llenar la primera visita al maximo, deja tareas que
 * tambien caben en las unidades pequenas y por tanto amplia el conjunto de rutas
 * admisibles.</p>
 *
 * <p>Un pedido cuenta para H, el nivel 1 del objetivo, cuando queda sin asignar
 * <b>cualquiera</b> de sus tareas, tal como establece {@code Solucion}.</p>
 *
 * <p>El objeto se construye una sola vez por iteracion de planificacion y es de solo
 * lectura a partir de ahi, de modo que el Split, la educacion y el cruce lo comparten sin
 * copiarlo. Tambien precalcula la lista de tareas geograficamente proximas a cada tarea,
 * que es la granularidad del vecindario de la busqueda local del apartado 6.3.3: sin ella
 * la exploracion seria cuadratica en el numero de tareas.</p>
 */
public final class TareasEntrega {

    private final InstanciaPlanificacion instancia;
    private final int cantidad;
    private final int[] pedido;
    private final int[] cantidadUnidades;
    private final int[] punto;
    private final long[] limite;
    private final int[] almacenCercano;
    private final int[] primeraDePedido;
    private final int[] tareasDePedido;
    private final int[][] vecinos;

    /**
     * @param instancia    fotografia del problema
     * @param granularidad numero de tareas proximas que se conservan por tarea
     */
    public TareasEntrega(InstanciaPlanificacion instancia, int granularidad) {
        this.instancia = instancia;
        final int cantidadPedidos = instancia.cantidadPedidos();
        final int capacidadMaxima = capacidadMaximaDisponible(instancia);

        this.primeraDePedido = new int[cantidadPedidos];
        this.tareasDePedido = new int[cantidadPedidos];
        int total = 0;
        for (int i = 0; i < cantidadPedidos; i++) {
            int visitas = (instancia.pedidoCantidad(i) + capacidadMaxima - 1) / capacidadMaxima;
            primeraDePedido[i] = total;
            tareasDePedido[i] = visitas;
            total += visitas;
        }

        this.cantidad = total;
        this.pedido = new int[total];
        this.cantidadUnidades = new int[total];
        this.punto = new int[total];
        this.limite = new long[total];
        this.almacenCercano = new int[total];

        for (int i = 0; i < cantidadPedidos; i++) {
            int restante = instancia.pedidoCantidad(i);
            int visitas = tareasDePedido[i];
            int base = primeraDePedido[i];
            for (int k = 0; k < visitas; k++) {
                // Reparto parejo: el redondeo hacia arriba de lo que resta entre las visitas
                // que quedan reparte el sobrante entre las primeras tareas.
                int porVisita = (restante + (visitas - k) - 1) / (visitas - k);
                int t = base + k;
                pedido[t] = i;
                cantidadUnidades[t] = porVisita;
                punto[t] = instancia.puntoPedido(i);
                limite[t] = instancia.pedidoMinutoLimite(i);
                almacenCercano[t] = almacenMasCercano(instancia, punto[t]);
                restante -= porVisita;
            }
        }

        this.vecinos = calcularVecinos(instancia, granularidad);
    }

    /** Numero de tareas de entrega, que es la longitud del cromosoma. */
    public int cantidad() {
        return cantidad;
    }

    /** Indice local del pedido al que pertenece la tarea. */
    public int pedido(int tarea) {
        return pedido[tarea];
    }

    /** Unidades del producto P que entrega la tarea. */
    public int cantidadUnidades(int tarea) {
        return cantidadUnidades[tarea];
    }

    /** Punto de la matriz de distancias en que se atiende la tarea. */
    public int punto(int tarea) {
        return punto[tarea];
    }

    /** Instante limite de llegada de la tarea. */
    public long limite(int tarea) {
        return limite[tarea];
    }

    /**
     * Almacen mas cercano al destino de la tarea. Es el que fija el punto de arranque
     * supuesto de una ruta que empieza por esta tarea, con el que el Split rompe la
     * dependencia circular entre el costo de la ruta y la unidad que la atiende.
     */
    public int almacenCercano(int tarea) {
        return almacenCercano[tarea];
    }

    /** Tareas geograficamente proximas a la dada, en orden de distancia creciente. */
    public int[] vecinos(int tarea) {
        return vecinos[tarea];
    }

    /** Indice de la primera tarea del pedido. */
    public int primeraDePedido(int indicePedido) {
        return primeraDePedido[indicePedido];
    }

    /** Numero de tareas en que se descompuso el pedido. */
    public int tareasDePedido(int indicePedido) {
        return tareasDePedido[indicePedido];
    }

    /** Instancia sobre la que se construyo la descomposicion. */
    public InstanciaPlanificacion instancia() {
        return instancia;
    }

    // ----------------------------------------------------------------- internos

    /**
     * Capacidad de la unidad mas grande de las que estan disponibles. Es la que fija el
     * tamano maximo de una visita; si la flota se quedase sin ninguna unidad se toma la del
     * auto, para que la descomposicion siga estando definida.
     */
    private static int capacidadMaximaDisponible(InstanciaPlanificacion instancia) {
        int maxima = 0;
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            maxima = Math.max(maxima, instancia.unidadCapacidad(u));
        }
        return maxima > 0 ? maxima : TipoUnidad.AUTO.capacidad();
    }

    /** Almacen que minimiza la distancia hasta el punto dado. */
    private static int almacenMasCercano(InstanciaPlanificacion instancia, int puntoDestino) {
        MatrizDistancias matriz = instancia.matriz();
        int elegido = 0;
        int mejor = Integer.MAX_VALUE;
        for (int a = 0; a < instancia.cantidadAlmacenes(); a++) {
            int km = matriz.km(instancia.puntoAlmacen(a), puntoDestino);
            if (km < mejor) {
                mejor = km;
                elegido = a;
            }
        }
        return elegido;
    }

    /**
     * Lista de tareas proximas a cada tarea. Se recorre el orden de distancia creciente que
     * devuelve la matriz y se expande cada punto de pedido a todas sus tareas. Las tareas
     * hermanas, las del mismo pedido, encabezan la lista: comparten punto y por eso la
     * matriz no las devuelve, pero son las primeras candidatas de cualquier movimiento.
     */
    private int[][] calcularVecinos(InstanciaPlanificacion instancia, int granularidad) {
        final MatrizDistancias matriz = instancia.matriz();
        final int primerPuntoPedido = instancia.cantidadAlmacenes();
        final int ultimoPuntoPedido = primerPuntoPedido + instancia.cantidadPedidos();
        int[][] resultado = new int[cantidad][];
        int[] acumulador = new int[cantidad];

        for (int t = 0; t < cantidad; t++) {
            int n = 0;
            int propio = pedido[t];
            int base = primeraDePedido[propio];
            for (int k = 0; k < tareasDePedido[propio]; k++) {
                if (base + k != t) {
                    acumulador[n++] = base + k;
                }
            }
            int[] proximos = matriz.vecinosCercanos(punto[t]);
            for (int i = 0; i < proximos.length && n < granularidad + tareasDePedido[propio]; i++) {
                int p = proximos[i];
                if (p < primerPuntoPedido || p >= ultimoPuntoPedido) {
                    continue;
                }
                int vecino = p - primerPuntoPedido;
                if (vecino == propio) {
                    continue;
                }
                if (matriz.km(punto[t], p) >= MatrizDistancias.INALCANZABLE) {
                    continue;
                }
                int inicio = primeraDePedido[vecino];
                for (int k = 0; k < tareasDePedido[vecino] && n < acumulador.length; k++) {
                    acumulador[n++] = inicio + k;
                }
            }
            int[] copia = new int[n];
            System.arraycopy(acumulador, 0, copia, 0, n);
            resultado[t] = copia;
        }
        return resultado;
    }
}
