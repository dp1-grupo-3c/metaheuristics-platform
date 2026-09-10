package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.construccion.HeuristicaConstructiva;
import org.kindbox.core.evaluacion.FuncionObjetivo;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Busqueda adaptativa de vecindad amplia del apartado 7 del ISA (Shaw 1998; Ropke y Pisinger
 * 2006; Pisinger y Ropke 2007; Christiaens y Vanden Berghe 2020; Voigt 2025).
 *
 * <p>Es el algoritmo de trayectoria de los dos que compara el experimento del apartado 12.
 * Cada iteracion destruye una parte de la solucion vigente y la reconstruye: un operador de
 * destruccion manda al banco un numero {@code q} de tareas de entrega y un operador de
 * reconstruccion las vuelve a colocar en las rutas. Una tarea es una visita: hay una por
 * pedido salvo cuando el pedido supera la capacidad de la unidad mas grande de la flota, en
 * cuyo caso {@link TareasAlns} lo reparte en varias entregas parciales, que el enunciado
 * admite y sin las cuales ese pedido no cabria en ninguna ruta. Los operadores se eligen por ruleta ponderada sobre pesos
 * que se adaptan por segmentos, y la solucion resultante se acepta segun un recocido simulado.
 * La mejor solucion conocida se conserva aparte y nunca se pierde.</p>
 *
 * <h2>Que la distingue de la busqueda genetica hibrida</h2>
 * <p>Los dos algoritmos comparten el modelo del problema, el decodificador, la funcion
 * objetivo, la matriz de distancias y la heuristica constructiva, conforme al apartado 10, de
 * modo que el experimento compara mecanismos de busqueda y no modelos. La diferencia
 * estructural esta en el tratamiento de la infactibilidad: HGS mantiene una subpoblacion
 * infactible y la penaliza, mientras que aqui <b>ninguna solucion infactible en sentido duro
 * se acepta jamas</b>. La unica infactibilidad admitida es la del banco de tareas sin
 * asignar; los pedidos con alguna tarea en el banco son exactamente la H del nivel 1 del
 * objetivo, porque un pedido servido a medias no cuenta como atendido. La comprobacion de
 * las siete restricciones del apartado 2.6 vive dentro del operador de insercion, que delega
 * en {@link ProgramadorRuta}.</p>
 *
 * <h2>Presupuesto</h2>
 * <p>El presupuesto es de reloj de pared, entre 2 y 18 segundos segun la configuracion del
 * apartado 2.3. El bucle consulta {@code presupuesto.agotado()} en cada iteracion y los
 * operadores de reconstruccion lo consultan tambien por dentro, de modo que la interrupcion
 * ocurre como muy tarde tras colocar una tarea. La unica parte no interrumpible es la
 * heuristica constructiva del arranque, cuyo contrato no recibe el presupuesto: con
 * presupuestos menores que su propio tiempo de construccion el reloj de pared queda acotado
 * por ese tiempo y no por el presupuesto. Sea cual sea el punto de corte se devuelve la
 * mejor solucion factible conocida. El enfriamiento del recocido se parametriza contra
 * {@code presupuesto.fraccionConsumida()} y no contra el contador de iteraciones, por la razon
 * que documenta {@link CriterioAceptacion}.</p>
 *
 * <h2>Arranque desde el plan vigente</h2>
 * <p>{@link #resolverDesde} arranca desde un plan ya en ejecucion en lugar de desde la
 * heuristica constructiva. El apartado 11.4 del ISA senala que esa posibilidad favorece de
 * forma natural la estabilidad del plan entre replanificaciones, porque la busqueda parte de
 * las asignaciones vigentes y solo se aparta de ellas cuando gana algo, y constituye una
 * hipotesis experimental de interes que el banco de pruebas del apartado 12 puede contrastar
 * contra el arranque constructivo.</p>
 *
 * <h2>Reproducibilidad</h2>
 * <p>Cada corrida construye su propio decodificador, sus propios operadores y su propio
 * generador aleatorio a partir de la semilla, y todos los recorridos son por indice, de modo
 * que ninguna tabla asociativa influye en el resultado. La reproduccion es, aun asi,
 * <b>estadistica y no bit a bit</b>: el criterio de aceptacion se enfria contra el reloj y no
 * contra el contador de iteraciones, de modo que dos corridas con la misma semilla en maquinas
 * o cargas distintas completan un numero distinto de iteraciones y toman decisiones de
 * aceptacion distintas. Es el precio deliberado de que el comportamiento sea comparable entre
 * las tres configuraciones de presupuesto del apartado 2.3, y por eso el apartado 12.3 pide
 * corridas repetidas y compara medias en lugar de valores unicos. Fijar
 * {@code maximoIteracionesSinMejora} y un presupuesto holgado tampoco vuelve la corrida
 * determinista, por la misma razon.</p>
 *
 * <p>La clase es reentrante entre corridas pero no segura para uso concurrente.</p>
 */
public final class BusquedaAdaptativaVecindadAmplia implements Algoritmo {

    /** Nombre con que el algoritmo aparece en los reportes del apartado 12. */
    public static final String NOMBRE = "ALNS";

    private final HeuristicaConstructiva constructiva;
    private final ParametrosAlns parametros;
    private final FuncionObjetivo objetivo;
    private final long semilla;

    /**
     * @param constructiva heuristica que produce la solucion de partida; puede ser
     *                     {@code null}, en cuyo caso el arranque es una insercion voraz sobre
     *                     el banco completo
     */
    public BusquedaAdaptativaVecindadAmplia(HeuristicaConstructiva constructiva) {
        this(constructiva, ParametrosAlns.porDefecto(), new FuncionObjetivoJerarquica(), 0L);
    }

    public BusquedaAdaptativaVecindadAmplia(HeuristicaConstructiva constructiva, long semilla) {
        this(constructiva, ParametrosAlns.porDefecto(), new FuncionObjetivoJerarquica(), semilla);
    }

    public BusquedaAdaptativaVecindadAmplia(HeuristicaConstructiva constructiva,
                                            ParametrosAlns parametros, long semilla) {
        this(constructiva, parametros, new FuncionObjetivoJerarquica(), semilla);
    }

    /**
     * @param constructiva heuristica de arranque, o {@code null}
     * @param parametros   parametros calibrables del apartado 7.4
     * @param objetivo     funcion objetivo compartida con el otro algoritmo, que es la que
     *                     valora la solucion devuelta
     * @param semilla      semilla del generador de la corrida
     */
    public BusquedaAdaptativaVecindadAmplia(HeuristicaConstructiva constructiva, ParametrosAlns parametros,
                                            FuncionObjetivo objetivo, long semilla) {
        if (parametros == null || objetivo == null) {
            throw new IllegalArgumentException("La busqueda necesita parametros y funcion objetivo");
        }
        this.constructiva = constructiva;
        this.parametros = parametros;
        this.objetivo = objetivo;
        this.semilla = semilla;
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    /** Parametros con que corre esta instancia. */
    public ParametrosAlns parametros() {
        return parametros;
    }

    /** Semilla del generador de la corrida. */
    public long semilla() {
        return semilla;
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
        return ejecutar(instancia, presupuesto, null);
    }

    /**
     * Resuelve la instancia arrancando desde un plan ya vigente en lugar de desde la
     * heuristica constructiva, conforme al apartado 11.4 del ISA.
     *
     * <p>Del plan solo se toman las asignaciones de pedido a unidad que siguen teniendo
     * sentido en la fotografia actual: se descartan las unidades que ya no estan disponibles y
     * los pedidos que ya no estan pendientes, y cada ruta se recorta hasta que resulta
     * factible con los bloqueos, los plazos y los turnos vigentes. Los pedidos que caen por
     * ese recorte quedan en el banco y la primera reconstruccion los recoloca.</p>
     *
     * @param planVigente plan de la iteracion anterior; con {@code null} se comporta como
     *                    {@link #resolver}
     */
    public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia,
                                                PresupuestoComputo presupuesto, Solucion planVigente) {
        return ejecutar(instancia, presupuesto, planVigente);
    }

    // ----------------------------------------------------------------- internos

    private ResultadoPlanificacion ejecutar(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                            Solucion planVigente) {
        if (instancia.cantidadPedidos() == 0 || instancia.cantidadUnidades() == 0) {
            return sinBusqueda(instancia, presupuesto);
        }

        final Aleatorio aleatorio = new Aleatorio(semilla);
        final ProgramadorRuta programador = new ProgramadorRuta(instancia);
        // Los pedidos se descomponen una sola vez en tareas de entrega. Un pedido que supera
        // la capacidad de la unidad mas grande de la flota no cabe en ninguna ruta como visita
        // unica, de modo que sin este reparto quedaria de forma permanente en el banco y
        // pondria un suelo artificial a la H del nivel 1.
        final TareasAlns tareas = new TareasAlns(instancia);
        final MotorInsercion motor = new MotorInsercion(instancia, tareas, parametros);

        final OperadorDestruccion[] destruccion = {
                new RemocionAleatoria(tareas.cantidad()),
                new RemocionDelPeor(tareas.cantidad(), parametros.determinismoSeleccion()),
                new RemocionPorAfinidad(instancia, tareas, parametros),
                new RemocionCadenasAdyacentes(instancia, tareas, parametros),
                new RemocionPorCriticidad(instancia, tareas, parametros),
                new RemocionDeRutaCompleta(instancia.cantidadUnidades())};
        final OperadorReconstruccion[] reconstruccion = {
                new InsercionVoraz(motor),
                new InsercionPorArrepentimiento(motor, parametros),
                new InsercionVorazConParpadeo(motor, parametros)};

        final CapaAdaptativa capa = new CapaAdaptativa(destruccion.length, reconstruccion.length, parametros);
        final CriterioAceptacion criterio = new CriterioAceptacion(parametros);

        final EstadoAlns vigente = new EstadoAlns(instancia, tareas, programador);
        final EstadoAlns candidato = new EstadoAlns(instancia, tareas, programador);
        final EstadoAlns mejor = new EstadoAlns(instancia, tareas, programador);

        arrancar(vigente, planVigente, programador, aleatorio, presupuesto, reconstruccion[0]);
        mejor.copiarDesde(vigente);
        ValorObjetivo mejorValor = mejor.valor();

        final double penalizacion = penalizacionDelBanco(vigente);
        double escalarVigente = vigente.escalar(penalizacion);
        criterio.calibrar(escalarVigente);

        final long topeSinMejora = parametros.maximoIteracionesSinMejora();
        final long topeParaReinicio = parametros.iteracionesParaReinicio();
        long sinMejora = 0L;

        while (!presupuesto.agotado()) {
            final ValorObjetivo muestra = mejorValor;
            presupuesto.muestrear(() -> muestra);

            final int indiceDestruccion = capa.elegirDestruccion(aleatorio);
            final int indiceReconstruccion = capa.elegirReconstruccion(aleatorio);

            candidato.copiarDesde(vigente);
            int grado = gradoDeDestruccion(candidato, aleatorio);
            if (grado > 0) {
                destruccion[indiceDestruccion].destruir(candidato, grado, aleatorio);
            }
            reconstruccion[indiceReconstruccion].reconstruir(candidato, aleatorio, presupuesto);

            final ValorObjetivo valorCandidato = candidato.valor();
            final double escalarCandidato = candidato.escalar(penalizacion);
            final int resultado;
            if (valorCandidato.mejorQue(mejorValor)) {
                mejor.copiarDesde(candidato);
                mejorValor = valorCandidato;
                vigente.copiarDesde(candidato);
                escalarVigente = escalarCandidato;
                sinMejora = 0L;
                resultado = CapaAdaptativa.NUEVA_MEJOR;
            } else if (escalarCandidato < escalarVigente) {
                vigente.copiarDesde(candidato);
                escalarVigente = escalarCandidato;
                sinMejora++;
                resultado = CapaAdaptativa.MEJORA;
            } else if (criterio.aceptar(escalarCandidato, escalarVigente,
                    presupuesto.fraccionConsumida(), aleatorio)) {
                vigente.copiarDesde(candidato);
                escalarVigente = escalarCandidato;
                sinMejora++;
                resultado = CapaAdaptativa.ACEPTADA;
            } else {
                sinMejora++;
                resultado = CapaAdaptativa.RECHAZADA;
            }

            capa.registrar(indiceDestruccion, indiceReconstruccion, resultado);
            presupuesto.contarIteracion();

            if (topeSinMejora > 0L && sinMejora >= topeSinMejora) {
                break;
            }
            if (sinMejora > 0L && sinMejora % topeParaReinicio == 0L) {
                vigente.copiarDesde(mejor);
                escalarVigente = vigente.escalar(penalizacion);
            }
        }

        final Solucion solucion = mejor.materializar();
        final ValorObjetivo valorFinal = objetivo.evaluar(instancia, solucion);
        presupuesto.cerrarPerfil(valorFinal);
        return new ResultadoPlanificacion(NOMBRE, solucion.conValor(valorFinal), presupuesto.perfil(),
                presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semilla);
    }

    /**
     * Construye la solucion de partida y cierra su banco con una insercion voraz.
     *
     * <p>La heuristica constructiva se recibe por la interfaz {@link HeuristicaConstructiva} y
     * es la misma que consume la busqueda genetica hibrida, conforme al apartado 10 del ISA.
     * Si no se proporciona ninguna, o si la que se proporciona deja pedidos sin colocar, la
     * propia insercion voraz del conjunto de operadores produce la solucion inicial: el
     * algoritmo no depende de una implementacion concreta para arrancar.</p>
     */
    private void arrancar(EstadoAlns estado, Solucion planVigente, ProgramadorRuta programador,
                          Aleatorio aleatorio, PresupuestoComputo presupuesto,
                          OperadorReconstruccion voraz) {
        Solucion partida = planVigente;
        if (partida == null && constructiva != null) {
            programador.reiniciarInventarios();
            partida = constructiva.construir(estado.instancia(), programador, aleatorio);
        }
        // La busqueda valora las rutas con el inventario intacto; el consumo de la
        // construccion no puede arrastrarse.
        programador.reiniciarInventarios();
        if (partida != null) {
            estado.cargarDesde(partida);
        } else {
            estado.vaciar();
        }
        voraz.reconstruir(estado, aleatorio, presupuesto);
    }

    /**
     * Penalizacion con que cada pedido del banco entra en el escalar interno de aceptacion.
     * Se fija como un multiplo del costo medio por pedido atendido de la solucion inicial, de
     * modo que abandonar un pedido nunca resulte rentable por mucho kilometraje que ahorre.
     * Asi el nivel 1 del objetivo, que es lexicografico, se traslada al recocido sin que este
     * quede incapaz de moverse entre soluciones con H distinta.
     */
    private double penalizacionDelBanco(EstadoAlns estado) {
        int colocados = estado.asignados();
        double medio = colocados > 0 && estado.costo() > 0.0 ? estado.costo() / colocados : 100.0;
        return parametros.factorPenalizacionBanco() * medio;
    }

    /**
     * Numero de pedidos que retira la destruccion de esta iteracion. Se sortea en el rango del
     * apartado 7.4, expresado en fraccion de los pedidos ya colocados, y se acota por el tope
     * absoluto que hace viable el presupuesto de segundos.
     */
    private int gradoDeDestruccion(EstadoAlns estado, Aleatorio aleatorio) {
        int colocados = estado.asignados();
        if (colocados <= 0) {
            return 0;
        }
        int minimo = Math.max(1, (int) Math.ceil(parametros.fraccionMinimaDestruccion() * colocados));
        int maximo = (int) Math.floor(parametros.fraccionMaximaDestruccion() * colocados);
        maximo = Math.min(Math.min(maximo, parametros.maximoAbsolutoDestruccion()), colocados);
        if (maximo < minimo) {
            maximo = minimo;
        }
        minimo = Math.min(minimo, maximo);
        return minimo == maximo ? minimo : aleatorio.siguienteEntero(minimo, maximo);
    }

    /** Salida inmediata para una fotografia sin pedidos pendientes o sin unidades disponibles. */
    private ResultadoPlanificacion sinBusqueda(InstanciaPlanificacion instancia,
                                               PresupuestoComputo presupuesto) {
        Solucion vacia = Solucion.vacia(instancia);
        ValorObjetivo valor = objetivo.evaluar(instancia, vacia);
        presupuesto.cerrarPerfil(valor);
        return new ResultadoPlanificacion(NOMBRE, vacia.conValor(valor), presupuesto.perfil(),
                presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semilla);
    }
}
