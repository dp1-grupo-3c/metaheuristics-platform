package org.kindbox.core.simulacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.TipoParada;

/**
 * Estado del mundo simulado en un instante: almacenes, pedidos, flota e indicadores
 * acumulados.
 *
 * <p>Es el unico lugar donde vive el estado mutable de la corrida. {@link MotorSimulacion}
 * decide <em>cuando</em> cambia cada cosa y esta clase sabe <em>como</em> cambia y que
 * queda anotado en las metricas del apartado 12 del ISA.</p>
 *
 * <h2>Contabilidad de pedidos</h2>
 * <p>Un pedido registrado esta siempre en exactamente uno de tres conjuntos disjuntos:
 * entregado por completo, pendiente o incumplido. Un pedido cuyo instante limite pasa sin
 * haberse completado sale de los pendientes y entra en los incumplidos, porque la politica
 * de la empresa hace duras las ventanas de entrega y una unidad que llegase despues ya no
 * cumpliria el compromiso. De ahi que en toda instantanea se satisfaga
 * {@code registrados = entregados + pendientes + incumplidos}, que es la identidad con la
 * que se verifica que el motor no pierde ni duplica trabajo.</p>
 *
 * <p>Los pedidos pendientes se mantienen ademas en una cola de prioridad por instante
 * limite. Es la que permite al motor saber, sin muestrear el tiempo, cual es el proximo
 * instante en que puede producirse un colapso logistico.</p>
 *
 * <h2>Almacenes</h2>
 * <p>El central tiene inventario ilimitado; los dos intermedios arrancan a capacidad plena y
 * se recargan por completo cada dia a las 23:59. Cada consumo o recarga recalcula el color
 * de semaforo del requisito no funcional (d) del enunciado y, si cambio, deja constancia en
 * el registro cronologico de activaciones que muestra el modal de fin de simulacion.</p>
 *
 * <p>La clase no es segura para uso concurrente: se accede bajo el candado del motor.</p>
 */
public final class EstadoSimulacion {

    private final CalendarioEscenario calendario;
    private final RegistroBloqueos bloqueos;

    private final List<Almacen> almacenes;
    private final int[] inventario;
    private final ColorSemaforo[] colorAlmacen;
    private final int indiceCentral;

    private final List<Pedido> pedidos;
    private final Map<Integer, Integer> indicePorIdPedido;
    private final int[] pendiente;
    private final boolean[] registrado;
    private final boolean[] completado;
    private final boolean[] incumplido;
    private final boolean[] conEntregaParcial;
    private final PriorityQueue<Integer> vencimientos;

    private final UnidadEnCurso[] unidades;
    private final Map<String, Integer> indicePorCodigoUnidad;

    private int pedidosRegistrados;
    private int pedidosEntregados;
    private int pedidosIncumplidos;
    private int pedidosPendientes;
    private int unidadesEntregadas;
    private double costoAcumulado;
    private final int[] kilometrosPorTipo = new int[TipoUnidad.values().length];
    private final Map<Integer, Long> minutosEntregaPorPlazo = new TreeMap<>();
    private final Map<Integer, Integer> entregasPorPlazo = new TreeMap<>();
    private final List<MetricasSimulacion.ActivacionSemaforo> activaciones = new ArrayList<>();
    private final int[] averiasPorTipo = new int[TipoAveria.values().length];
    private long ejecucionesPlanificador;
    private long milisegundosPlanificador;

