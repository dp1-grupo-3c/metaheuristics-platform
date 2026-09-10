package org.kindbox.core.metaheuristica.hgs;

/**
 * Parametros de la busqueda genetica hibrida, con los valores iniciales del apartado 6.4
 * del ISA.
 *
 * <p>Los parametros de poblacion no son estimaciones del equipo: tamano minimo 25, tamano de
 * generacion 40, cuatro individuos de elite, proporcion objetivo de factibles 0,20 y
 * granularidad del vecindario 20 son los valores publicados por Vidal, Crainic, Gendreau y
 * Prins (2012) y por Vidal (2022), verificados de forma independiente por Simensen, Hasle y
 * Stalhane (2022), que ademas situan entre el 5 y el 40 por ciento el esfuerzo que conviene
 * dedicar al individuo de elite inicial.</p>
 *
 * <p>Tres parametros <b>si</b> se apartan de los valores publicados, porque dependen del
 * problema y del presupuesto y no de la metaheuristica: la escala de la penalizacion del
 * desfase, la frecuencia con que se ajusta y el numero de pasadas de la busqueda local. Cada
 * uno lleva en su metodo la razon del cambio y la medicion que lo sostiene. El resto de
 * valores gobiernan el Split adaptado y la materializacion, que son propios de esta
 * implementacion y no tienen referencia publicada.</p>
 *
 * <p>El objeto se lee una sola vez al arrancar {@link BusquedaGeneticaHibrida#resolver}, de
 * modo que modificarlo a mitad de una ejecucion no afecta a la ejecucion en curso.</p>
 */
public final class ParametrosHgs {

    /** Tamano minimo de cada subpoblacion, mu en la notacion de Vidal. */
    public static final int TAMANO_MINIMO_POBLACION = 25;
    /** Numero de descendientes que se acumulan antes de seleccionar supervivientes, lambda. */
    public static final int TAMANO_GENERACION = 40;
    /** Individuos de elite cuya supervivencia esta garantizada por su valor objetivo. */
    public static final int INDIVIDUOS_ELITE = 4;
    /** Individuos mas proximos con los que se mide la contribucion a la diversidad. */
    public static final int VECINOS_PROXIMOS_DIVERSIDAD = 5;
    /** Proporcion objetivo de descendientes factibles que persigue la penalizacion dinamica. */
    public static final double PROPORCION_OBJETIVO_FACTIBLES = 0.20;
    /** Numero de vertices proximos que explora cada movimiento de la busqueda local. */
    public static final int GRANULARIDAD_VECINDARIO = 20;
    /** Fraccion del presupuesto dedicada a producir el individuo de elite inicial. */
    public static final double ESFUERZO_ELITE = 0.10;
    /** Cota inferior admisible del esfuerzo de elite, segun Simensen y otros (2022). */
    public static final double ESFUERZO_ELITE_MINIMO = 0.05;
    /** Cota superior admisible del esfuerzo de elite, segun Simensen y otros (2022). */
    public static final double ESFUERZO_ELITE_MAXIMO = 0.40;

    /**
     * Costo, en soles, con el que se penaliza en la busqueda interna una tarea de entrega
     * que queda sin asignar. Coincide con el peso del nivel 1 de la funcion objetivo, de
     * modo que ninguna ganancia de costo compensa dejar un pedido sin atender.
     */
    public static final double PENALIZACION_TAREA_NO_ATENDIDA = 1_000_000.0;

