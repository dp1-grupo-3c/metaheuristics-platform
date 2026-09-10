package org.kindbox.core.metaheuristica.alns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Representacion mutable de trabajo de la busqueda adaptativa de vecindad amplia, conforme
 * al apartado 7.3.1 del ISA.
 *
 * <p>La solucion es un conjunto de secuencias de tareas de entrega, una por unidad de
 * transporte, mas un <b>banco de tareas no asignadas</b>. Una tarea es una visita de entrega:
 * casi siempre hay una por pedido, y solo los pedidos que superan la capacidad de la unidad
 * mas grande de la flota se reparten en varias, conforme a {@link TareasAlns}. La {@code H}
 * del nivel 1 de la funcion objetivo del apartado 2.5 es el numero de <b>pedidos</b> con
 * alguna tarea en el banco, y se lleva incrementalmente: un pedido servido a medias sigue
 * contando uno, porque el enunciado solo da por atendido el pedido completo.</p>
 *
 * <p>Todo se guarda en arreglos primitivos que se reutilizan durante la corrida entera. Un
 * movimiento no asigna memoria: inserta o retira un entero de una fila y vuelve a valorar la
 * ruta afectada con {@link ProgramadorRuta#evaluar}, que es el camino del decodificador que
 * no construye objetos. Cada fila se lleva por duplicado, una con los indices de tarea, que
 * son la identidad con que trabajan los operadores, y otra con los indices de pedido, que es
 * lo que el decodificador espera recibir. La conversion a {@link Solucion} ocurre una sola
 * vez, al devolver el resultado.</p>
 *
 * <h2>Factibilidad</h2>
 * <p>Un estado <b>siempre</b> es factible en las siete restricciones duras del apartado 2.6:
 * la unica infactibilidad admitida es la del banco, que es la que mide el nivel 1. Esa es la
 * diferencia estructural con la busqueda genetica hibrida, que si mantiene una subpoblacion
 * infactible. La comprobacion vive en el operador de insercion, que solo acepta una posicion
 * cuando el decodificador la declara factible.</p>
 *
 * <h2>Inventario de los almacenes intermedios</h2>
 * <p>Durante la busqueda cada ruta se valora con el inventario intacto, es decir con el modo
 * del decodificador que no consume inventario, porque valorar una posicion candidata no debe
 * ensuciar un recurso compartido por todas las rutas. Al materializar la solucion las rutas
 * se programan en orden consumiendo de verdad el inventario, y si alguna dejase de ser
 * factible por ese consumo sus ultimas visitas se devuelven al banco. Asi el plan devuelto
 * cumple tambien la restriccion 5 del apartado 2.6.</p>
 *
 * <p>La clase no es segura para uso concurrente: comparte el {@link ProgramadorRuta} con los
 * operadores del algoritmo que la posee.</p>
 */
public final class EstadoAlns {

    /** Holgura minima de filas de ruta, para instancias con turnos muy cortos. */
    private static final int CAPACIDAD_RUTA_MINIMA = 8;

    private final InstanciaPlanificacion instancia;
    private final TareasAlns tareas;
    private final ProgramadorRuta programador;
    private final int cantidadUnidades;
    private final int cantidadPedidos;
    private final int cantidadTareas;

    /** Capacidad actual de cada fila de ruta. Todas las filas comparten capacidad. */
    private int capacidadRuta;
    /** Secuencia de tareas de cada unidad, en orden de visita. */
    private int[][] rutaTarea;
    /** Pedido de cada visita, paralelo a {@link #rutaTarea}. Es lo que recibe el decodificador. */
    private int[][] rutaPedido;
    /** Unidades del producto P de cada visita, paralela a {@link #rutaTarea}. */
    private int[][] rutaCantidad;
    private final int[] longitud;
    private final int[] kilometrosRuta;
    private final double[] costoRuta;

    /** Unidad que atiende cada tarea, o {@code -1} si esta en el banco. */
    private final int[] unidadDe;
    /** Posicion de la tarea dentro de la secuencia de su unidad. */
    private final int[] posicionDe;
    /** Tareas del banco, sin orden significativo. */
    private final int[] banco;
    /** Posicion de cada tarea dentro del banco, o {@code -1} si esta asignada. */
    private final int[] posicionEnBanco;
    private int tamanoBanco;

    /** Tareas de cada pedido que siguen en el banco. Con una o mas, el pedido suma a H. */
    private final int[] tareasEnBancoDe;
    /** Pedidos con alguna tarea en el banco, que es exactamente la H del nivel 1. */
    private int pedidosPendientes;

    private double costoTotal;
    private int kilometrosTotales;

    /**
     * Crea un estado con todas las tareas en el banco y todas las rutas vacias, descomponiendo
     * los pedidos por su cuenta.
     *
     * @param instancia   fotografia del problema
     * @param programador decodificador compartido con los operadores del algoritmo
     */
    public EstadoAlns(InstanciaPlanificacion instancia, ProgramadorRuta programador) {
        this(instancia, new TareasAlns(instancia), programador);
    }

    /**
     * Crea un estado sobre una descomposicion en tareas ya calculada, que es como lo usa el
     * algoritmo: los tres estados de una corrida y el motor de insercion comparten una sola.
     *
     * @param instancia   fotografia del problema
     * @param tareas      descomposicion de los pedidos en visitas
     * @param programador decodificador compartido con los operadores del algoritmo
     */
    public EstadoAlns(InstanciaPlanificacion instancia, TareasAlns tareas, ProgramadorRuta programador) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.programador = programador;
        this.cantidadUnidades = instancia.cantidadUnidades();
        this.cantidadPedidos = instancia.cantidadPedidos();
        this.cantidadTareas = tareas.cantidad();
        this.capacidadRuta = capacidadInicial(instancia, cantidadTareas);
        this.rutaTarea = new int[cantidadUnidades][capacidadRuta];
        this.rutaPedido = new int[cantidadUnidades][capacidadRuta];
        this.rutaCantidad = new int[cantidadUnidades][capacidadRuta];
        this.longitud = new int[cantidadUnidades];
        this.kilometrosRuta = new int[cantidadUnidades];
        this.costoRuta = new double[cantidadUnidades];
        this.unidadDe = new int[Math.max(1, cantidadTareas)];
        this.posicionDe = new int[Math.max(1, cantidadTareas)];
        this.banco = new int[Math.max(1, cantidadTareas)];
        this.posicionEnBanco = new int[Math.max(1, cantidadTareas)];
        this.tareasEnBancoDe = new int[Math.max(1, cantidadPedidos)];
        vaciar();
    }

    /**
     * Cota superior razonable de visitas por ruta: cada entrega consume el tiempo de
     * acondicionamiento, de modo que un turno acota cuantas caben. Es solo la capacidad
     * inicial de las filas; {@link #asegurarCapacidadRuta} las agranda si hiciera falta.
     */
    private static int capacidadInicial(InstanciaPlanificacion instancia, int cantidadTareas) {
        int servicio = Math.max(1, instancia.parametros().minutosAcondicionamiento());
        int maximo = CAPACIDAD_RUTA_MINIMA;
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            long ventana = instancia.unidadMinutoFinTurno(u) - instancia.unidadMinutoDisponible(u);
            long cabidas = Math.max(0L, ventana / servicio) + 2L;
            if (cabidas > maximo) {
                maximo = (int) Math.min(cabidas, cantidadTareas + 1L);
            }
        }
        return Math.max(CAPACIDAD_RUTA_MINIMA, Math.min(maximo, Math.max(1, cantidadTareas)));
    }

    // ------------------------------------------------------------- consultas

    public InstanciaPlanificacion instancia() {
        return instancia;
    }

    /** Descomposicion de los pedidos en tareas sobre la que trabaja el estado. */
    public TareasAlns tareas() {
        return tareas;
    }

    /** Decodificador compartido. Los operadores lo usan para valorar posiciones candidatas. */
    public ProgramadorRuta programador() {
        return programador;
    }

    public int cantidadUnidades() {
        return cantidadUnidades;
    }

    public int cantidadPedidos() {
        return cantidadPedidos;
    }

    /** Numero de tareas de entrega, que es el espacio de indices de los operadores. */
    public int cantidadTareas() {
        return cantidadTareas;
    }

    /** Numero de visitas de la ruta de la unidad. */
    public int longitudRuta(int unidad) {
        return longitud[unidad];
    }

    /** Tarea que ocupa la posicion dada de la ruta de la unidad. */
    public int tareaEn(int unidad, int posicion) {
        return rutaTarea[unidad][posicion];
    }

    /** Pedido que ocupa la posicion dada de la ruta de la unidad. */
    public int pedidoEn(int unidad, int posicion) {
        return rutaPedido[unidad][posicion];
    }

    /** Unidad que atiende la tarea, o {@code -1} si esta en el banco. */
    public int unidadDe(int tarea) {
        return unidadDe[tarea];
    }

    /**
     * Unidad que atiende alguna tarea del pedido, o {@code -1} si ninguna esta colocada. Es
     * lo que consultan los operadores guiados por geometria, que recorren puntos vecinos y
     * solo conocen el pedido al que corresponde cada punto.
     */
    public int unidadDePedido(int pedido) {
        int primera = tareas.primeraDePedido(pedido);
        int fin = primera + tareas.tareasDePedido(pedido);
        for (int t = primera; t < fin; t++) {
            if (unidadDe[t] >= 0) {
                return unidadDe[t];
            }
        }
        return -1;
    }

    /**
     * Tarea colocada del pedido, o {@code -1} si todas estan en el banco. Complementa a
     * {@link #unidadDePedido} para los operadores que necesitan retirar algo concreto.
     */
    public int tareaColocadaDe(int pedido) {
        int primera = tareas.primeraDePedido(pedido);
        int fin = primera + tareas.tareasDePedido(pedido);
        for (int t = primera; t < fin; t++) {
            if (unidadDe[t] >= 0) {
                return t;
            }
        }
        return -1;
    }

    /** Posicion de la tarea dentro de la ruta de su unidad, o {@code -1} si esta en el banco. */
    public int posicionDe(int tarea) {
        return unidadDe[tarea] < 0 ? -1 : posicionDe[tarea];
    }

    /** Indica si la tarea esta sin asignar. */
    public boolean enBanco(int tarea) {
        return unidadDe[tarea] < 0;
    }

    /** Cardinal del banco, medido en tareas. */
    public int tamanoBanco() {
        return tamanoBanco;
    }

    /**
     * Pedidos con alguna tarea sin asignar, que es exactamente la H del nivel 1 del objetivo.
     * Coincide con {@link #tamanoBanco} cuando ningun pedido necesita entregas parciales.
     */
    public int h() {
        return pedidosPendientes;
    }

    /** Tarea que ocupa la posicion dada del banco. */
    public int bancoEn(int posicion) {
        return banco[posicion];
    }

    /** Tareas ya colocadas en alguna ruta. */
    public int asignados() {
        return cantidadTareas - tamanoBanco;
    }

    /** Costo de operacion del plan, en soles. Es la S del nivel 2 del objetivo. */
    public double costo() {
        return costoTotal;
    }

    /** Kilometros totales del plan. */
    public int kilometros() {
        return kilometrosTotales;
    }

    /** Costo de la ruta de la unidad, en soles. */
    public double costoRuta(int unidad) {
        return costoRuta[unidad];
    }

    /** Kilometros de la ruta de la unidad. */
    public int kilometrosRuta(int unidad) {
        return kilometrosRuta[unidad];
    }

    /** Capacidad actual de las filas de ruta. */
    public int capacidadRuta() {
        return capacidadRuta;
    }

    /** Valor jerarquico del estado, con la penalizacion blanda en cero. */
    public ValorObjetivo valor() {
        return new ValorObjetivo(pedidosPendientes, costoTotal, 0.0);
    }

    /**
     * Escalar interno que guia la aceptacion por recocido simulado. Suma el costo y una
     * penalizacion por pedido pendiente, de modo que el nivel 1 del objetivo se traduzca en
     * un unico numero comparable. La comparacion entre soluciones que se reportan sigue
     * siendo la jerarquica de {@link ValorObjetivo}.
     */
    public double escalar(double penalizacionPorPedidoDelBanco) {
        return costoTotal + penalizacionPorPedidoDelBanco * pedidosPendientes;
    }

    /** Acceso directo a la fila de tareas de una unidad. No debe modificarse desde fuera. */
    int[] filaTareas(int unidad) {
        return rutaTarea[unidad];
    }

    /** Acceso directo a la fila de pedidos de una unidad. No debe modificarse desde fuera. */
    int[] filaPedidos(int unidad) {
        return rutaPedido[unidad];
    }

    /** Acceso directo a la fila de cantidades de una unidad. No debe modificarse desde fuera. */
    int[] filaCantidades(int unidad) {
        return rutaCantidad[unidad];
    }

    // ---------------------------------------------------------- modificacion

    /** Devuelve el estado a rutas vacias y banco completo. */
    public void vaciar() {
        for (int u = 0; u < cantidadUnidades; u++) {
            longitud[u] = 0;
            kilometrosRuta[u] = 0;
            costoRuta[u] = 0.0;
        }
        tamanoBanco = 0;
        for (int p = 0; p < cantidadPedidos; p++) {
            tareasEnBancoDe[p] = 0;
        }
        pedidosPendientes = 0;
        for (int t = 0; t < cantidadTareas; t++) {
            unidadDe[t] = -1;
            posicionDe[t] = -1;
            posicionEnBanco[t] = -1;
            meterEnBanco(t);
        }
        costoTotal = 0.0;
        kilometrosTotales = 0;
    }

    /**
     * Inserta la tarea en la posicion dada de la ruta de la unidad, tomando por buenos los
     * kilometros y el costo que el llamante acaba de obtener del decodificador. Es el camino
     * caliente: evita reevaluar la ruta que el operador de insercion ya valoro.
     */
    public void insertarValorado(int unidad, int posicion, int tarea, int kilometros, double costo) {
        asegurarCapacidadRuta(longitud[unidad] + 1);
        int[] fila = rutaTarea[unidad];
        int[] pedidos = rutaPedido[unidad];
        int[] cantidades = rutaCantidad[unidad];
        for (int i = longitud[unidad]; i > posicion; i--) {
            fila[i] = fila[i - 1];
            pedidos[i] = pedidos[i - 1];
            cantidades[i] = cantidades[i - 1];
            posicionDe[fila[i]] = i;
        }
        fila[posicion] = tarea;
        pedidos[posicion] = tareas.pedido(tarea);
        cantidades[posicion] = tareas.unidades(tarea);
        longitud[unidad]++;
        unidadDe[tarea] = unidad;
        posicionDe[tarea] = posicion;
        sacarDelBanco(tarea);

        costoTotal += costo - costoRuta[unidad];
        kilometrosTotales += kilometros - kilometrosRuta[unidad];
        costoRuta[unidad] = costo;
        kilometrosRuta[unidad] = kilometros;
    }

    /**
     * Inserta la tarea valorando la ruta resultante por su cuenta.
     *
     * @return {@code false} si la ruta resultante no es factible, en cuyo caso la insercion
     *         se deshace y el estado queda intacto
     */
    public boolean insertar(int unidad, int posicion, int tarea) {
        int kilometrosPrevios = kilometrosRuta[unidad];
        double costoPrevio = costoRuta[unidad];
        insertarValorado(unidad, posicion, tarea, kilometrosPrevios, costoPrevio);
        if (programador.evaluar(unidad, rutaPedido[unidad], rutaCantidad[unidad], longitud[unidad])
                && programador.ultimaFactible()) {
            costoTotal += programador.ultimoCosto() - costoRuta[unidad];
            kilometrosTotales += programador.ultimosKilometros() - kilometrosRuta[unidad];
            costoRuta[unidad] = programador.ultimoCosto();
            kilometrosRuta[unidad] = programador.ultimosKilometros();
            return true;
        }
        quitar(tarea);
        return false;
    }

    /** Retira la tarea de su ruta y la devuelve al banco. No hace nada si ya estaba en el. */
    public void quitar(int tarea) {
        int unidad = unidadDe[tarea];
        if (unidad < 0) {
            return;
        }
        quitarEn(unidad, posicionDe[tarea]);
    }

    /**
     * Retira la visita que ocupa la posicion dada de la ruta de la unidad y devuelve su
     * tarea al banco.
     *
     * @return la tarea retirada
     */
    public int quitarEn(int unidad, int posicion) {
        int[] fila = rutaTarea[unidad];
        int[] pedidos = rutaPedido[unidad];
        int[] cantidades = rutaCantidad[unidad];
        int tarea = fila[posicion];
        int fin = longitud[unidad] - 1;
        for (int i = posicion; i < fin; i++) {
            fila[i] = fila[i + 1];
            pedidos[i] = pedidos[i + 1];
            cantidades[i] = cantidades[i + 1];
            posicionDe[fila[i]] = i;
        }
        longitud[unidad] = fin;
        unidadDe[tarea] = -1;
        posicionDe[tarea] = -1;
        meterEnBanco(tarea);
        reevaluarRuta(unidad);
        return tarea;
    }

    /** Vacia la ruta de una unidad y manda todas sus tareas al banco. */
    public int vaciarRuta(int unidad) {
        int retirados = longitud[unidad];
        int[] fila = rutaTarea[unidad];
        for (int i = 0; i < retirados; i++) {
            int tarea = fila[i];
            unidadDe[tarea] = -1;
            posicionDe[tarea] = -1;
            meterEnBanco(tarea);
        }
        longitud[unidad] = 0;
        costoTotal -= costoRuta[unidad];
        kilometrosTotales -= kilometrosRuta[unidad];
        costoRuta[unidad] = 0.0;
        kilometrosRuta[unidad] = 0;
        return retirados;
    }

    /** Copia barata del estado dado sobre este. Solo mueve arreglos primitivos. */
    public void copiarDesde(EstadoAlns otro) {
        asegurarCapacidadRuta(otro.capacidadRuta);
        for (int u = 0; u < cantidadUnidades; u++) {
            int n = otro.longitud[u];
            System.arraycopy(otro.rutaTarea[u], 0, rutaTarea[u], 0, n);
            System.arraycopy(otro.rutaPedido[u], 0, rutaPedido[u], 0, n);
            System.arraycopy(otro.rutaCantidad[u], 0, rutaCantidad[u], 0, n);
            longitud[u] = n;
            kilometrosRuta[u] = otro.kilometrosRuta[u];
            costoRuta[u] = otro.costoRuta[u];
        }
        System.arraycopy(otro.unidadDe, 0, unidadDe, 0, cantidadTareas);
        System.arraycopy(otro.posicionDe, 0, posicionDe, 0, cantidadTareas);
        System.arraycopy(otro.banco, 0, banco, 0, otro.tamanoBanco);
        System.arraycopy(otro.posicionEnBanco, 0, posicionEnBanco, 0, cantidadTareas);
        System.arraycopy(otro.tareasEnBancoDe, 0, tareasEnBancoDe, 0, cantidadPedidos);
        tamanoBanco = otro.tamanoBanco;
        pedidosPendientes = otro.pedidosPendientes;
        costoTotal = otro.costoTotal;
        kilometrosTotales = otro.kilometrosTotales;
    }

    /**
     * Carga el estado a partir de un plan ya construido, sea el de la heuristica
     * constructiva o el plan vigente de la iteracion anterior (apartado 11.4 del ISA).
     *
     * <p>Las paradas de entrega de un mismo pedido se emparejan en orden con las tareas de
     * ese pedido, de modo que un plan que reparte un pedido grande entre varias unidades se
     * carga tal cual. La cantidad que se toma es la de la tarea, no la de la parada: asi el
     * estado nunca declara entregar de un pedido mas de lo que tiene pendiente, aunque el
     * plan de partida lo hubiese repartido con otro corte. Cada ruta se valora al cerrarla
     * y, si no resulta factible con la fotografia actual, sus ultimas visitas vuelven al
     * banco hasta que lo sea. Asi el estado inicial cumple la invariante de factibilidad
     * dura.</p>
     */
    public void cargarDesde(Solucion plan) {
        vaciar();
        for (Ruta ruta : plan.rutas()) {
            int unidad = instancia.indiceDeUnidad(ruta.codigoUnidad());
            if (unidad < 0) {
                continue;
            }
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() != TipoParada.ENTREGA) {
                    continue;
                }
                int pedido = instancia.indiceDePedido(parada.idPedido());
                if (pedido < 0) {
                    continue;
                }
                int tarea = primeraTareaLibre(pedido);
                if (tarea < 0) {
                    continue;
                }
                asegurarCapacidadRuta(longitud[unidad] + 1);
                rutaTarea[unidad][longitud[unidad]] = tarea;
                rutaPedido[unidad][longitud[unidad]] = pedido;
                rutaCantidad[unidad][longitud[unidad]] = tareas.unidades(tarea);
                unidadDe[tarea] = unidad;
                posicionDe[tarea] = longitud[unidad];
                sacarDelBanco(tarea);
                longitud[unidad]++;
            }
            recortarHastaFactible(unidad);
        }
        recalcularTodo();
    }

    /** Primera tarea del pedido que sigue en el banco, o {@code -1} si no queda ninguna. */
    private int primeraTareaLibre(int pedido) {
        int primera = tareas.primeraDePedido(pedido);
        int fin = primera + tareas.tareasDePedido(pedido);
        for (int t = primera; t < fin; t++) {
            if (unidadDe[t] < 0) {
                return t;
            }
        }
        return -1;
    }

    /** Devuelve al banco las ultimas visitas de la ruta hasta que resulte factible. */
    private void recortarHastaFactible(int unidad) {
        while (longitud[unidad] > 0) {
            if (programador.evaluar(unidad, rutaPedido[unidad], rutaCantidad[unidad], longitud[unidad])
                    && programador.ultimaFactible()) {
                return;
            }
            int tarea = rutaTarea[unidad][longitud[unidad] - 1];
            longitud[unidad]--;
            unidadDe[tarea] = -1;
            posicionDe[tarea] = -1;
            meterEnBanco(tarea);
        }
    }

    /** Vuelve a valorar todas las rutas y rehace los agregados de costo y kilometros. */
    public void recalcularTodo() {
        costoTotal = 0.0;
        kilometrosTotales = 0;
        for (int u = 0; u < cantidadUnidades; u++) {
            if (longitud[u] == 0) {
                costoRuta[u] = 0.0;
                kilometrosRuta[u] = 0;
                continue;
            }
            if (!programador.evaluar(u, rutaPedido[u], rutaCantidad[u], longitud[u])) {
                vaciarRuta(u);
                continue;
            }
            costoRuta[u] = programador.ultimoCosto();
            kilometrosRuta[u] = programador.ultimosKilometros();
            costoTotal += costoRuta[u];
            kilometrosTotales += kilometrosRuta[u];
        }
    }

    // ------------------------------------------------------- materializacion

    /**
     * Convierte el estado en la {@link Solucion} que consumen el visualizador y la
     * verificacion del apartado 12.4.
     *
     * <p>Es el unico punto en que se construyen objetos y el unico en que el inventario de
     * los almacenes intermedios se consume de verdad. Si una ruta dejase de ser factible por
     * ese consumo, sus ultimas visitas se devuelven al banco: el plan devuelto cumple
     * siempre las siete restricciones duras, aunque su H pueda ser mayor que la del estado
     * en un caso que la practica no produce, porque la demanda de una iteracion queda muy
     * por debajo del inventario de los intermedios.</p>
     */
    public Solucion materializar() {
        programador.reiniciarInventarios();
        List<Ruta> rutas = new ArrayList<>(cantidadUnidades);
        costoTotal = 0.0;
        kilometrosTotales = 0;
        for (int u = 0; u < cantidadUnidades; u++) {
            recortarHastaFactible(u);
            Programacion programacion =
                    programador.programar(u, rutaPedido[u], rutaCantidad[u], longitud[u], true);
            if (programacion.sinProgramacion()) {
                vaciarRuta(u);
                programacion = programador.programar(u, rutaPedido[u], rutaCantidad[u], 0, true);
            }
            rutas.add(programacion.ruta());
            // Los agregados se toman de la programacion que se acaba de materializar, no de
            // una reevaluacion posterior: asi el banco, las rutas y el costo no pueden
            // discrepar entre si dentro de la Solucion devuelta.
            costoRuta[u] = programacion.costo();
            kilometrosRuta[u] = programacion.kilometros();
            costoTotal += costoRuta[u];
            kilometrosTotales += kilometrosRuta[u];
        }

        Map<Integer, Integer> noAtendidos = new LinkedHashMap<>();
        for (int p = 0; p < cantidadPedidos; p++) {
            if (tareasEnBancoDe[p] == 0) {
                continue;
            }
            int pendiente = 0;
            int primera = tareas.primeraDePedido(p);
            int fin = primera + tareas.tareasDePedido(p);
            for (int t = primera; t < fin; t++) {
                if (unidadDe[t] < 0) {
                    pendiente += tareas.unidades(t);
                }
            }
            noAtendidos.put(instancia.pedidoId(p), pendiente);
        }
        return new Solucion(rutas, noAtendidos, valor());
    }

    // ----------------------------------------------------------------- internos

    private void reevaluarRuta(int unidad) {
        costoTotal -= costoRuta[unidad];
        kilometrosTotales -= kilometrosRuta[unidad];
        if (longitud[unidad] == 0) {
            costoRuta[unidad] = 0.0;
            kilometrosRuta[unidad] = 0;
            return;
        }
        // Retirar visitas nunca vuelve improgramable una secuencia que si lo era, de modo
        // que la rama negativa es solo una salvaguarda de la invariante.
        if (!programador.evaluar(unidad, rutaPedido[unidad], rutaCantidad[unidad], longitud[unidad])) {
            costoRuta[unidad] = 0.0;
            kilometrosRuta[unidad] = 0;
            vaciarRutaSinAgregados(unidad);
            return;
        }
        costoRuta[unidad] = programador.ultimoCosto();
        kilometrosRuta[unidad] = programador.ultimosKilometros();
        costoTotal += costoRuta[unidad];
        kilometrosTotales += kilometrosRuta[unidad];
    }

    /** Vacia la ruta sin tocar los agregados, que el llamante ya desconto. */
    private void vaciarRutaSinAgregados(int unidad) {
        int[] fila = rutaTarea[unidad];
        for (int i = 0; i < longitud[unidad]; i++) {
            int tarea = fila[i];
            unidadDe[tarea] = -1;
            posicionDe[tarea] = -1;
            meterEnBanco(tarea);
        }
        longitud[unidad] = 0;
    }

    private void meterEnBanco(int tarea) {
        banco[tamanoBanco] = tarea;
        posicionEnBanco[tarea] = tamanoBanco;
        tamanoBanco++;
        if (tareasEnBancoDe[tareas.pedido(tarea)]++ == 0) {
            pedidosPendientes++;
        }
    }

    private void sacarDelBanco(int tarea) {
        int posicion = posicionEnBanco[tarea];
        if (posicion < 0) {
            return;
        }
        int ultimo = banco[--tamanoBanco];
        banco[posicion] = ultimo;
        posicionEnBanco[ultimo] = posicion;
        posicionEnBanco[tarea] = -1;
        if (--tareasEnBancoDe[tareas.pedido(tarea)] == 0) {
            pedidosPendientes--;
        }
    }

    private void asegurarCapacidadRuta(int necesaria) {
        if (necesaria <= capacidadRuta) {
            return;
        }
        int nueva = Math.max(necesaria, capacidadRuta * 2);
        for (int u = 0; u < cantidadUnidades; u++) {
            int[] tareasFila = new int[nueva];
            int[] pedidos = new int[nueva];
            int[] cantidades = new int[nueva];
            System.arraycopy(rutaTarea[u], 0, tareasFila, 0, longitud[u]);
            System.arraycopy(rutaPedido[u], 0, pedidos, 0, longitud[u]);
            System.arraycopy(rutaCantidad[u], 0, cantidades, 0, longitud[u]);
            rutaTarea[u] = tareasFila;
            rutaPedido[u] = pedidos;
            rutaCantidad[u] = cantidades;
        }
        capacidadRuta = nueva;
    }

    @Override
    public String toString() {
        return "EstadoAlns[H=" + pedidosPendientes + " tareasEnBanco=" + tamanoBanco
                + " S=" + String.format("%.2f", costoTotal) + " km=" + kilometrosTotales + "]";
    }
}