    /**
     * @param calendario  traductor entre el reloj interno y el calendario real
     * @param bloqueos    registro de tramos bloqueados del escenario
     * @param almacenes   almacenes de la empresa, en orden de identificador
     * @param pedidos     pedidos del escenario, ordenados por instante de registro
     * @param flota       unidades de transporte, ya situadas en el almacen central
     */
    public EstadoSimulacion(CalendarioEscenario calendario, RegistroBloqueos bloqueos,
                            List<Almacen> almacenes, List<Pedido> pedidos, List<UnidadTransporte> flota) {
        this.calendario = calendario;
        this.bloqueos = bloqueos;
        this.almacenes = List.copyOf(almacenes);
        this.inventario = new int[this.almacenes.size()];
        this.colorAlmacen = new ColorSemaforo[this.almacenes.size()];
        int central = 0;
        for (int i = 0; i < this.almacenes.size(); i++) {
            Almacen a = this.almacenes.get(i);
            inventario[i] = a.central() ? Integer.MAX_VALUE : a.capacidad();
            colorAlmacen[i] = ColorSemaforo.VERDE;
            if (a.central()) {
                central = i;
            }
        }
        this.indiceCentral = central;

        this.pedidos = List.copyOf(pedidos);
        int cantidad = this.pedidos.size();
        this.pendiente = new int[cantidad];
        this.registrado = new boolean[cantidad];
        this.completado = new boolean[cantidad];
        this.incumplido = new boolean[cantidad];
        this.conEntregaParcial = new boolean[cantidad];
        this.indicePorIdPedido = new HashMap<>(cantidad * 2);
        for (int i = 0; i < cantidad; i++) {
            indicePorIdPedido.put(this.pedidos.get(i).id(), i);
        }
        this.vencimientos = new PriorityQueue<>(Math.max(16, cantidad / 8),
                Comparator.comparingLong(i -> this.pedidos.get(i).minutoLimite()));

        this.unidades = new UnidadEnCurso[flota.size()];
        this.indicePorCodigoUnidad = new HashMap<>(flota.size() * 2);
        for (int i = 0; i < flota.size(); i++) {
            unidades[i] = new UnidadEnCurso(i, flota.get(i));
            indicePorCodigoUnidad.put(flota.get(i).codigo(), i);
        }
    }

    // -------------------------------------------------------------- contexto

    /** Traductor entre el reloj interno y el calendario real. */
    public CalendarioEscenario calendario() {
        return calendario;
    }

    /** Registro de tramos bloqueados del escenario. */
    public RegistroBloqueos bloqueos() {
        return bloqueos;
    }

    /** Almacenes de la empresa, en orden de identificador. */
    public List<Almacen> almacenes() {
        return almacenes;
    }

    /** Almacen central, al que retornan las unidades averiadas y las que entran en mantenimiento. */
    public Almacen central() {
        return almacenes.get(indiceCentral);
    }

    /** Inventario disponible del almacen, con {@link Integer#MAX_VALUE} para el central. */
    public int inventario(int indiceAlmacen) {
        return inventario[indiceAlmacen];
    }