    /**
     * Costo, en soles, con el que la busqueda interna penaliza cada pedido que cambia de
     * unidad respecto del plan vigente. Es el termino blando de estabilidad del apartado 11.4
     * del ISA, y {@link EstabilidadPlan} lo resuelve en tiempo constante por tarea.
     *
     * <p>El valor se elige por su relacion con los otros dos pesos del escalar interno y no
     * por si mismo. Por arriba tiene que quedar <b>muy por debajo</b> de
     * {@link #PENALIZACION_TAREA_NO_ATENDIDA}, que vale un millon: con 25 soles la relacion es
     * de uno a cuarenta mil, de modo que ninguna acumulacion imaginable de estabilidad
     * compensa dejar un pedido fuera de plazo, que es la exigencia expresa del apartado 11.4.
     * Por abajo tiene que ser comparable al ahorro de costo que produce una eleccion de unidad
     * distinta, porque si no, no desempata nada: el costo por kilometro de la flota va de 3
     * soles la bicicleta a 8 el auto, de modo que 25 soles equivalen a un rodeo de entre tres y
     * ocho kilometros. Leido asi, el peso dice que conservar la unidad de un pedido vale lo que
     * un desvio de unos pocos kilometros, y que un ahorro mayor que ese sigue prevaleciendo.
     * Un peso de 1 sol, que es el de {@code FuncionObjetivo.PESO_ESTABILIDAD} en la evaluacion
     * final, solo llega a romper los empates exactos, que son muchos pero no todos.</p>
     *
     * <p>Con cero el termino queda desactivado y la busqueda se comporta como antes de
     * cablearlo, lo que permite medir el efecto sin recompilar.</p>
     */
    public static final double PESO_ESTABILIDAD = 25.0;

    private int tamanoMinimoPoblacion = TAMANO_MINIMO_POBLACION;
    private int tamanoGeneracion = TAMANO_GENERACION;
    private int individuosElite = INDIVIDUOS_ELITE;
    private int vecinosProximosDiversidad = VECINOS_PROXIMOS_DIVERSIDAD;
    private double proporcionObjetivoFactibles = PROPORCION_OBJETIVO_FACTIBLES;
    private int granularidadVecindario = GRANULARIDAD_VECINDARIO;
    private double esfuerzoElite = ESFUERZO_ELITE;

    private int longitudMaximaArco = 8;
    private int iteracionesLagrangiana = 6;
    private int candidatosUnidadPorRuta = 3;
    private int desfaseMaximoDeArco = 240;

    private double pesoEstabilidad = PESO_ESTABILIDAD;

    private double penalizacionDesfaseInicial = 20_000.0;
    private double penalizacionDesfaseMinima = 1_000.0;
    private double penalizacionDesfaseMaxima = 200_000.0;
    private int frecuenciaAjustePenalizacion = 10;
    private double factorAumentoPenalizacion = 1.20;
    private double factorReduccionPenalizacion = 0.85;
    private double holguraProporcionFactibles = 0.05;

    private int frecuenciaMaterializacion = 25;
    private double probabilidadReparacion = 0.50;
    private double factorPenalizacionReparacion = 10.0;
    private int pasadasEducacionMaximas = 4;

    private long maximoGeneraciones = 1_000_000L;
    private long maximoGeneracionesSinMejora = 20_000L;

    /** Parametros con los valores iniciales del apartado 6.4 del ISA. */
    public static ParametrosHgs porDefecto() {
        return new ParametrosHgs();
    }

    // ------------------------------------------------------------- poblacion

    /** Tamano minimo de cada subpoblacion. */
    public int tamanoMinimoPoblacion() {
        return tamanoMinimoPoblacion;
    }

    public ParametrosHgs tamanoMinimoPoblacion(int valor) {
        this.tamanoMinimoPoblacion = exigirPositivo(valor, "tamano minimo de poblacion");
        return this;
    }

    /** Descendientes generados antes de seleccionar supervivientes. */
    public int tamanoGeneracion() {
        return tamanoGeneracion;
    }

    public ParametrosHgs tamanoGeneracion(int valor) {
        this.tamanoGeneracion = exigirPositivo(valor, "tamano de generacion");
        return this;
    }

    /** Individuos de elite con supervivencia garantizada por valor objetivo. */
    public int individuosElite() {
        return individuosElite;
    }

    public ParametrosHgs individuosElite(int valor) {
        if (valor < 0) {
            throw new IllegalArgumentException("Individuos de elite negativos: " + valor);
        }
        this.individuosElite = valor;
        return this;
    }

    /** Individuos mas proximos que intervienen en la contribucion a la diversidad. */
    public int vecinosProximosDiversidad() {
        return vecinosProximosDiversidad;
    }

    public ParametrosHgs vecinosProximosDiversidad(int valor) {
        this.vecinosProximosDiversidad = exigirPositivo(valor, "vecinos proximos de diversidad");
        return this;
    }

    /** Proporcion objetivo de descendientes factibles. */
    public double proporcionObjetivoFactibles() {
        return proporcionObjetivoFactibles;
    }

