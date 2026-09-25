package org.kindbox.core.problema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Fotografia estatica del problema en el instante en que arranca una iteracion de
 * planificacion, conforme al esquema de descomposicion del apartado 2.2 del ISA.
 *
 * <p>Toda la informacion se expone en arreglos primitivos indexados por posicion, segun
 * la consideracion de implementacion del apartado 13. Los puntos de la matriz de
 * distancias se organizan en tres bloques contiguos:</p>
 * <ol>
 *   <li>almacenes, en {@code [0, cantidadAlmacenes)};</li>
 *   <li>destinos de pedidos, en {@code [cantidadAlmacenes, cantidadAlmacenes + cantidadPedidos)};</li>
 *   <li>posiciones iniciales de las unidades, en el bloque final.</li>
 * </ol>
 *
 * <p>La clase es inmutable. Los algoritmos no la modifican: producen una {@link Solucion}.</p>
 */
public final class InstanciaPlanificacion {

    private final long minutoActual;
    private final ParametrosOperacion.Instantanea parametros;
    private final MatrizDistancias matriz;

    private final int cantidadAlmacenes;
    private final int[] almacenId;
    private final String[] almacenNombre;
    private final int[] almacenNodo;
    private final int[] almacenInventario;
    private final boolean[] almacenCentral;

    private final int cantidadPedidos;
    private final int[] pedidoId;
    private final String[] pedidoCliente;
    private final int[] pedidoNodo;
    private final int[] pedidoCantidad;
    private final long[] pedidoMinutoLimite;
    private final long[] pedidoMinutoRegistro;
    private final int[] pedidoPlazoHoras;

    private final int cantidadUnidades;
    private final String[] unidadCodigo;
    private final TipoUnidad[] unidadTipo;
    private final int[] unidadNodo;
    private final int[] unidadCarga;
    private final long[] unidadMinutoDisponible;
    private final long[] unidadMinutoFinTurno;
    private final boolean[] unidadPausaCumplida;

    private final Map<Integer, Integer> indicePorIdPedido;
    private final Map<String, Integer> indicePorCodigoUnidad;
    private final Map<Integer, String> asignacionVigente;

    private InstanciaPlanificacion(Constructor c) {
        this.minutoActual = c.minutoActual;
        this.parametros = c.parametros;
        this.matriz = c.matriz;

        this.cantidadAlmacenes = c.almacenes.size();
        this.almacenId = new int[cantidadAlmacenes];
        this.almacenNombre = new String[cantidadAlmacenes];
        this.almacenNodo = new int[cantidadAlmacenes];
        this.almacenInventario = new int[cantidadAlmacenes];
        this.almacenCentral = new boolean[cantidadAlmacenes];
        for (int i = 0; i < cantidadAlmacenes; i++) {
            Almacen a = c.almacenes.get(i);
            almacenId[i] = a.id();
            almacenNombre[i] = a.nombre();
            almacenNodo[i] = a.nodo();
            almacenCentral[i] = a.central();
            almacenInventario[i] = a.central() ? Integer.MAX_VALUE : c.inventarios.get(i);
        }

        this.cantidadPedidos = c.pedidos.size();
        this.pedidoId = new int[cantidadPedidos];
        this.pedidoCliente = new String[cantidadPedidos];
        this.pedidoNodo = new int[cantidadPedidos];
        this.pedidoCantidad = new int[cantidadPedidos];
        this.pedidoMinutoLimite = new long[cantidadPedidos];
        this.pedidoMinutoRegistro = new long[cantidadPedidos];
        this.pedidoPlazoHoras = new int[cantidadPedidos];
        this.indicePorIdPedido = new HashMap<>(cantidadPedidos * 2);
        for (int i = 0; i < cantidadPedidos; i++) {
            Pedido p = c.pedidos.get(i);
            pedidoId[i] = p.id();
            pedidoCliente[i] = p.idCliente();
            pedidoNodo[i] = p.nodoDestino();
            pedidoCantidad[i] = c.cantidadesPendientes.get(i);
            pedidoMinutoLimite[i] = p.minutoLimite();
            pedidoMinutoRegistro[i] = p.minutoRegistro();
            pedidoPlazoHoras[i] = p.plazoHoras();
            indicePorIdPedido.put(p.id(), i);
        }

        this.cantidadUnidades = c.unidades.size();
        this.unidadCodigo = new String[cantidadUnidades];
        this.unidadTipo = new TipoUnidad[cantidadUnidades];
        this.unidadNodo = new int[cantidadUnidades];
        this.unidadCarga = new int[cantidadUnidades];
        this.unidadMinutoDisponible = new long[cantidadUnidades];
        this.unidadMinutoFinTurno = new long[cantidadUnidades];
        this.unidadPausaCumplida = new boolean[cantidadUnidades];
        this.indicePorCodigoUnidad = new HashMap<>(cantidadUnidades * 2);
        for (int i = 0; i < cantidadUnidades; i++) {
            UnidadTransporte u = c.unidades.get(i);
            unidadCodigo[i] = u.codigo();
            unidadTipo[i] = u.tipo();
            unidadNodo[i] = u.nodo();
            unidadCarga[i] = u.cargaABordo();
            long arranque = Math.max(minutoActual, u.minutoDisponibleDesde());
            unidadMinutoDisponible[i] = arranque;
            long horizonte = c.horizontes.get(i);
            unidadMinutoFinTurno[i] = horizonte > 0 ? horizonte : Turno.finDelTurno(arranque);
            unidadPausaCumplida[i] = c.pausasCumplidas.get(i);
            indicePorCodigoUnidad.put(u.codigo(), i);
        }

        this.asignacionVigente = Map.copyOf(c.asignacionVigente);
    }

