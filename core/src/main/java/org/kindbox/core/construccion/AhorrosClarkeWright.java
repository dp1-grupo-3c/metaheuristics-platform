package org.kindbox.core.construccion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.evaluacion.FuncionObjetivo;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Heuristica de ahorros de Clarke y Wright (1964) en su version paralela, adaptada a PaqRap
 * conforme al apartado 10.1 del ISA.
 *
 * <p>El mecanismo base son los cuatro pasos clasicos. Se arranca con una ruta independiente
 * por visita; se calcula el ahorro {@code s(i,j) = c(i,0) + c(0,j) - c(i,j)} de servir dos
 * visitas de forma consecutiva en lugar de por separado; se ordena la lista de ahorros de
 * mayor a menor; y se recorre esa lista fusionando las rutas de {@code i} y {@code j} cuando
 * no estan ya en la misma ruta, cuando ambas visitas son extremos de su ruta y cuando la
 * fusion no viola ninguna restriccion activa.</p>
 *
 * <h2>Almacen de referencia del par</h2>
 * <p>La formula del ahorro supone un deposito unico y PaqRap tiene tres. La adaptacion que
 * se adopta aqui es tomar, para cada pedido, el almacen <b>mas cercano a el</b> como su
 * almacen de referencia, y evaluar el ahorro con esa referencia:
 * {@code s(i,j) = d(i, dep(i)) + d(dep(j), j) - d(i,j)}. Como todas las calles son de doble
 * sentido la matriz es simetrica y el ahorro asi definido resulta simetrico, de modo que
 * basta generar los pares con {@code i < j} y decidir la orientacion en el momento de
 * fusionar. La eleccion del almacen mas cercano es la unica que no exige conocer de antemano
 * que unidad servira el par: mide la proximidad de dos destinos <em>respecto de la red de
 * almacenes</em>, que es justo lo que el ahorro pretende capturar.</p>
 *
 * <p>El ahorro cumple aqui el papel de <b>criterio de orden</b> y no de criterio de decision.
 * Una ruta de PaqRap arranca en la posicion de su unidad y no cierra regresando a un almacen,
 * de modo que el ahorro geometrico no coincide con el ahorro real de costo. Quien acepta o
 * rechaza cada fusion es {@link ProgramadorRuta}, que devuelve el costo y la factibilidad
 * verdaderos de la secuencia fusionada.</p>
 *
 * <h2>Flota heterogenea</h2>
 * <p>La factibilidad y el costo de una fusion dependen del tipo de unidad que la atenderia,
 * y el tipo no puede fijarse antes de conocer el tamano final de la ruta. La decision de
 * diseno que resuelve la dependencia es evaluar cada fusion candidata sobre el tipo
 * <b>mas barato por kilometro que la admita</b> (bicicleta, luego moto, luego auto) y
 * quedarse con ese tipo. Es coherente con el nivel 2 del objetivo del apartado 2.5, que
 * minimiza kilometros por costo del tipo, y deja que sea el crecimiento de la ruta el que
 * fuerce el ascenso a un tipo mayor.</p>
 *
 * <p>Como el costo por kilometro depende del tipo, un ahorro geometrico positivo puede
 * encarecer el plan: fusionar dos rutas de bicicleta a 3 soles por kilometro en una de auto
 * a 8 soles empeora el nivel 2 aunque acorte el recorrido. Por eso la fusion se confirma
 * comparando el costo real de la ruta fusionada contra la suma de los costos reales de las
 * dos rutas separadas. La unica excepcion es la presion del nivel 1: mientras haya mas rutas
 * que unidades disponibles se acepta cualquier fusion factible, porque una ruta sin unidad
 * manda todos sus pedidos al banco y el nivel 1 domina a cualquier ahorro de costo.</p>
 *
 * <h2>Asignacion de rutas a unidades</h2>
 * <p>Cada unidad arranca en su propia posicion y con su propio instante de disponibilidad,
 * de modo que el costo real de una ruta depende de a quien se le asigne. La asignacion es
 * voraz: las rutas se recorren de mayor a menor exigencia, medida en numero de paradas, y
 * cada una recibe la unidad libre de su tipo cuyo trayecto en vacio hasta la primera parada
 * sea menor. Solo entonces se conocen los instantes reales, de modo que la ruta se
 * <b>reevalua siempre</b> con el decodificador ya con la unidad concreta; si no cabe, se le
 * recortan visitas por la cola hasta que cabe y las recortadas vuelven al banco.</p>
 *
 * <p>Si al asignar no quedan unidades libres del tipo de la ruta se intenta un tipo de mayor
 * capacidad que aun tenga unidades libres; si tampoco lo hay, los pedidos de esa ruta van al
 * banco de no atendidos. Un cierre voraz final reparte lo que quedo en el banco entre las
 * unidades que siguen libres, porque dejar una unidad parada mientras un pedido espera
 * empeora el nivel 1 del objetivo sin ganar nada en el nivel 2.</p>
 *
 * <h2>Restricciones delegadas</h2>
 * <p>Ni las ventanas de tiempo duras, ni el cierre de turno, ni la pausa de alimentacion, ni
 * los abastecimientos intermedios se comprueban recorriendo la ruta: los resuelve entero
 * {@link ProgramadorRuta}, y aqui solo se consulta {@code ultimaFactible()}. Durante la
 * exploracion se usa el camino rapido {@code evaluar(...)}, que no construye objetos y, sobre
 * todo, <b>no consume el inventario</b> de los almacenes intermedios, que es un recurso
 * compartido por todas las rutas del plan; el inventario solo se descuenta al confirmar cada
 * ruta con {@code programar(..., true)}.</p>
 *
 * <h2>Entregas parciales</h2>
 * <p>Un pedido cuya cantidad supera la capacidad de toda unidad no cabe en una sola visita.
 * Antes de arrancar los ahorros cada pedido se parte en el minimo numero de visitas que caben
 * en la mayor capacidad de la flota, con cantidades lo mas parejas posible: partes parejas
 * dejan mas visitas al alcance de los tipos pequenos, que son los baratos. Cada visita es un
 * nodo independiente de la lista de ahorros, de modo que dos partes del mismo pedido, que
 * estan a distancia cero, encabezan la lista y tienden a caer en la misma ruta.</p>
 *
 * <h2>Complejidad y granularidad</h2>
 * <p>El costo esta dominado por el ordenamiento de la lista de ahorros, que es
 * {@code O(n^2 log n)} sobre el numero de visitas. La lista vive en arreglos primitivos y se
 * ordena por clave empaquetada, sin objetos intermedios. Para instancias grandes
 * {@link #conGranularidad(int)} restringe los pares a los geograficamente proximos con
 * {@link MatrizDistancias#vecinosCercanos(int)}, con lo que la lista pasa a ser lineal en el
 * numero de visitas y la heuristica sigue cabiendo en el presupuesto cuando la instancia
 * crece hacia el colapso.</p>
 *
 * <p>Los arreglos de trabajo son campos de instancia y se reutilizan entre llamadas, de modo
 * que la clase <b>no</b> es segura para uso concurrente: cada algoritmo, y cada hilo dentro
 * de el, debe usar la suya.</p>
 */
public final class AhorrosClarkeWright implements HeuristicaConstructiva {

    /** Valor de granularidad que pide la lista completa de pares. */
    public static final int VECINDARIO_COMPLETO = 0;

    /** Vecinos por visita que usa {@link #granular()}, calibrado sobre instancias de centenas de pedidos. */
    public static final int GRANULARIDAD_POR_DEFECTO = 30;

    /**
     * Numero de visitas a partir del cual la lista completa deja de construirse aunque se
     * haya pedido. Con mas visitas los pares crecen por encima del medio millon y ni la
     * memoria ni el ordenamiento caben en un presupuesto de entre 2 y 18 segundos, de modo
     * que se degrada a la variante granular en lugar de arriesgar la ejecucion entera.
     */
    public static final int LIMITE_LISTA_COMPLETA = 1000;

    /** Tolerancia de la comparacion de costos, en soles. */
    private static final double EPSILON_COSTO = 1e-9;

    /** Tipos de unidad de menor a mayor costo por kilometro. Fija el orden de prueba de una fusion. */
    private static final TipoUnidad[] TIPOS_POR_COSTO = tiposPorCosto();

    /** Tipos de unidad de menor a mayor capacidad. Fija el orden de ascenso al reasignar. */
    private static final TipoUnidad[] TIPOS_POR_CAPACIDAD = tiposPorCapacidad();

    /** Tipos de unidad en el orden del enumerado, cacheados para no clonar en cada consulta. */
    private static final TipoUnidad[] TIPOS = TipoUnidad.values();

    private static final int CANTIDAD_TIPOS = TIPOS.length;

    private final int granularidad;
    private final double ruido;
    private final String nombre;
    private final FuncionObjetivo objetivo = new FuncionObjetivoJerarquica();

    // Visitas: un pedido se parte en tantas como exijan las entregas parciales.
    private int[] visitaPedido = new int[0];
    private int[] visitaCantidad = new int[0];
    private boolean[] visitaServida = new boolean[0];
    private boolean[] visitaDescartada = new boolean[0];
    private int[] primeraVisitaDePedido = new int[0];
    private int[] visitasDePedido = new int[0];
    private int[] kmAlmacenDePedido = new int[0];
    private int[] entregadoDePedido = new int[0];

    // Rutas en construccion, encadenadas sobre las visitas.
    private int[] siguienteVisita = new int[0];
    private int[] rutaDeVisita = new int[0];
    private int[] primeraDeRuta = new int[0];
    private int[] ultimaDeRuta = new int[0];
    private int[] paradasDeRuta = new int[0];
    private int[] tipoDeRuta = new int[0];
    private double[] costoDeRuta = new double[0];
    private boolean[] activaDeRuta = new boolean[0];
    private long[] ordenDeRuta = new long[0];

    // Secuencia de trabajo que se entrega al decodificador.
    private int[] secuenciaPedido = new int[0];
    private int[] secuenciaCantidad = new int[0];
    private int[] secuenciaVisita = new int[0];

    // Lista de ahorros.
    private int[] parPrimero = new int[0];
    private int[] parSegundo = new int[0];
    private int[] ahorroDePar = new int[0];
    private long[] claveDePar = new long[0];
    private long[] paresNormalizados = new long[0];

    // Flota.
    private final int[] prototipoDeTipo = new int[CANTIDAD_TIPOS];
    private final long[] ventanaDeTipo = new long[CANTIDAD_TIPOS];
    private final int[] unidadesDeTipo = new int[CANTIDAD_TIPOS];
    private boolean[] unidadOcupada = new boolean[0];

    private int capacidadMaxima;
    private int maximoVisitasPorPedido;
    private int limiteParadas;
    private int rutasActivas;
    private int visitasPendientes;

    // Resultado de la ultima eleccion de tipo, para no devolver objetos desde el bucle caliente.
    private int tipoElegido;
    private boolean factibleElegido;
    private double costoElegido;

    /** Heuristica sobre la lista completa de pares, sin ruido de desempate. */
    public AhorrosClarkeWright() {
        this(VECINDARIO_COMPLETO, 0.0);
    }

    /**
     * @param granularidad vecinos por visita que entran en la lista de ahorros, o
     *                     {@link #VECINDARIO_COMPLETO} para la lista completa
     * @param ruido        amplitud relativa de la perturbacion aleatoria del orden de los
     *                     ahorros, en {@code [0,1)}; con {@code 0} la heuristica es
     *                     determinista y reproduce el Clarke y Wright clasico
     */
    public AhorrosClarkeWright(int granularidad, double ruido) {
        if (granularidad < 0) {
            throw new IllegalArgumentException("Granularidad negativa: " + granularidad);
        }
        if (ruido < 0.0 || ruido >= 1.0) {
            throw new IllegalArgumentException("Ruido fuera de [0,1): " + ruido);
        }
        this.granularidad = granularidad;
        this.ruido = ruido;
        this.nombre = componerNombre(granularidad, ruido);
    }

    /** Variante sobre la lista completa de pares. Es el Clarke y Wright paralelo clasico. */
    public static AhorrosClarkeWright completo() {
        return new AhorrosClarkeWright(VECINDARIO_COMPLETO, 0.0);
    }

    /**
     * Variante que limita los ahorros a los pares geograficamente proximos, con la
     * granularidad por defecto.
     */
    public static AhorrosClarkeWright granular() {
        return new AhorrosClarkeWright(GRANULARIDAD_POR_DEFECTO, 0.0);
    }

    /**
     * Variante que limita los ahorros a los pares geograficamente proximos.
     *
     * <p>Para cada visita se toman sus {@code granularidad} destinos de pedido mas cercanos
     * segun {@link MatrizDistancias#vecinosCercanos(int)} y solo esos pares entran en la
     * lista, mas los pares entre visitas de un mismo pedido, que estan a distancia cero y no
     * aparecen en el vecindario porque comparten punto. La lista pasa de cuadratica a lineal
     * en el numero de visitas, que es lo que permite seguir construyendo dentro del
     * presupuesto cuando la instancia crece hacia el colapso. El recorte no cambia el
     * resultado en la practica: un par lejano tiene ahorro bajo o negativo y nunca llega a
     * fusionarse.</p>
     *
     * @param granularidad vecinos por visita, mayor que cero
     */
    public static AhorrosClarkeWright conGranularidad(int granularidad) {
        if (granularidad <= 0) {
            throw new IllegalArgumentException("La granularidad debe ser positiva: " + granularidad);
        }
        return new AhorrosClarkeWright(granularidad, 0.0);
    }

    /**
     * Variante aleatorizada: el orden de los ahorros se perturba con el generador de la
     * corrida, de modo que llamadas sucesivas produzcan planes distintos pero igual de
     * razonables. Es lo que necesita la poblacion inicial de la busqueda genetica hibrida
     * del apartado 6.3.1, que no puede partir de un unico individuo repetido.
     *
     * @param granularidad vecinos por visita, o {@link #VECINDARIO_COMPLETO}
     * @param ruido        amplitud relativa de la perturbacion, en {@code [0,1)}
     */
    public static AhorrosClarkeWright conRuido(int granularidad, double ruido) {
        return new AhorrosClarkeWright(granularidad, ruido);
    }

    /** Vecinos por visita que entran en la lista de ahorros, o {@link #VECINDARIO_COMPLETO}. */
    public int granularidad() {
        return granularidad;
    }

    /** Amplitud relativa de la perturbacion aleatoria del orden de los ahorros. */
    public double ruido() {
        return ruido;
    }

    @Override
    public String nombre() {
        return nombre;
    }

    @Override
    public Solucion construir(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                              Aleatorio aleatorio) {
        return construir(instancia, programador, aleatorio, null);
    }

    /**
     * Construccion sometida a un presupuesto de reloj de pared.
     *
     * <p>El presupuesto se consulta en el bucle de fusion, que es donde se concentra el
     * trabajo y el unico tramo cuyo costo crece de forma apreciable con el tamano de la
     * instancia. Al agotarse se interrumpe la fusion y se pasa directamente a la asignacion,
     * de modo que lo devuelto es siempre un plan completo: en el peor caso el de las rutas
     * unitarias de la inicializacion, que ya es valido aunque caro. Los pasos previos, que
     * son el ordenamiento de los ahorros, no se interrumpen porque sin la lista ordenada no
     * hay nada que fusionar.</p>
     */
    @Override
    public Solucion construir(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                              Aleatorio aleatorio, PresupuestoComputo presupuesto) {
        programador.reiniciarInventarios();
        if (instancia.cantidadPedidos() == 0 || instancia.cantidadUnidades() == 0) {
            return Solucion.vacia(instancia);
        }

        prepararFlota(instancia);
        if (capacidadMaxima <= 0) {
            return Solucion.vacia(instancia);
        }

        int visitas = partirEnVisitas(instancia);
        if (visitas == 0) {
            return Solucion.vacia(instancia);
        }

        inicializarRutas(instancia, programador, visitas);
        int pares = construirAhorros(instancia, visitas, aleatorio);
        fusionar(instancia, programador, pares, presupuesto);
        return asignarYConfirmar(instancia, programador, visitas);
    }

    // ------------------------------------------------------------------- flota

    /**
     * Calcula, por tipo de unidad, cuantas unidades hay y cual sirve de prototipo para
     * valorar las fusiones antes de conocer la unidad concreta.
     *
     * <p>El prototipo es la unidad del tipo con mayor ventana de operacion, es decir con mas
     * minutos entre su disponibilidad y el cierre de su turno. Es la eleccion optimista: una
     * fusion que ni siquiera cabe en la unidad mas holgada de su tipo no cabe en ninguna, y
     * las que caben en el prototipo pero no en la unidad que finalmente se les asigne quedan
     * cubiertas por la reevaluacion y el recorte de la fase de asignacion. La alternativa
     * pesimista rechazaria fusiones viables y dejaria mas rutas que unidades, que es
     * exactamente lo que castiga el nivel 1 del objetivo.</p>
     */
    private void prepararFlota(InstanciaPlanificacion instancia) {
        Arrays.fill(prototipoDeTipo, -1);
        Arrays.fill(ventanaDeTipo, 0L);
        Arrays.fill(unidadesDeTipo, 0);

        final int unidades = instancia.cantidadUnidades();
        if (unidadOcupada.length < unidades) {
            unidadOcupada = new boolean[unidades];
        }
        Arrays.fill(unidadOcupada, 0, unidades, false);

        long ventanaMaxima = 0;
        for (int u = 0; u < unidades; u++) {
            int tipo = instancia.unidadTipo(u).ordinal();
            unidadesDeTipo[tipo]++;
            long ventana = instancia.unidadMinutoFinTurno(u) - instancia.unidadMinutoDisponible(u);
            if (prototipoDeTipo[tipo] < 0 || ventana > ventanaDeTipo[tipo]) {
                prototipoDeTipo[tipo] = u;
                ventanaDeTipo[tipo] = ventana;
            }
            if (ventana > ventanaMaxima) {
                ventanaMaxima = ventana;
            }
        }

        capacidadMaxima = 0;
        for (TipoUnidad tipo : TIPOS_POR_COSTO) {
            if (unidadesDeTipo[tipo.ordinal()] > 0 && tipo.capacidad() > capacidadMaxima) {
                capacidadMaxima = tipo.capacidad();
            }
        }

        // Cota superior de paradas por ruta: cada entrega consume el acondicionamiento
        // completo, de modo que ninguna ruta cabe mas veces que eso en su ventana. Poda las
        // fusiones largas sin llegar a llamar al decodificador.
        int servicio = Math.max(1, instancia.parametros().minutosAcondicionamiento());
        limiteParadas = (int) Math.max(1L, ventanaMaxima / servicio);
    }

    // ---------------------------------------------------------------- visitas

    /**
     * Parte cada pedido en el minimo numero de visitas que caben en la mayor capacidad de la
     * flota y reparte la cantidad de la forma mas pareja posible entre ellas.
     *
     * <p>Un pedido de cantidad menor o igual que esa capacidad produce una sola visita, de
     * modo que el caso corriente no paga nada. El reparto parejo, y no el llenado codicioso,
     * es deliberado: dos partes de 15 caben en un auto igual que 24 y 6, pero un reparto
     * parejo baja mas veces por debajo de la capacidad de moto o de bicicleta, que son los
     * tipos baratos del nivel 2 del objetivo.</p>
     *
     * @return numero de visitas generadas
     */
    private int partirEnVisitas(InstanciaPlanificacion instancia) {
        final int pedidos = instancia.cantidadPedidos();
        int total = 0;
        for (int p = 0; p < pedidos; p++) {
            total += (instancia.pedidoCantidad(p) + capacidadMaxima - 1) / capacidadMaxima;
        }
        asegurarCapacidadVisitas(total, pedidos);

        maximoVisitasPorPedido = 1;
        int v = 0;
        for (int p = 0; p < pedidos; p++) {
            int cantidad = instancia.pedidoCantidad(p);
            int partes = (cantidad + capacidadMaxima - 1) / capacidadMaxima;
            int base = cantidad / partes;
            int resto = cantidad % partes;
            primeraVisitaDePedido[p] = v;
            visitasDePedido[p] = partes;
            if (partes > maximoVisitasPorPedido) {
                maximoVisitasPorPedido = partes;
            }
            entregadoDePedido[p] = 0;
            kmAlmacenDePedido[p] = distanciaAlAlmacenMasCercano(instancia, p);
            for (int k = 0; k < partes; k++) {
                visitaPedido[v] = p;
                visitaCantidad[v] = base + (k < resto ? 1 : 0);
                visitaServida[v] = false;
                v++;
            }
        }
        visitasPendientes = v;
        return v;
    }

    /**
     * Distancia del destino del pedido a su almacen de referencia, que es el mas cercano de
     * los tres. Si ningun almacen es alcanzable con los bloqueos vigentes se devuelve cero,
     * con lo que el pedido aporta al ahorro solo su distancia al otro extremo del par: el
     * pedido sigue siendo servible por una unidad que ya lleve carga a bordo, de modo que
     * excluirlo de la lista seria peor que dejarlo con ahorro conservador.
     */
    private int distanciaAlAlmacenMasCercano(InstanciaPlanificacion instancia, int pedido) {
        MatrizDistancias matriz = instancia.matriz();
        int punto = instancia.puntoPedido(pedido);
        int mejor = MatrizDistancias.INALCANZABLE;
        for (int a = 0; a < instancia.cantidadAlmacenes(); a++) {
            int km = matriz.km(punto, instancia.puntoAlmacen(a));
            if (km < mejor) {
                mejor = km;
            }
        }
        return mejor >= MatrizDistancias.INALCANZABLE ? 0 : mejor;
    }

    // ----------------------------------------------------- paso 1: una ruta por visita

    /**
     * Paso 1 del mecanismo: una ruta independiente por visita, ya evaluada con el tipo mas
     * barato que la admite. La ruta que ningun tipo puede recorrer, por ejemplo por tener el
     * destino aislado por los bloqueos, nace inactiva y su visita queda para el banco.
     */
    private void inicializarRutas(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                                  int visitas) {
        rutasActivas = 0;
        for (int v = 0; v < visitas; v++) {
            siguienteVisita[v] = -1;
            rutaDeVisita[v] = v;
            primeraDeRuta[v] = v;
            ultimaDeRuta[v] = v;
            paradasDeRuta[v] = 1;

            secuenciaPedido[0] = visitaPedido[v];
            secuenciaCantidad[0] = visitaCantidad[v];
            elegirTipo(programador, 1);

            tipoDeRuta[v] = tipoElegido;
            costoDeRuta[v] = costoElegido;
            activaDeRuta[v] = tipoElegido >= 0;
            if (activaDeRuta[v]) {
                rutasActivas++;
            }
        }
    }

    /**
     * Elige el tipo de unidad con el que se atendera la secuencia de trabajo: el mas barato
     * por kilometro que la admita. Deja el resultado en {@link #tipoElegido},
     * {@link #factibleElegido} y {@link #costoElegido}.
     *
     * <p>Se recorren los tipos de menor a mayor costo por kilometro y se devuelve el primero
     * que produce una programacion factible. Si ninguno lo consigue se conserva el primero
     * que al menos admite la secuencia, con su costo: sirve para las rutas de una sola visita,
     * que deben seguir vivas hasta la fase de asignacion porque una unidad concreta bien
     * situada puede volverlas factibles aunque el prototipo de su tipo no lo lograse. Las
     * fusiones, en cambio, exigen factibilidad y descartan ese caso.</p>
     */
    private void elegirTipo(ProgramadorRuta programador, int longitud) {
        tipoElegido = -1;
        factibleElegido = false;
        costoElegido = 0.0;
        int reserva = -1;
        double costoReserva = 0.0;

        for (TipoUnidad tipo : TIPOS_POR_COSTO) {
            int prototipo = prototipoDeTipo[tipo.ordinal()];
            if (prototipo < 0 || excedeCapacidad(tipo.capacidad(), longitud)) {
                continue;
            }
            if (!programador.evaluar(prototipo, secuenciaPedido, secuenciaCantidad, longitud)) {
                continue;
            }
            if (programador.ultimaFactible()) {
                tipoElegido = tipo.ordinal();
                factibleElegido = true;
                costoElegido = programador.ultimoCosto();
                return;
            }
            if (reserva < 0) {
                reserva = tipo.ordinal();
                costoReserva = programador.ultimoCosto();
            }
        }

        if (reserva >= 0) {
            tipoElegido = reserva;
            costoElegido = costoReserva;
        }
    }

    /** Indica si alguna visita de la secuencia de trabajo no cabe de una vez en la capacidad dada. */
    private boolean excedeCapacidad(int capacidad, int longitud) {
        for (int i = 0; i < longitud; i++) {
            if (secuenciaCantidad[i] > capacidad) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------- pasos 2 y 3: ahorros y ordenamiento

    /**
     * Pasos 2 y 3: calcula el ahorro de cada par admisible y ordena la lista de mayor a
     * menor.
     *
     * <p>El ordenamiento es por clave empaquetada en un entero largo, con el ahorro negado en
     * los 32 bits altos y el indice del par en los bajos, de modo que un unico
     * {@code Arrays.sort} sobre primitivos deja la lista en orden descendente de ahorro y con
     * los empates resueltos por indice, que es reproducible. No interviene ningun objeto ni
     * ningun comparador.</p>
     *
     * @return numero de pares de la lista
     */
    private int construirAhorros(InstanciaPlanificacion instancia, int visitas, Aleatorio aleatorio) {
        int pares = granularidad > 0 || visitas > LIMITE_LISTA_COMPLETA
                ? generarParesProximos(instancia, visitas)
                : generarParesCompletos(instancia, visitas);

        final MatrizDistancias matriz = instancia.matriz();
        int ahorroMaximo = 0;
        for (int k = 0; k < pares; k++) {
            int i = parPrimero[k];
            int j = parSegundo[k];
            int km = matriz.km(instancia.puntoPedido(visitaPedido[i]), instancia.puntoPedido(visitaPedido[j]));
            int ahorro = kmAlmacenDePedido[visitaPedido[i]] + kmAlmacenDePedido[visitaPedido[j]] - km;
            ahorroDePar[k] = ahorro;
            if (ahorro > ahorroMaximo) {
                ahorroMaximo = ahorro;
            }
        }

        for (int k = 0; k < pares; k++) {
            int clave = ahorroDePar[k];
            if (ruido > 0.0 && ahorroMaximo > 0) {
                // Perturbacion tipo GRASP: rebaja cada ahorro una fraccion aleatoria del mayor
                // de la lista, con lo que el orden cambia entre corridas sin dejar de ser
                // razonable. Solo altera el orden de prueba; la aceptacion sigue siendo por costo.
                clave -= (int) (ruido * ahorroMaximo * aleatorio.siguienteDouble());
            }
            claveDePar[k] = ((long) (-clave) << 32) | k;
        }
        Arrays.sort(claveDePar, 0, pares);
        return pares;
    }

    /** Lista completa: todos los pares de visitas con camino entre ellas. */
    private int generarParesCompletos(InstanciaPlanificacion instancia, int visitas) {
        asegurarCapacidadPares(visitas * (visitas - 1) / 2);
        final MatrizDistancias matriz = instancia.matriz();
        int pares = 0;
        for (int i = 0; i < visitas; i++) {
            int puntoI = instancia.puntoPedido(visitaPedido[i]);
            for (int j = i + 1; j < visitas; j++) {
                if (matriz.km(puntoI, instancia.puntoPedido(visitaPedido[j])) >= MatrizDistancias.INALCANZABLE) {
                    continue;
                }
                parPrimero[pares] = i;
                parSegundo[pares] = j;
                pares++;
            }
        }
        return pares;
    }

    /**
     * Lista restringida a los pares geograficamente proximos. Los pares se normalizan con la
     * visita menor primero, se ordenan y se depuran en el sitio, porque el vecindario no es
     * simetrico y un mismo par puede aparecer desde los dos extremos.
     */
    private int generarParesProximos(InstanciaPlanificacion instancia, int visitas) {
        final int vecinos = granularidad > 0 ? granularidad : GRANULARIDAD_POR_DEFECTO;
        final int almacenes = instancia.cantidadAlmacenes();
        final int cantidadPedidos = instancia.cantidadPedidos();
        final MatrizDistancias matriz = instancia.matriz();

        // Cota del numero de pares generados antes de depurar: cada visita se empareja con
        // las demas visitas de su propio pedido y con todas las visitas de cada uno de sus
        // vecinos. Se calcula en aritmetica larga porque el producto desborda un entero en
        // instancias con muchas entregas parciales.
        long cota = (long) visitas * (vecinos + 1) * maximoVisitasPorPedido + 1L;
        if (cota > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("La lista de pares proximos no cabe en memoria: " + cota);
        }
        if (paresNormalizados.length < cota) {
            paresNormalizados = new long[(int) cota];
        }

        int generados = 0;
        for (int i = 0; i < visitas; i++) {
            int pedido = visitaPedido[i];
            // Las visitas de un mismo pedido comparten punto y no aparecen en su propio
            // vecindario, de modo que hay que emparejarlas de forma explicita.
            int fin = primeraVisitaDePedido[pedido] + visitasDePedido[pedido];
            for (int j = i + 1; j < fin; j++) {
                paresNormalizados[generados++] = ((long) i << 32) | j;
            }
            int tomados = 0;
            int[] cercanos = matriz.vecinosCercanos(instancia.puntoPedido(pedido));
            for (int c = 0; c < cercanos.length && tomados < vecinos; c++) {
                int otroPedido = cercanos[c] - almacenes;
                if (otroPedido < 0 || otroPedido >= cantidadPedidos || otroPedido == pedido) {
                    continue;
                }
                tomados++;
                int desde = primeraVisitaDePedido[otroPedido];
                int hasta = desde + visitasDePedido[otroPedido];
                for (int j = desde; j < hasta; j++) {
                    int menor = Math.min(i, j);
                    int mayor = Math.max(i, j);
                    paresNormalizados[generados++] = ((long) menor << 32) | mayor;
                }
            }
        }

        Arrays.sort(paresNormalizados, 0, generados);
        asegurarCapacidadPares(generados);
        int pares = 0;
        long anterior = -1L;
        for (int k = 0; k < generados; k++) {
            long par = paresNormalizados[k];
            if (par == anterior) {
                continue;
            }
            anterior = par;
            int i = (int) (par >>> 32);
            int j = (int) par;
            if (matriz.km(instancia.puntoPedido(visitaPedido[i]),
                    instancia.puntoPedido(visitaPedido[j])) >= MatrizDistancias.INALCANZABLE) {
                continue;
            }
            parPrimero[pares] = i;
            parSegundo[pares] = j;
            pares++;
        }
        return pares;
    }

    // --------------------------------------------------- paso 4: fusion iterativa

    /**
     * Paso 4: recorre la lista de ahorros de mayor a menor y fusiona lo que puede.
     *
     * <p>Se rechaza sin llamar al decodificador el par cuyas visitas ya comparten ruta, el
     * que no deja las dos visitas como extremos contiguos, el que superaria la cota de
     * paradas por turno y, mientras no haya presion de flota, el de ahorro no positivo. Lo
     * que sobrevive se programa de verdad, primero en la orientacion que respeta el sentido
     * de las dos rutas y luego en la contraria.</p>
     */
    private void fusionar(InstanciaPlanificacion instancia, ProgramadorRuta programador, int pares,
                          PresupuestoComputo presupuesto) {
        final int unidades = instancia.cantidadUnidades();
        for (int k = 0; k < pares; k++) {
            // Consultar el reloj cuesta una llamada al sistema, de modo que se hace cada 256
            // pares y no en cada uno. Con decenas de miles de pares la granularidad sobra.
            if (presupuesto != null && (k & 0xFF) == 0 && presupuesto.agotado()) {
                return;
            }
            int par = (int) claveDePar[k];
            int i = parPrimero[par];
            int j = parSegundo[par];
            int rutaI = rutaDeVisita[i];
            int rutaJ = rutaDeVisita[j];
            if (rutaI == rutaJ || !activaDeRuta[rutaI] || !activaDeRuta[rutaJ]) {
                continue;
            }
            if (paradasDeRuta[rutaI] + paradasDeRuta[rutaJ] > limiteParadas) {
                continue;
            }
            // Sin presion de flota un ahorro no positivo no llega a compensar el desvio, de
            // modo que ni se programa. Con presion si se intenta: el nivel 1 del objetivo
            // domina y una ruta de mas es una ruta que puede quedarse sin unidad.
            boolean presion = rutasActivas > unidades;
            if (ahorroDePar[par] <= 0 && !presion) {
                continue;
            }

            boolean fusionada = false;
            if (ultimaDeRuta[rutaI] == i && primeraDeRuta[rutaJ] == j) {
                fusionada = intentarFusion(instancia, programador, rutaI, rutaJ, presion);
            }
            if (!fusionada && ultimaDeRuta[rutaJ] == j && primeraDeRuta[rutaI] == i) {
                intentarFusion(instancia, programador, rutaJ, rutaI, presion);
            }
        }
    }

    /**
     * Programa la concatenacion de dos rutas y la confirma si procede.
     *
     * <p>La fusion se acepta cuando existe un tipo que la recorre de forma factible y ademas
     * su costo real no supera la suma de los costos de las dos rutas separadas. Bajo presion
     * de flota se acepta cualquier fusion factible, porque el nivel 1 del objetivo domina: una
     * ruta que se queda sin unidad manda todos sus pedidos al banco.</p>
     *
     * @return {@code true} si las dos rutas quedaron fusionadas en {@code rutaCabeza}
     */
    private boolean intentarFusion(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                                   int rutaCabeza, int rutaCola, boolean presion) {
        int longitud = volcarSecuencia(rutaCabeza, 0);
        longitud = volcarSecuencia(rutaCola, longitud);
        elegirTipo(programador, longitud);
        if (!factibleElegido) {
            return false;
        }
        if (!presion && costoElegido > costoDeRuta[rutaCabeza] + costoDeRuta[rutaCola] + EPSILON_COSTO) {
            return false;
        }

        for (int v = primeraDeRuta[rutaCola]; v >= 0; v = siguienteVisita[v]) {
            rutaDeVisita[v] = rutaCabeza;
        }
        siguienteVisita[ultimaDeRuta[rutaCabeza]] = primeraDeRuta[rutaCola];
        ultimaDeRuta[rutaCabeza] = ultimaDeRuta[rutaCola];
        paradasDeRuta[rutaCabeza] += paradasDeRuta[rutaCola];
        tipoDeRuta[rutaCabeza] = tipoElegido;
        costoDeRuta[rutaCabeza] = costoElegido;
        activaDeRuta[rutaCola] = false;
        rutasActivas--;
        return true;
    }

    /**
     * Vuelca la ruta en la secuencia de trabajo a partir de la posicion dada.
     *
     * @return nueva longitud de la secuencia
     */
    private int volcarSecuencia(int ruta, int desde) {
        int n = desde;
        for (int v = primeraDeRuta[ruta]; v >= 0; v = siguienteVisita[v]) {
            secuenciaPedido[n] = visitaPedido[v];
            secuenciaCantidad[n] = visitaCantidad[v];
            secuenciaVisita[n] = v;
            n++;
        }
        return n;
    }

    // ------------------------------------------------ asignacion a unidades concretas

    /**
     * Asigna cada ruta a una unidad concreta, la reevalua con esa unidad y confirma el plan.
     *
     * <p>Las rutas se recorren de mayor a menor numero de paradas, que es su exigencia: la
     * ruta larga tiene menos unidades capaces de asumirla, de modo que elegir primero por ella
     * evita quedarse sin margen. Cada ruta toma la unidad libre de su tipo cuyo trayecto en
     * vacio hasta la primera parada sea menor, y solo entonces se conocen los instantes
     * reales, de modo que se vuelve a programar siempre.</p>
     */
    private Solucion asignarYConfirmar(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                                       int visitas) {
        int activas = 0;
        for (int r = 0; r < visitas; r++) {
            if (activaDeRuta[r]) {
                ordenDeRuta[activas++] = ((long) (Integer.MAX_VALUE - paradasDeRuta[r]) << 32) | r;
            }
        }
        Arrays.sort(ordenDeRuta, 0, activas);

        List<Ruta> rutas = new ArrayList<>(activas);
        for (int k = 0; k < activas; k++) {
            int ruta = (int) ordenDeRuta[k];
            int unidad = elegirUnidad(instancia, tipoDeRuta[ruta], primeraDeRuta[ruta]);
            if (unidad < 0) {
                continue;
            }
            int longitud = volcarSecuencia(ruta, 0);
            longitud = recortarHastaFactible(programador, unidad, longitud);
            if (longitud == 0) {
                continue;
            }
            confirmar(programador, rutas, unidad, longitud);
        }

        cerrarConUnidadesLibres(instancia, programador, rutas, visitas);
        return componerSolucion(instancia, rutas);
    }

    /**
     * Unidad libre que atendera la ruta. Se prefiere la del tipo elegido durante la fusion
     * con el menor trayecto en vacio hasta la primera parada; si ese tipo se agoto se asciende
     * al primer tipo de mas capacidad que aun tenga unidades libres, y si tampoco lo hay la
     * ruta se queda sin unidad y sus pedidos van al banco.
     */
    private int elegirUnidad(InstanciaPlanificacion instancia, int tipo, int primeraVisita) {
        if (tipo < 0) {
            return -1;
        }
        int unidad = unidadMasCercana(instancia, tipo, primeraVisita);
        if (unidad >= 0) {
            return unidad;
        }
        int capacidad = TIPOS[tipo].capacidad();
        for (TipoUnidad candidato : TIPOS_POR_CAPACIDAD) {
            if (candidato.capacidad() <= capacidad) {
                continue;
            }
            unidad = unidadMasCercana(instancia, candidato.ordinal(), primeraVisita);
            if (unidad >= 0) {
                return unidad;
            }
        }
        return -1;
    }

    /** Unidad libre del tipo con el menor trayecto en vacio hasta la primera parada de la ruta. */
    private int unidadMasCercana(InstanciaPlanificacion instancia, int tipo, int primeraVisita) {
        final MatrizDistancias matriz = instancia.matriz();
        final int destino = instancia.puntoPedido(visitaPedido[primeraVisita]);
        int elegida = -1;
        int mejorKm = MatrizDistancias.INALCANZABLE;
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            if (unidadOcupada[u] || instancia.unidadTipo(u).ordinal() != tipo) {
                continue;
            }
            int km = matriz.km(instancia.puntoUnidad(u), destino);
            if (km < mejorKm) {
                mejorKm = km;
                elegida = u;
            }
        }
        return elegida;
    }

    /**
     * Recorta visitas por la cola de la secuencia de trabajo hasta que la unidad concreta la
     * recorre de forma factible. Es la reparacion que hace honesta la fase de fusion: alli las
     * fusiones se valoraron sobre el prototipo optimista del tipo, y una unidad real puede
     * arrancar mas tarde o mas lejos. Las visitas recortadas vuelven al banco y el cierre
     * voraz posterior aun puede recuperarlas.
     *
     * @return longitud factible de la secuencia, cero si no queda ninguna
     */
    private int recortarHastaFactible(ProgramadorRuta programador, int unidad, int longitud) {
        while (longitud > 0) {
            if (programador.evaluar(unidad, secuenciaPedido, secuenciaCantidad, longitud)
                    && programador.ultimaFactible()) {
                return longitud;
            }
            longitud--;
        }
        return 0;
    }

    /**
     * Programa de verdad la secuencia de trabajo sobre la unidad, consumiendo ya el inventario
     * de los almacenes intermedios, y anota lo entregado.
     */
    private void confirmar(ProgramadorRuta programador, List<Ruta> rutas, int unidad, int longitud) {
        Programacion programacion = programador.programar(
                unidad, secuenciaPedido, secuenciaCantidad, longitud, true);
        if (programacion.sinProgramacion() || !programacion.factible()) {
            return;
        }
        rutas.add(programacion.ruta());
        unidadOcupada[unidad] = true;
        for (int i = 0; i < longitud; i++) {
            entregadoDePedido[secuenciaPedido[i]] += secuenciaCantidad[i];
            visitaServida[secuenciaVisita[i]] = true;
            visitasPendientes--;
        }
    }

    /**
     * Cierre voraz: reparte entre las unidades que siguen libres las visitas que quedaron sin
     * atender, sea porque su ruta no encontro unidad o porque hubo que recortarla.
     *
     * <p>Cada unidad libre, de menor a mayor costo por kilometro, encadena la visita pendiente
     * mas cercana a su posicion actual mientras el decodificador la declare factible, y
     * abandona una visita en cuanto no cabe. Es una construccion por vecino mas cercano y no
     * un segundo Clarke y Wright, porque a esta altura lo que queda son restos dispersos y lo
     * unico que importa es el nivel 1 del objetivo: dejar una unidad parada mientras un pedido
     * espera no ahorra nada, porque una unidad sin ruta no recorre kilometros.</p>
     */
    private void cerrarConUnidadesLibres(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                                         List<Ruta> rutas, int visitas) {
        if (visitasPendientes <= 0) {
            return;
        }
        final MatrizDistancias matriz = instancia.matriz();
        for (TipoUnidad tipo : TIPOS_POR_COSTO) {
            final int capacidad = tipo.capacidad();
            for (int u = 0; u < instancia.cantidadUnidades() && visitasPendientes > 0; u++) {
                if (unidadOcupada[u] || instancia.unidadTipo(u) != tipo) {
                    continue;
                }
                Arrays.fill(visitaDescartada, 0, visitas, false);
                int punto = instancia.puntoUnidad(u);
                int longitud = 0;
                while (longitud < limiteParadas) {
                    int mejor = -1;
                    int mejorKm = MatrizDistancias.INALCANZABLE;
                    for (int v = 0; v < visitas; v++) {
                        if (visitaServida[v] || visitaDescartada[v] || visitaCantidad[v] > capacidad) {
                            continue;
                        }
                        int km = matriz.km(punto, instancia.puntoPedido(visitaPedido[v]));
                        if (km < mejorKm) {
                            mejorKm = km;
                            mejor = v;
                        }
                    }
                    if (mejor < 0) {
                        break;
                    }
                    secuenciaPedido[longitud] = visitaPedido[mejor];
                    secuenciaCantidad[longitud] = visitaCantidad[mejor];
                    secuenciaVisita[longitud] = mejor;
                    if (programador.evaluar(u, secuenciaPedido, secuenciaCantidad, longitud + 1)
                            && programador.ultimaFactible()) {
                        punto = instancia.puntoPedido(visitaPedido[mejor]);
                        visitaDescartada[mejor] = true;
                        longitud++;
                    } else {
                        // Lo que no cabe ahora tampoco cabra con la ruta ya mas larga.
                        visitaDescartada[mejor] = true;
                    }
                }
                if (longitud > 0) {
                    confirmar(programador, rutas, u, longitud);
                }
            }
        }
    }

    /**
     * Compone el plan: las rutas confirmadas y el banco con el remanente de cada pedido que
     * no llego a entregarse. El valor objetivo lo calcula la funcion jerarquica compartida,
     * de modo que la heuristica no puede discrepar de la evaluacion que hara el algoritmo que
     * la consuma.
     */
    private Solucion componerSolucion(InstanciaPlanificacion instancia, List<Ruta> rutas) {
        Map<Integer, Integer> banco = new LinkedHashMap<>();
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            int remanente = instancia.pedidoCantidad(p) - entregadoDePedido[p];
            if (remanente > 0) {
                banco.put(instancia.pedidoId(p), remanente);
            }
        }
        Solucion solucion = new Solucion(rutas, banco, ValorObjetivo.cero());
        return solucion.conValor(objetivo.evaluar(instancia, solucion));
    }

    // -------------------------------------------------------------- arreglos

    /** Redimensiona los arreglos por visita y por pedido si la instancia crecio. */
    private void asegurarCapacidadVisitas(int visitas, int pedidos) {
        if (visitaPedido.length < visitas) {
            visitaPedido = new int[visitas];
            visitaCantidad = new int[visitas];
            visitaServida = new boolean[visitas];
            visitaDescartada = new boolean[visitas];
            siguienteVisita = new int[visitas];
            rutaDeVisita = new int[visitas];
            primeraDeRuta = new int[visitas];
            ultimaDeRuta = new int[visitas];
            paradasDeRuta = new int[visitas];
            tipoDeRuta = new int[visitas];
            costoDeRuta = new double[visitas];
            activaDeRuta = new boolean[visitas];
            ordenDeRuta = new long[visitas];
            secuenciaPedido = new int[visitas];
            secuenciaCantidad = new int[visitas];
            secuenciaVisita = new int[visitas];
        }
        if (primeraVisitaDePedido.length < pedidos) {
            primeraVisitaDePedido = new int[pedidos];
            visitasDePedido = new int[pedidos];
            kmAlmacenDePedido = new int[pedidos];
            entregadoDePedido = new int[pedidos];
        }
    }

    /** Redimensiona los arreglos de la lista de ahorros si la instancia crecio. */
    private void asegurarCapacidadPares(int pares) {
        if (parPrimero.length < pares) {
            parPrimero = new int[pares];
            parSegundo = new int[pares];
            ahorroDePar = new int[pares];
            claveDePar = new long[pares];
        }
    }

    // ---------------------------------------------------------------- estatico

    /** Tipos de unidad ordenados de menor a mayor costo por kilometro. */
    private static TipoUnidad[] tiposPorCosto() {
        TipoUnidad[] tipos = TipoUnidad.values().clone();
        for (int i = 1; i < tipos.length; i++) {
            TipoUnidad actual = tipos[i];
            int j = i - 1;
            while (j >= 0 && tipos[j].costoPorKm() > actual.costoPorKm()) {
                tipos[j + 1] = tipos[j];
                j--;
            }
            tipos[j + 1] = actual;
        }
        return tipos;
    }

    /** Tipos de unidad ordenados de menor a mayor capacidad. */
    private static TipoUnidad[] tiposPorCapacidad() {
        TipoUnidad[] tipos = TipoUnidad.values().clone();
        for (int i = 1; i < tipos.length; i++) {
            TipoUnidad actual = tipos[i];
            int j = i - 1;
            while (j >= 0 && tipos[j].capacidad() > actual.capacidad()) {
                tipos[j + 1] = tipos[j];
                j--;
            }
            tipos[j + 1] = actual;
        }
        return tipos;
    }

    private static String componerNombre(int granularidad, double ruido) {
        StringBuilder texto = new StringBuilder("Clarke-Wright paralelo");
        if (granularidad > 0) {
            texto.append(" g=").append(granularidad);
        }
        if (ruido > 0.0) {
            texto.append(String.format(" ruido=%.2f", ruido));
        }
        return texto.toString();
    }
}