    public ParametrosHgs proporcionObjetivoFactibles(double valor) {
        if (valor <= 0.0 || valor >= 1.0) {
            throw new IllegalArgumentException("Proporcion objetivo de factibles fuera de (0,1): " + valor);
        }
        this.proporcionObjetivoFactibles = valor;
        return this;
    }

    // ------------------------------------------------------------- educacion

    /** Numero de vertices proximos que explora cada movimiento de la busqueda local. */
    public int granularidadVecindario() {
        return granularidadVecindario;
    }

    public ParametrosHgs granularidadVecindario(int valor) {
        this.granularidadVecindario = exigirPositivo(valor, "granularidad del vecindario");
        return this;
    }

    /**
     * Pasadas maximas de la busqueda local sobre un mismo descendiente. Las ultimas pasadas
     * de una educacion son las mas caras, porque ya casi no encuentran mejoras y recorren el
     * vecindario entero de cada vertice; con un presupuesto de segundos rinde mas invertir
     * ese tiempo en generar mas descendientes. Cuatro pasadas multiplican por cuatro las
     * generaciones alcanzadas sin perder calidad final.
     */
    public int pasadasEducacionMaximas() {
        return pasadasEducacionMaximas;
    }

    public ParametrosHgs pasadasEducacionMaximas(int valor) {
        this.pasadasEducacionMaximas = exigirPositivo(valor, "pasadas de educacion");
        return this;
    }

    /**
     * Generaciones entre dos sondeos de materializacion. Un sondeo construye la solucion
     * concreta de un descendiente aunque su cota entregable no mejore, porque la reparacion
     * de rutas que hace la materializacion puede rescatar visitas que la cota da por
     * perdidas.
     */
    public int frecuenciaMaterializacion() {
        return frecuenciaMaterializacion;
    }

    public ParametrosHgs frecuenciaMaterializacion(int valor) {
        this.frecuenciaMaterializacion = exigirPositivo(valor, "frecuencia de materializacion");
        return this;
    }

    /** Probabilidad de reintentar la educacion con la penalizacion multiplicada. */
    public double probabilidadReparacion() {
        return probabilidadReparacion;
    }

    public ParametrosHgs probabilidadReparacion(double valor) {
        this.probabilidadReparacion = exigirFraccion(valor, "probabilidad de reparacion");
        return this;
    }

    /** Factor por el que se multiplica la penalizacion en la fase de reparacion. */
    public double factorPenalizacionReparacion() {
        return factorPenalizacionReparacion;
    }

    public ParametrosHgs factorPenalizacionReparacion(double valor) {
        if (valor < 1.0) {
            throw new IllegalArgumentException("Factor de reparacion menor que uno: " + valor);
        }
        this.factorPenalizacionReparacion = valor;
        return this;
    }

    // ----------------------------------------------------------------- split

    /**
     * Numero maximo de tareas de entrega que puede cubrir un arco del grafo auxiliar del
     * Split. El acondicionamiento de una hora por visita limita una ruta a siete u ocho
     * paradas dentro de un turno de ocho horas, de modo que explorar arcos mas largos solo
     * gasta presupuesto (apartado 6.3.2 del ISA).
     */
    public int longitudMaximaArco() {
        return longitudMaximaArco;
    }

    public ParametrosHgs longitudMaximaArco(int valor) {
        this.longitudMaximaArco = exigirPositivo(valor, "longitud maxima de arco");
        return this;
    }

    /** Iteraciones de subgradiente del reparto de unidades por tipo en el Split. */
    public int iteracionesLagrangiana() {
        return iteracionesLagrangiana;
    }

    public ParametrosHgs iteracionesLagrangiana(int valor) {
        this.iteracionesLagrangiana = exigirPositivo(valor, "iteraciones lagrangianas");
        return this;
    }

    /** Unidades concretas que se prueban por ruta al deshacer la dependencia circular. */
    public int candidatosUnidadPorRuta() {
        return candidatosUnidadPorRuta;
    }

    public ParametrosHgs candidatosUnidadPorRuta(int valor) {
        this.candidatosUnidadPorRuta = exigirPositivo(valor, "candidatos de unidad por ruta");
        return this;
    }