    // ---------------------------------------------------------------- contexto

    /** Instante de la fotografia, en minutos desde el inicio del escenario. */
    public long minutoActual() {
        return minutoActual;
    }

    /** Parametros vigentes durante toda esta iteracion. */
    public ParametrosOperacion.Instantanea parametros() {
        return parametros;
    }

    /** Matriz de distancias sobre los puntos relevantes. */
    public MatrizDistancias matriz() {
        return matriz;
    }

    // --------------------------------------------------------------- almacenes

    public int cantidadAlmacenes() {
        return cantidadAlmacenes;
    }

    public int almacenId(int i) {
        return almacenId[i];
    }

    public String almacenNombre(int i) {
        return almacenNombre[i];
    }

    public int almacenNodo(int i) {
        return almacenNodo[i];
    }

    /** Inventario disponible del almacen. El central devuelve {@code Integer.MAX_VALUE}. */
    public int almacenInventario(int i) {
        return almacenInventario[i];
    }

    public boolean almacenEsCentral(int i) {
        return almacenCentral[i];
    }

    /** Punto de la matriz que corresponde al almacen {@code i}. */
    public int puntoAlmacen(int i) {
        return i;
    }

    // ----------------------------------------------------------------- pedidos

    public int cantidadPedidos() {
        return cantidadPedidos;
    }

    public int pedidoId(int i) {
        return pedidoId[i];
    }

    public String pedidoCliente(int i) {
        return pedidoCliente[i];
    }

    public int pedidoNodo(int i) {
        return pedidoNodo[i];
    }

    /** Unidades del producto P que faltan entregar del pedido. */
    public int pedidoCantidad(int i) {
        return pedidoCantidad[i];
    }

    public long pedidoMinutoLimite(int i) {
        return pedidoMinutoLimite[i];
    }

    public long pedidoMinutoRegistro(int i) {
        return pedidoMinutoRegistro[i];
    }

    public int pedidoPlazoHoras(int i) {
        return pedidoPlazoHoras[i];
    }

    /** Holgura del pedido respecto del instante de la fotografia, en minutos. */
    public long pedidoHolgura(int i) {
        return pedidoMinutoLimite[i] - minutoActual;
    }

    /** Punto de la matriz que corresponde al pedido {@code i}. */
    public int puntoPedido(int i) {
        return cantidadAlmacenes + i;
    }

    /** Indice local del pedido a partir de su identificador global, o {@code -1} si no esta pendiente. */
    public int indiceDePedido(int idGlobal) {
        Integer i = indicePorIdPedido.get(idGlobal);
        return i == null ? -1 : i;
    }

    // ---------------------------------------------------------------- unidades

    public int cantidadUnidades() {
        return cantidadUnidades;
    }

    public String unidadCodigo(int i) {
        return unidadCodigo[i];
    }

    public TipoUnidad unidadTipo(int i) {
        return unidadTipo[i];
    }

    public int unidadNodo(int i) {
        return unidadNodo[i];
    }

    /** Paquetes que la unidad ya lleva a bordo al arrancar la iteracion. */
    public int unidadCarga(int i) {
        return unidadCarga[i];
    }

    public int unidadCapacidad(int i) {
        return unidadTipo[i].capacidad();
    }

    /** Instante en que la unidad queda libre para arrancar su ruta. */
    public long unidadMinutoDisponible(int i) {
        return unidadMinutoDisponible[i];
    }

    /** Cierre del turno de la unidad. Ninguna ruta puede extenderse mas alla. */
    public long unidadMinutoFinTurno(int i) {
        return unidadMinutoFinTurno[i];
    }

    /**
     * Indica si la unidad ya cumplio la pausa de alimentacion del turno en que arranca su ruta.
     * El decodificador no le inserta otra: la pausa es exigible una vez por jornada, no una vez
     * por replanificacion.
     */
    public boolean unidadPausaCumplida(int i) {
        return unidadPausaCumplida[i];
    }