    /** Posicion del almacen con el identificador dado, o {@code -1} si no existe. */
    public int indiceDeAlmacen(int idAlmacen) {
        for (int i = 0; i < almacenes.size(); i++) {
            if (almacenes.get(i).id() == idAlmacen) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- almacenes

    /**
     * Consume inventario de un almacen y devuelve cuanto se pudo retirar. El central es
     * ilimitado. La restriccion dura 5 del apartado 2.6 del ISA prohibe abastecerse en un
     * almacen sin existencias, de modo que el retiro se acota al disponible: el plan lo
     * respeta por construccion y este acotamiento solo impide que una discrepancia deje el
     * inventario en negativo.
     */
    public int consumirInventario(int indiceAlmacen, int cantidad) {
        if (almacenes.get(indiceAlmacen).central()) {
            return Math.max(0, cantidad);
        }
        int retirado = Math.max(0, Math.min(cantidad, inventario[indiceAlmacen]));
        inventario[indiceAlmacen] -= retirado;
        return retirado;
    }

    /** Recarga a capacidad plena los almacenes intermedios, de forma instantanea. */
    public void recargarIntermedios() {
        for (int i = 0; i < almacenes.size(); i++) {
            if (!almacenes.get(i).central()) {
                inventario[i] = almacenes.get(i).capacidad();
            }
        }
    }

    /**
     * Recalcula el color de semaforo del almacen y devuelve el nuevo color si cambio, o
     * {@code null} si sigue igual. Solo el cambio se notifica, porque el panel del prototipo
     * muestra activaciones y no un muestreo continuo.
     */
    public ColorSemaforo actualizarSemaforo(int indiceAlmacen, ParametrosOperacion.Instantanea parametros) {
        if (almacenes.get(indiceAlmacen).central()) {
            return null;
        }
        ColorSemaforo nuevo = ColorSemaforo.deInventario(inventario[indiceAlmacen], parametros);
        if (nuevo == colorAlmacen[indiceAlmacen]) {
            return null;
        }
        colorAlmacen[indiceAlmacen] = nuevo;
        return nuevo;
    }

    /** Color vigente del semaforo del almacen. */
    public ColorSemaforo colorAlmacen(int indiceAlmacen) {
        return colorAlmacen[indiceAlmacen];
    }

    /** Anota una activacion del semaforo en el registro cronologico de las metricas. */
    public void anotarActivacionSemaforo(long minuto, int indiceAlmacen, ColorSemaforo color) {
        activaciones.add(new MetricasSimulacion.ActivacionSemaforo(
                minuto, almacenes.get(indiceAlmacen).nombre(), color, inventario[indiceAlmacen]));
    }

    // ---------------------------------------------------------------- pedidos

    /** Pedidos del escenario, ordenados por instante de registro. */
    public List<Pedido> pedidos() {
        return pedidos;
    }

    /** Pedido en la posicion dada. */
    public Pedido pedido(int indice) {
        return pedidos.get(indice);
    }

    /** Posicion del pedido con el identificador dado, o {@code -1} si no existe. */
    public int indiceDePedido(int idPedido) {
        Integer i = indicePorIdPedido.get(idPedido);
        return i == null ? -1 : i;
    }

    /** Unidades del producto P que faltan por entregar del pedido, o {@code 0} si ya cerro. */
    public int pendienteDe(int indice) {
        return completado[indice] || incumplido[indice] ? 0 : pendiente[indice];
    }

    /**
     * Unidades del producto P que del pedido no se han entregado, este abierto o cerrado.
     *
     * <p>Se diferencia de {@link #pendienteDe(int)} en los dos extremos del ciclo de vida, y
     * por eso es la cifra que muestra la tabla de pedidos del visualizador. De un pedido
     * incumplido dice lo que se quedo sin entregar, y no cero, porque el operador necesita ver
     * el faltante; de uno que aun no ha llegado dice su cantidad entera, y no cero, porque la
     * reserva del contador solo se hace al procesar su llegada y la tabla puede consultarse en
     * ese mismo minuto simulado.</p>
     */
    public int noEntregadoDe(int indice) {
        return registrado[indice] ? pendiente[indice] : pedidos.get(indice).cantidad();
    }

    /** Registra la llegada de un pedido y lo incorpora al conjunto de pendientes. */
    public void registrarPedido(int indice) {
        if (registrado[indice]) {
            return;
        }
        registrado[indice] = true;
        pendiente[indice] = pedidos.get(indice).cantidad();
        pedidosRegistrados++;
        pedidosPendientes++;
        vencimientos.add(indice);
    }

    /**
     * Anota la entrega de una cantidad del pedido.
     *
     * @return {@code true} si el pedido quedo completo con esta entrega
     */
    public boolean entregar(int indice, int cantidad, long minuto) {
        if (cantidad <= 0 || !registrado[indice] || completado[indice] || incumplido[indice]) {
            return false;
        }
        int entregado = Math.min(cantidad, pendiente[indice]);
        pendiente[indice] -= entregado;
        unidadesEntregadas += entregado;
        if (pendiente[indice] > 0) {
            conEntregaParcial[indice] = true;
            return false;
        }
        completado[indice] = true;
        pedidosEntregados++;
        pedidosPendientes--;
        Pedido p = pedidos.get(indice);
        int plazo = p.plazoHoras();
        minutosEntregaPorPlazo.merge(plazo, minuto - p.minutoRegistro(), Long::sum);
        entregasPorPlazo.merge(plazo, 1, Integer::sum);
        return true;
    }

    /**
     * Instante limite del pedido pendiente mas urgente, o {@link Long#MAX_VALUE} si no queda
     * ninguno. Es el proximo instante en que puede producirse un colapso logistico.
     */
    public long proximoVencimiento() {
        while (!vencimientos.isEmpty()) {
            int i = vencimientos.peek();
            if (completado[i] || incumplido[i]) {
                vencimientos.poll();
                continue;
            }
            return pedidos.get(i).minutoLimite();
        }
        return Long.MAX_VALUE;
    }

    /**
     * Marca como incumplidos todos los pedidos pendientes cuyo limite ya paso y devuelve sus
     * posiciones, en orden de limite creciente. El primero de la lista es el que define el
     * instante de colapso del apartado 12.1 del ISA.
     */
    public List<Integer> vencidosHasta(long minuto) {
        List<Integer> vencidos = new ArrayList<>();
        while (!vencimientos.isEmpty()) {
            int i = vencimientos.peek();
            if (completado[i] || incumplido[i]) {
                vencimientos.poll();
                continue;
            }
            if (pedidos.get(i).minutoLimite() > minuto) {
                break;
            }
            vencimientos.poll();
            incumplido[i] = true;
            pedidosIncumplidos++;
            pedidosPendientes--;
            vencidos.add(i);
        }
        return vencidos;
    }

    /** Posiciones de los pedidos pendientes en el instante dado. */
    public int[] pendientesEn(long minuto) {
        int[] indices = new int[pedidosPendientes];
        int k = 0;
        for (int i = 0; i < pedidos.size() && k < indices.length; i++) {
            if (registrado[i] && !completado[i] && !incumplido[i] && pedidos.get(i).minutoRegistro() <= minuto) {
                indices[k++] = i;
            }
        }
        return k == indices.length ? indices : java.util.Arrays.copyOf(indices, k);
    }

    /** Pedidos aun sin completar ni incumplir. */
    public int pedidosPendientes() {
        return pedidosPendientes;
    }

    /** Pedidos que han llegado hasta el instante. */
    public int pedidosRegistrados() {
        return pedidosRegistrados;
    }

    /** Pedidos entregados por completo. */
    public int pedidosEntregados() {
        return pedidosEntregados;
    }

    /** Pedidos cuyo plazo vencio sin completarse. */
    public int pedidosIncumplidos() {
        return pedidosIncumplidos;
    }

    // --------------------------------------------------------------- unidades

    /** Unidades de la flota, en el orden en que las declara el archivo. */
    public UnidadEnCurso[] unidades() {
        return unidades;
    }

    /** Unidad en la posicion dada. */
    public UnidadEnCurso unidad(int indice) {
        return unidades[indice];
    }

    /** Posicion de la unidad con el codigo TTNN dado, o {@code -1} si no existe. */
    public int indiceDeUnidad(String codigo) {
        Integer i = indicePorCodigoUnidad.get(codigo);
        return i == null ? -1 : i;
    }

    // --------------------------------------------------------------- metricas

    /** Anota los kilometros y el costo de operacion de un tramo recorrido. */
    public void anotarRecorrido(TipoUnidad tipo, int kilometros) {
        if (kilometros <= 0) {
            return;
        }
        kilometrosPorTipo[tipo.ordinal()] += kilometros;
        costoAcumulado += kilometros * tipo.costoPorKm();
    }

    /** Anota una averia en el desglose por tipo. */
    public void anotarAveria(TipoAveria tipo) {
        averiasPorTipo[tipo.ordinal()]++;
    }

    /** Anota una ejecucion del planificador y el reloj de pared que consumio. */
    public void anotarEjecucionPlanificador(long milisegundos) {
        ejecucionesPlanificador++;
        milisegundosPlanificador += milisegundos;
    }

    /** Ejecuciones del planificador acumuladas. */
    public long ejecucionesPlanificador() {
        return ejecucionesPlanificador;
    }

    /** Fotografia inmutable de los indicadores acumulados. */
    public MetricasSimulacion metricas() {
        Map<String, Integer> kilometros = new LinkedHashMap<>();
        for (TipoUnidad t : TipoUnidad.values()) {
            // La clave es el nombre del enumerado, el mismo que usa el resto de la API.
            kilometros.put(t.name(), kilometrosPorTipo[t.ordinal()]);
        }
        Map<Integer, Double> promedios = new TreeMap<>();
        Map<Integer, Integer> conteos = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : entregasPorPlazo.entrySet()) {
            int plazo = e.getKey();
            int n = e.getValue();
            conteos.put(plazo, n);
            promedios.put(plazo, n == 0 ? 0.0 : (double) minutosEntregaPorPlazo.getOrDefault(plazo, 0L) / n);
        }
        Map<Integer, Integer> averias = new TreeMap<>();
        for (TipoAveria t : TipoAveria.values()) {
            averias.put(t.codigo(), averiasPorTipo[t.ordinal()]);
        }
        return new MetricasSimulacion(pedidosRegistrados, pedidosEntregados, pedidosPendientes(),
                pedidosConEntregaParcial(), unidadesEntregadas, pedidosIncumplidos, costoAcumulado,
                kilometros, promedios, conteos,
                activaciones, averias, ejecucionesPlanificador, milisegundosPlanificador);
    }

    /**
     * Pedidos con alguna entrega y todavia sin completar. Es un SUBCONJUNTO de los pendientes
     * y de los incumplidos, no una categoria disjunta: no debe sumarse a ellos.
     */
    private int pedidosConEntregaParcial() {
        int n = 0;
        for (int i = 0; i < pedidos.size(); i++) {
            if (conEntregaParcial[i] && !completado[i]) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ presentacion

    /** Estado de todos los almacenes tal como lo consume el visualizador. */
    public List<VistaAlmacen> vistasDeAlmacenes() {
        List<VistaAlmacen> vistas = new ArrayList<>(almacenes.size());
        for (int i = 0; i < almacenes.size(); i++) {
            Almacen a = almacenes.get(i);
            vistas.add(new VistaAlmacen(a.id(), a.nombre(), Ciudad.x(a.nodo()), Ciudad.y(a.nodo()),
                    a.central(), a.central() ? -1 : inventario[i], a.central() ? -1 : a.capacidad(),
                    colorAlmacen[i]));
        }
        return vistas;
    }

    /** Tramos bloqueados vigentes en el instante dado, tal como los dibuja el visualizador. */
    public List<VistaBloqueo> vistasDeBloqueos(long minuto) {
        List<Bloqueo> vigentes = bloqueos.vigentes(minuto);
        List<VistaBloqueo> vistas = new ArrayList<>(vigentes.size());
        for (Bloqueo b : vigentes) {
            int[] nodos = b.nodos();
            vistas.add(new VistaBloqueo(UnidadEnCurso.aPares(nodos, 0, nodos.length),
                    b.minutoInicio(), b.minutoFin()));
        }
        return vistas;
    }

    /**
     * Estado de una unidad tal como lo consume el visualizador.
     *
     * <p>Los pedidos a bordo se derivan de las paradas de entrega que aun le quedan al
     * itinerario vigente. El producto P es fungible y la carga a bordo es un unico numero,
     * de modo que esa derivacion es la unica lectura fiel de "que lleva esta unidad": lo que
     * le queda por entregar segun el plan comprometido.</p>
     */
    public VistaUnidad vistaDe(UnidadEnCurso u, long minuto) {
        UnidadTransporte unidad = u.unidad();
        int nodo = u.nodoActual(minuto);
        int destinoX = -1;
        int destinoY = -1;
        long minutosHastaDestino = 0L;
        if (u.indiceParada() >= 0 && !u.sirviendo()) {
            int nodoDestino = u.nodoEn(u.desplazamientoParada(u.indiceParada()));
            destinoX = Ciudad.x(nodoDestino);
            destinoY = Ciudad.y(nodoDestino);
            minutosHastaDestino = Math.max(0L, u.minutoLlegadaParada(u.indiceParada()) - minuto);
        }
        List<VistaUnidad.PedidoABordo> aBordo = new ArrayList<>();
        Ruta ruta = u.ruta();
        if (ruta != null && u.indiceParada() != UnidadEnCurso.SIN_PARADA) {
            int desde = u.indiceParada() < 0 ? 0 : (u.sirviendo() ? u.indiceParada() + 1 : u.indiceParada());
            List<Parada> paradas = ruta.paradas();
            for (int k = desde; k < paradas.size(); k++) {
                Parada p = paradas.get(k);
                if (p.tipo() != TipoParada.ENTREGA) {
                    continue;
                }
                int indicePedido = indiceDePedido(p.idPedido());
                int total = indicePedido < 0 ? p.cantidad() : pedidos.get(indicePedido).cantidad();
                aBordo.add(new VistaUnidad.PedidoABordo(p.idPedido(), p.cantidad(), total));
            }
        }
        return new VistaUnidad(unidad.codigo(), unidad.tipo(), unidad.estado(),
                Ciudad.x(nodo), Ciudad.y(nodo), unidad.cargaABordo(), unidad.capacidad(),
                u.caminoRecorrido(minuto), u.caminoPendiente(minuto),
                destinoX, destinoY, minutosHastaDestino, aBordo,
                u.averia() == null ? 0 : u.averia().codigo());
    }
}
