package org.kindbox.core.metaheuristica.alns;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Con un presupuesto por iteraciones de {@code PresupuestoComputo.deIteraciones(n)} cada
 * decision anterior pasa a ser funcion del contador: el bucle se detiene exactamente tras
 * {@code n} iteraciones, la temperatura es la de la iteracion {@code k} de {@code n} y los
 * operadores de reconstruccion no ven cambiar {@code agotado()} dentro de una iteracion,
 * porque el contador solo avanza al cerrarla. El arranque, que precede al bucle, se hace con
 * el contador en cero: la heuristica constructiva y la insercion voraz que cierra su banco
 * corren hasta el final, acotadas por su propio numero de pares y de tareas, y solo una
 * cancelacion puede cortarlas.</p>
 *
 * <h2>Estabilidad del plan entre replanificaciones</h2>
 * <p>La restriccion blanda del apartado 11.4 del ISA entra en la busqueda como un termino de
 * penalizacion de bajo peso sobre el numero de pedidos que cambian de unidad respecto del plan
 * vigente que trae la fotografia. El termino vive dentro del escalar interno de
 * {@link EstadoAlns#escalar}, que es lo que compara el recocido, y dentro del delta con que el
 * motor de insercion elige unidad, que es donde se decide de verdad quien atiende cada pedido.
 * Sin el, la penalizacion solo aparecia en la evaluacion final y no guiaba nada: como el
 * objetivo tiene muchos empates de costo y la busqueda es estocastica, cada replanificacion
 * devolvia un desempate distinto y un pedido podia rotar de unidad en unidad sin que ninguna
 * llegase a salir del almacen.</p>
 *
 * <p>El peso se fija con {@code ParametrosAlns.factorPenalizacionEstabilidad} muy por debajo
 * del de la infactibilidad, de modo que la estabilidad nunca prevalece sobre el cumplimiento
 * del plazo, y el nivel 1 del objetivo sigue decidiendo por su cuenta cual es la mejor
 * solucion. El plan vigente se traduce una sola vez al arrancar la corrida en
 * {@link PlanVigenteAlns}, y de ahi en adelante resolverlo cuesta un acceso a arreglo.</p>
 *
 * <h2>Arranque desde el plan vigente</h2>
 * <p>{@link #resolverDesde} arranca desde un plan ya en ejecucion en lugar de desde la
 * heuristica constructiva. Es el segundo modo de arranque del apartado 7.3.5 del ISA. El
 * apartado 11.4 senala que esa posibilidad favorece de forma natural la estabilidad del plan
 * entre replanificaciones, porque la busqueda parte de las asignaciones vigentes y solo se
 * aparta de ellas cuando gana algo, y constituye una hipotesis experimental de interes que el
 * banco de pruebas del apartado 12 puede contrastar contra el arranque constructivo. El motor
 * de simulacion lo activa con el indicador {@code arranqueDesdePlanVigente} de la
 * configuracion del escenario, y entonces le entrega en cada replanificacion el plan vigente
 * recortado a la fotografia.</p>
 *
 * <p>Los dos mecanismos se refuerzan y no se estorban: el arranque coloca los pedidos en la
 * unidad que ya los tenia, con lo que la desviacion de partida es nula y el termino no
 * penaliza nada; a partir de ahi el termino es justo lo que impide que la busqueda deshaga
 * ese arranque por un empate de costo. Con el arranque constructivo la desviacion de partida
 * es alta y el termino la va rebajando movimiento a movimiento. En ninguno de los dos casos
 * hay penalizacion cruzada, porque el termino se mide siempre contra la asignacion vigente de
 * la fotografia y nunca contra la solucion de partida.</p>
 *
 * <h2>Reproducibilidad</h2>
 * <p>Cada corrida construye su propio decodificador, sus propios operadores y su propio
 * generador aleatorio a partir de la semilla, y todos los recorridos son por indice, de modo
 * que ninguna tabla asociativa influye en el resultado. Con presupuesto por reloj la
 * reproduccion es, aun asi, <b>estadistica y no bit a bit</b>: el criterio de aceptacion se
 * enfria contra el reloj y no contra el contador de iteraciones, de modo que dos corridas con
 * la misma semilla en maquinas o cargas distintas completan un numero distinto de iteraciones
 * y toman decisiones de aceptacion distintas. Es el precio deliberado de que el comportamiento
 * sea comparable entre las tres configuraciones de presupuesto del apartado 2.3, y por eso el
 * apartado 12.3 pide corridas repetidas y compara medias en lugar de valores unicos. Fijar
 * {@code maximoIteracionesSinMejora} y un presupuesto holgado tampoco vuelve la corrida
 * determinista, por la misma razon.</p>
 *
 * <p>Con presupuesto por iteraciones la reproduccion si es <b>bit a bit</b>: la misma semilla
 * y la misma instancia dan el mismo plan, con las mismas paradas en el mismo orden y el mismo
 * costo hasta el ultimo bit, que es lo que exigen las pruebas de regresion. La semilla es la
 * del constructor, salvo que el invocante fije otra con
 * {@link #resolver(InstanciaPlanificacion, PresupuestoComputo, long)}, como hace el motor de
 * simulacion para dar a cada replanificacion su propia corriente aleatoria.</p>
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
    /** Podas del filtro de cota inferior en la ultima ejecucion completada. */
    private volatile long ultimasPodasCotaInferior;

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

    /**
     * Semilla del constructor, la que usan {@link #resolver(InstanciaPlanificacion, PresupuestoComputo)}
     * y {@link #resolverDesde}.
     */
    public long semilla() {
        return semilla;
    }

    /**
     * Posiciones de insercion que el filtro de cota inferior salto sin decodificar en la
     * ultima ejecucion completada por esta instancia, o cero si el filtro esta desactivado.
     * Es una medida de diagnostico y no forma parte del resultado.
     */
    public long podasCotaInferior() {
        return ultimasPodasCotaInferior;
    }

    /** Nombre, semilla y parametros completos, para dejar trazada la configuracion de una corrida. */
    @Override
    public String toString() {
        return NOMBRE + "[semilla=" + semilla + " " + parametros + "]";
    }

    /** Resuelve con la semilla con que se construyo la busqueda. */
    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
        return ejecutar(instancia, presupuesto, null, semilla);
    }

    /**
     * Resuelve con la semilla indicada, que sustituye a la del constructor solo en esta
     * ejecucion. Es la via por la que el motor de simulacion da a cada replanificacion su
     * propia corriente aleatoria; el resultado informa la semilla efectivamente usada.
     */
    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                           long semillaEjecucion) {
        return ejecutar(instancia, presupuesto, null, semillaEjecucion);
    }

    /** Esta busqueda si sabe partir del plan vigente, conforme al apartado 7.3.5 del ISA. */
    @Override
    public boolean admiteArranqueDesdePlanVigente() {
        return true;
    }

    /**
     * Resuelve la instancia arrancando desde un plan ya vigente en lugar de desde la
     * heuristica constructiva, con la semilla del constructor. Es el segundo modo de arranque
     * del apartado 7.3.5 del ISA y la capacidad sobre la que se sostiene la hipotesis
     * experimental del apartado 11.4.
     *
     * <p>Del plan solo se toman las asignaciones de pedido a unidad que siguen teniendo
     * sentido en la fotografia actual: se descartan las unidades que ya no estan disponibles y
     * los pedidos que ya no estan pendientes, y cada ruta se recorta hasta que resulta
     * factible con los bloqueos, los plazos y los turnos vigentes. Los pedidos que caen por
     * ese recorte quedan en el banco y la primera reconstruccion los recoloca. El motor de
     * simulacion entrega el plan ya filtrado; un plan que traiga unidades o pedidos ajenos a
     * la fotografia no rompe nada, porque {@link EstadoAlns#cargarDesde} los ignora.</p>
     *
     * @param planVigente plan de la iteracion anterior; con {@code null}, o con un plan sin
     *                    ninguna entrega, se comporta como {@link #resolver}
     */
    public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia,
                                                PresupuestoComputo presupuesto, Solucion planVigente) {
        return ejecutar(instancia, presupuesto, planVigente, semilla);
    }

    /**
     * Igual que {@link #resolverDesde(InstanciaPlanificacion, PresupuestoComputo, Solucion)}
     * con la semilla indicada por el invocante, que es como lo llama el motor de simulacion
     * cuando la configuracion del escenario activa el arranque desde el plan vigente.
     */
    @Override
    public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                                long semillaEjecucion, Solucion planVigente) {
        return ejecutar(instancia, presupuesto, planVigente, semillaEjecucion);
    }

    // ----------------------------------------------------------------- internos

    private ResultadoPlanificacion ejecutar(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                            Solucion planVigente, long semillaCorrida) {
        if (instancia.cantidadPedidos() == 0 || instancia.cantidadUnidades() == 0) {
            return sinBusqueda(instancia, presupuesto, semillaCorrida);
        }

        final Aleatorio aleatorio = new Aleatorio(semillaCorrida);
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

        // El plan vigente del apartado 11.4 se traduce a indices locales una sola vez, antes
        // de que la busqueda arranque: dentro del bucle el termino de estabilidad se resuelve
        // con un acceso a arreglo primitivo y sin comparar ningun codigo TTNN.
        final PlanVigenteAlns vigentes = new PlanVigenteAlns(instancia);
        vigente.configurarEstabilidad(vigentes, 0.0);
        candidato.configurarEstabilidad(vigentes, 0.0);
        mejor.configurarEstabilidad(vigentes, 0.0);

        arrancar(vigente, planVigente, programador, aleatorio, presupuesto, reconstruccion[0]);

        final double penalizacion = penalizacionDelBanco(vigente);
        final double pesoEstabilidad = pesoDeEstabilidad(vigente);
        vigente.pesoEstabilidad(pesoEstabilidad);
        candidato.pesoEstabilidad(pesoEstabilidad);
        mejor.pesoEstabilidad(pesoEstabilidad);

        mejor.copiarDesde(vigente);
        ValorObjetivo mejorValor = mejor.valor();
        // Compromiso del plan vigente: ningun candidato puede soltar un pedido que el plan
        // anterior atendia y que el arranque pudo conservar. Cambiarlo por otro con la misma
        // H, aunque ese otro pueda esperar horas, es justo lo que dejaba vencer pedidos que
        // ya iban en camino.
        final int topeComprometidos = vigente.comprometidosEnBanco();

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
            if (candidato.comprometidosEnBanco() > topeComprometidos) {
                sinMejora++;
                resultado = CapaAdaptativa.RECHAZADA;
            } else if (valorCandidato.mejorQue(mejorValor)) {
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
        ultimasPodasCotaInferior = motor.podasCotaInferior();
        return new ResultadoPlanificacion(NOMBRE, solucion.conValor(valorFinal), presupuesto.perfil(),
                presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semillaCorrida,
                pesosFinales(capa, destruccion, reconstruccion));
    }

    /**
     * Pesos vigentes de la capa adaptativa al cerrar la ejecucion, por nombre de operador, que
     * el apartado 7.3.3 del ISA expone para los reportes del apartado 12. Primero van los de
     * destruccion y despues los de reconstruccion, en el orden en que {@link #ejecutar} los
     * declara, de modo que el orden es el mismo en todas las ejecuciones. Se construye una
     * sola vez por ejecucion, fuera del bucle de busqueda.
     */
    private static Map<String, Double> pesosFinales(CapaAdaptativa capa, OperadorDestruccion[] destruccion,
                                                    OperadorReconstruccion[] reconstruccion) {
        Map<String, Double> pesos = new LinkedHashMap<>();
        for (int i = 0; i < destruccion.length; i++) {
            anotarPeso(pesos, destruccion[i].nombre(), capa.pesoDestruccion(i));
        }
        for (int i = 0; i < reconstruccion.length; i++) {
            anotarPeso(pesos, reconstruccion[i].nombre(), capa.pesoReconstruccion(i));
        }
        return pesos;
    }

    /** Anota un peso y rechaza un nombre repetido, que haria perder la entrada de otro operador. */
    private static void anotarPeso(Map<String, Double> pesos, String nombre, double peso) {
        if (pesos.put(nombre, peso) != null) {
            throw new IllegalStateException("Dos operadores comparten el nombre " + nombre);
        }
    }

    /**
     * Construye la solucion de partida y cierra su banco con una insercion voraz.
     *
     * <p>La heuristica constructiva se recibe por la interfaz {@link HeuristicaConstructiva} y
     * es la misma que consume la busqueda genetica hibrida, conforme al apartado 10 del ISA.
     * Si no se proporciona ninguna, o si la que se proporciona deja pedidos sin colocar, la
     * propia insercion voraz del conjunto de operadores produce la solucion inicial: el
     * algoritmo no depende de una implementacion concreta para arrancar.</p>
     *
     * <p>Un plan vigente sin ninguna entrega equivale a no tener plan y se descarta, de modo
     * que la primera replanificacion de una corrida, en la que ninguna unidad lleva todavia
     * ruta, arranca con la heuristica constructiva aunque el arranque desde el plan vigente
     * este activado.</p>
     */
    private void arrancar(EstadoAlns estado, Solucion planVigente, ProgramadorRuta programador,
                          Aleatorio aleatorio, PresupuestoComputo presupuesto,
                          OperadorReconstruccion voraz) {
        Solucion partida = planVigente != null && !planVigente.rutasConEntregas().isEmpty() ? planVigente : null;
        if (partida == null && constructiva != null) {
            programador.reiniciarInventarios();
            partida = constructiva.construir(estado.instancia(), programador, aleatorio, presupuesto);
        }
        // La busqueda valora las rutas con el inventario intacto; el consumo de la
        // construccion no puede arrastrarse.
        programador.reiniciarInventarios();
        if (partida != null) {
            estado.cargarDesde(partida);
        } else {
            estado.vaciar();
        }
        // La insercion que cierra el banco del arranque ya se guia por la estabilidad: con el
        // presupuesto de segundos del apartado 2.3 partir cerca del plan vigente vale tanto
        // como converger hacia el. El peso se estima con el costo que la partida lleva
        // acumulado y se rehace, sin perder nada de lo colocado, en cuanto el arranque cierra.
        estado.pesoEstabilidad(pesoDeEstabilidad(estado));
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
        return parametros.factorPenalizacionBanco() * costoMedioPorTarea(estado);
    }

    /**
     * Peso con que cada pedido que cambia de unidad respecto del plan vigente entra en el
     * escalar interno, conforme al apartado 11.4 del ISA. Se expresa sobre el mismo costo
     * medio que la penalizacion del banco, de modo que la razon entre los dos terminos es la
     * de sus factores y no depende de la fotografia: con los valores por defecto abandonar un
     * pedido cuesta cuarenta veces mas que reasignarlo, que es el "muy por debajo" que el
     * apartado exige.
     */
    private double pesoDeEstabilidad(EstadoAlns estado) {
        return parametros.factorPenalizacionEstabilidad() * costoMedioPorTarea(estado);
    }

    /** Costo medio por tarea colocada, con un valor de respaldo para el estado vacio. */
    private double costoMedioPorTarea(EstadoAlns estado) {
        int colocados = estado.asignados();
        return colocados > 0 && estado.costo() > 0.0 ? estado.costo() / colocados : 100.0;
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
                                               PresupuestoComputo presupuesto, long semillaCorrida) {
        Solucion vacia = Solucion.vacia(instancia);
        ValorObjetivo valor = objetivo.evaluar(instancia, vacia);
        presupuesto.cerrarPerfil(valor);
        // Sin busqueda no hay capa adaptativa que informar: no se llego a construir ninguna.
        return new ResultadoPlanificacion(NOMBRE, vacia.conValor(valor), presupuesto.perfil(),
                presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semillaCorrida, Map.of());
    }
}