    /**
     * Desfase, en minutos, a partir del cual se deja de alargar un arco. Un arco que ya
     * incumple plazos por mas de este margen solo empeora al anadirle visitas.
     */
    public int desfaseMaximoDeArco() {
        return desfaseMaximoDeArco;
    }

    public ParametrosHgs desfaseMaximoDeArco(int valor) {
        this.desfaseMaximoDeArco = exigirPositivo(valor, "desfase maximo de arco");
        return this;
    }

    // ---------------------------------------------------------- penalizacion

    /**
     * Costo en soles con el que arranca la penalizacion de cada minuto de desfase.
     *
     * <p>El valor es alto a proposito y no es el de un VRP con ventanas blandas. En PaqRap
     * una entrega fuera de plazo <b>no cuenta como entrega</b>: el pedido sigue sumando a H,
     * el nivel 1 del objetivo. Una penalizacion pequena llevaria a la busqueda a su optimo
     * interno construyendo rutas que lo sirven todo con horas de retraso, que al materializar
     * el plan valen exactamente lo mismo que no haber servido nada. La penalizacion tiene por
     * tanto que ser del orden del peso de una tarea no atendida dividido entre los minutos de
     * retraso tipicos, y por eso {@link #penalizacionDesfaseMinima} tampoco baja de un valor
     * en el que el retraso siga siendo caro. La experimentacion sobre la instancia densa de
     * ciento cincuenta y siete pedidos confirma el efecto: con 10 soles por minuto se llega a
     * H igual a 21 y con 20 000 a H igual a 16 en el mismo presupuesto de dos segundos.</p>
     */
    public double penalizacionDesfaseInicial() {
        return penalizacionDesfaseInicial;
    }

    public ParametrosHgs penalizacionDesfaseInicial(double valor) {
        this.penalizacionDesfaseInicial = exigirPositivo(valor, "penalizacion de desfase inicial");
        return this;
    }

    /** Cota inferior de la penalizacion dinamica. */
    public double penalizacionDesfaseMinima() {
        return penalizacionDesfaseMinima;
    }

    public ParametrosHgs penalizacionDesfaseMinima(double valor) {
        this.penalizacionDesfaseMinima = exigirPositivo(valor, "penalizacion de desfase minima");
        return this;
    }

    /** Cota superior de la penalizacion dinamica. */
    public double penalizacionDesfaseMaxima() {
        return penalizacionDesfaseMaxima;
    }

    public ParametrosHgs penalizacionDesfaseMaxima(double valor) {
        this.penalizacionDesfaseMaxima = exigirPositivo(valor, "penalizacion de desfase maxima");
        return this;
    }

    /**
     * Generaciones entre dos ajustes de la penalizacion dinamica. Vidal y otros (2012) lo
     * fijan en 100, pensando en corridas de decenas de miles de iteraciones; con el
     * presupuesto de entre 2 y 18 segundos del apartado 2.3 una corrida completa apenas pasa
     * de unos centenares de generaciones, de modo que ajustar cada 100 dejaria el mecanismo
     * sin actuar. Se adopta 10, que es el mismo mecanismo a la escala de este presupuesto.
     */
    public int frecuenciaAjustePenalizacion() {
        return frecuenciaAjustePenalizacion;
    }

    public ParametrosHgs frecuenciaAjustePenalizacion(int valor) {
        this.frecuenciaAjustePenalizacion = exigirPositivo(valor, "frecuencia de ajuste de penalizacion");
        return this;
    }

    /**
     * Peso en soles de cada pedido que cambia de unidad respecto del plan vigente. El valor
     * por defecto y su justificacion estan en {@link #PESO_ESTABILIDAD}.
     */
    public double pesoEstabilidad() {
        return pesoEstabilidad;
    }

    public ParametrosHgs pesoEstabilidad(double valor) {
        if (valor < 0.0 || !Double.isFinite(valor)) {
            throw new IllegalArgumentException("Peso de estabilidad no valido: " + valor);
        }
        if (valor >= PENALIZACION_TAREA_NO_ATENDIDA) {
            throw new IllegalArgumentException(
                    "El peso de estabilidad debe quedar muy por debajo del de un pedido no atendido: " + valor);
        }
        this.pesoEstabilidad = valor;
        return this;
    }