    /** Punto de la matriz que corresponde a la posicion inicial de la unidad {@code i}. */
    public int puntoUnidad(int i) {
        return cantidadAlmacenes + cantidadPedidos + i;
    }

    /** Indice local de la unidad a partir de su codigo TTNN, o {@code -1} si no esta disponible. */
    public int indiceDeUnidad(String codigo) {
        Integer i = indicePorCodigoUnidad.get(codigo);
        return i == null ? -1 : i;
    }

    // ------------------------------------------------------------- plan previo

    /**
     * Asignacion del plan vigente, de identificador de pedido a codigo de unidad.
     * Alimenta el termino de estabilidad del apartado 11.4 del ISA.
     */
    public Map<Integer, String> asignacionVigente() {
        return asignacionVigente;
    }

    // ------------------------------------------------------------- utilitarios

    /** Minutos de viaje entre dos puntos para el tipo de unidad dado. */
    public int minutosDeViaje(TipoUnidad tipo, int puntoOrigen, int puntoDestino) {
        int km = matriz.km(puntoOrigen, puntoDestino);
        if (km >= MatrizDistancias.INALCANZABLE) {
            return Integer.MAX_VALUE / 4;
        }
        return parametros.minutosDeViaje(tipo, km);
    }

    /** Demanda total pendiente en unidades del producto P. */
    public int demandaTotal() {
        int total = 0;
        for (int q : pedidoCantidad) {
            total += q;
        }
        return total;
    }

    /** Capacidad total instantanea de la flota disponible, en paquetes. */
    public int capacidadFlota() {
        int total = 0;
        for (TipoUnidad t : unidadTipo) {
            total += t.capacidad();
        }
        return total;
    }

    public static Constructor constructor() {
        return new Constructor();
    }

    /** Constructor incremental de la fotografia. */
    public static final class Constructor {
        private long minutoActual;
        private ParametrosOperacion.Instantanea parametros;
        private MatrizDistancias matriz;
        private final List<Almacen> almacenes = new ArrayList<>();
        private final List<Integer> inventarios = new ArrayList<>();
        private final List<Pedido> pedidos = new ArrayList<>();
        private final List<Integer> cantidadesPendientes = new ArrayList<>();
        private final List<UnidadTransporte> unidades = new ArrayList<>();
        private final List<Long> horizontes = new ArrayList<>();
        private final List<Boolean> pausasCumplidas = new ArrayList<>();
        private final Map<Integer, String> asignacionVigente = new HashMap<>();

        public Constructor minutoActual(long minuto) {
            this.minutoActual = minuto;
            return this;
        }

        public Constructor parametros(ParametrosOperacion.Instantanea parametros) {
            this.parametros = parametros;
            return this;
        }

        public Constructor matriz(MatrizDistancias matriz) {
            this.matriz = matriz;
            return this;
        }

        public Constructor almacen(Almacen almacen, int inventarioDisponible) {
            almacenes.add(almacen);
            inventarios.add(inventarioDisponible);
            return this;
        }

        public Constructor pedido(Pedido pedido, int cantidadPendiente) {
            if (cantidadPendiente <= 0) {
                return this;
            }
            pedidos.add(pedido);
            cantidadesPendientes.add(cantidadPendiente);
            return this;
        }

        public Constructor unidad(UnidadTransporte unidad) {
            return unidad(unidad, -1L);
        }

        /**
         * Agrega una unidad fijando de forma explicita el cierre de su horizonte de
         * planificacion. Con {@code -1} se usa el cierre del turno en curso, que es la
         * restriccion dura del apartado 2.6 del ISA; un valor mayor permite estudiar
         * horizontes extendidos sin tocar el resto del planificador.
         */
        public Constructor unidad(UnidadTransporte unidad, long minutoFinHorizonte) {
            return unidad(unidad, minutoFinHorizonte, false);
        }

        /**
         * Agrega una unidad indicando ademas si ya cumplio la pausa de alimentacion del turno en
         * que queda disponible.
         */
        public Constructor unidad(UnidadTransporte unidad, long minutoFinHorizonte, boolean pausaCumplida) {
            unidades.add(unidad);
            horizontes.add(minutoFinHorizonte);
            pausasCumplidas.add(pausaCumplida);
            return this;
        }

        public Constructor asignacionVigente(int idPedido, String codigoUnidad) {
            asignacionVigente.put(idPedido, codigoUnidad);
            return this;
        }

        public InstanciaPlanificacion construir() {
            if (parametros == null) {
                throw new IllegalStateException("La instancia requiere una fotografia de parametros");
            }
            if (matriz == null) {
                throw new IllegalStateException("La instancia requiere una matriz de distancias");
            }
            if (almacenes.isEmpty()) {
                throw new IllegalStateException("La instancia requiere al menos un almacen");
            }
            return new InstanciaPlanificacion(this);
        }
    }
}