    /** Factor de aumento cuando hay menos factibles de los deseados. */
    public double factorAumentoPenalizacion() {
        return factorAumentoPenalizacion;
    }

    public ParametrosHgs factorAumentoPenalizacion(double valor) {
        if (valor <= 1.0) {
            throw new IllegalArgumentException("Factor de aumento no mayor que uno: " + valor);
        }
        this.factorAumentoPenalizacion = valor;
        return this;
    }

    /** Factor de reduccion cuando hay mas factibles de los deseados. */
    public double factorReduccionPenalizacion() {
        return factorReduccionPenalizacion;
    }

    public ParametrosHgs factorReduccionPenalizacion(double valor) {
        if (valor <= 0.0 || valor >= 1.0) {
            throw new IllegalArgumentException("Factor de reduccion fuera de (0,1): " + valor);
        }
        this.factorReduccionPenalizacion = valor;
        return this;
    }

    /** Banda muerta alrededor de la proporcion objetivo dentro de la cual no se ajusta nada. */
    public double holguraProporcionFactibles() {
        return holguraProporcionFactibles;
    }

    public ParametrosHgs holguraProporcionFactibles(double valor) {
        this.holguraProporcionFactibles = exigirFraccion(valor, "holgura de la proporcion de factibles");
        return this;
    }

    // ------------------------------------------------------------- detencion

    /**
     * Fraccion del presupuesto dedicada a producir el individuo de elite inicial. Simensen,
     * Hasle y Stalhane (2022) situan entre el 5 y el 40 por ciento el rango en que ese
     * esfuerzo mejora la convergencia y la calidad final.
     */
    public double esfuerzoElite() {
        return esfuerzoElite;
    }

    public ParametrosHgs esfuerzoElite(double valor) {
        if (valor < ESFUERZO_ELITE_MINIMO || valor > ESFUERZO_ELITE_MAXIMO) {
            throw new IllegalArgumentException("Esfuerzo de elite fuera de ["
                    + ESFUERZO_ELITE_MINIMO + ", " + ESFUERZO_ELITE_MAXIMO + "]: " + valor);
        }
        this.esfuerzoElite = valor;
        return this;
    }

    /** Cota de generaciones. En operacion manda el presupuesto de reloj. */
    public long maximoGeneraciones() {
        return maximoGeneraciones;
    }

    public ParametrosHgs maximoGeneraciones(long valor) {
        if (valor <= 0) {
            throw new IllegalArgumentException("Maximo de generaciones no positivo: " + valor);
        }
        this.maximoGeneraciones = valor;
        return this;
    }

    /** Generaciones consecutivas sin mejora tras las que se detiene la busqueda. */
    public long maximoGeneracionesSinMejora() {
        return maximoGeneracionesSinMejora;
    }

    public ParametrosHgs maximoGeneracionesSinMejora(long valor) {
        if (valor <= 0) {
            throw new IllegalArgumentException("Maximo de generaciones sin mejora no positivo: " + valor);
        }
        this.maximoGeneracionesSinMejora = valor;
        return this;
    }

    // ------------------------------------------------------------ auxiliares

    private static int exigirPositivo(int valor, String nombre) {
        if (valor <= 0) {
            throw new IllegalArgumentException("Valor no positivo de " + nombre + ": " + valor);
        }
        return valor;
    }

    private static double exigirPositivo(double valor, String nombre) {
        if (!(valor > 0.0) || !Double.isFinite(valor)) {
            throw new IllegalArgumentException("Valor no positivo de " + nombre + ": " + valor);
        }
        return valor;
    }

    private static double exigirFraccion(double valor, String nombre) {
        if (valor < 0.0 || valor > 1.0) {
            throw new IllegalArgumentException("Valor de " + nombre + " fuera de [0,1]: " + valor);
        }
        return valor;
    }

    @Override
    public String toString() {
        return "ParametrosHgs[mu=" + tamanoMinimoPoblacion + " lambda=" + tamanoGeneracion
                + " elite=" + individuosElite + " granularidad=" + granularidadVecindario
                + " objetivoFactibles=" + proporcionObjetivoFactibles
                + " pesoEstabilidad=" + pesoEstabilidad
                + " esfuerzoElite=" + esfuerzoElite + "]";
    }
}
