package org.kindbox.core.metaheuristica.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.construccion.HeuristicaConstructiva;
import org.kindbox.core.evaluacion.FuncionObjetivo;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Busqueda genetica hibrida del apartado 6 del ISA, segun Vidal, Crainic, Gendreau y Prins
 * (2012), Vidal, Crainic, Gendreau y Prins (2013 y 2014) y Vidal (2022).
 *
 * <p>Es el algoritmo poblacional de los dos que compara el proyecto. Su ciclo es el
 * siguiente:</p>
 * <ol>
 *   <li>seleccion de dos progenitores por torneo binario sobre la aptitud combinada de valor
 *       objetivo y contribucion a la diversidad;</li>
 *   <li>cruce ordenado sobre la permutacion de tareas, con herencia gen a gen del vector de
 *       tipos de unidad ({@link CruceOrdenado});</li>
 *   <li>decodificacion del descendiente con el {@link Split} adaptado a flota heterogenea,
 *       que corta la permutacion en rutas y las asigna a unidades concretas;</li>
 *   <li>educacion del descendiente con busqueda local granular ({@link Educacion}) y, si
 *       sigue infactible, reparacion con la penalizacion multiplicada;</li>
 *   <li>insercion en la subpoblacion factible o en la infactible, con eliminacion de clones y
 *       supervivencia garantizada de los mejores por valor objetivo ({@link Poblacion});</li>
 *   <li>ajuste periodico de la penalizacion del desfase para sostener la proporcion objetivo
 *       de individuos factibles.</li>
 * </ol>
 *
 * <h2>Poblacion inicial</h2>
 * <p>Es aleatoria salvo un individuo, construido con la heuristica constructiva comun que
 * recibe por constructor, tomada siempre por su interfaz y nunca por su clase concreta.
 * Simensen, Hasle y Stalhane (2022) muestran que dedicar entre el 5 y el 40 por ciento del
 * computo a producir ese individuo de elite mejora la convergencia y la calidad final; aqui
 * esa fraccion es un parametro y actua como tope: la fase de elite educa el individuo de
 * forma repetida mientras siga mejorando y mientras no supere esa fraccion del presupuesto,
 * y devuelve el resto del reloj al bucle principal. Si no se entrega heuristica alguna, el
 * individuo de elite se arma ordenando las tareas por holgura creciente, que es el mismo
 * criterio con el que se alimenta la heuristica.</p>
 *
 * <h2>Criterio de parada</h2>
 * <p>Agotamiento del presupuesto, numero maximo de generaciones sin mejora o numero maximo
 * de generaciones. En operacion manda siempre la primera: el presupuesto se consulta en el
 * bucle principal y dentro de la busqueda local, de modo que la interrupcion nunca tarda mas
 * de un movimiento. En todo momento se conserva la mejor solucion factible hallada, que es
 * la que se devuelve.</p>
 *
 * <h2>Solucion devuelta</h2>
 * <p>La solucion se materializa con el decodificador comun y con el consumo real del
 * inventario de los almacenes intermedios. A una ruta que no resulte factible al
 * materializarla, sea por plazo, por cierre de turno o porque el inventario compartido se
 * agoto antes de llegarle el turno, se le retiran visitas de una en una hasta volverla
 * factible, y solo las visitas retiradas elevan H. Retirar y no descartar la ruta entera es
 * lo que exige el nivel 1 del objetivo: una ruta con cinco entregas en plazo y una fuera de
 * plazo vale cinco pedidos, no cero. Asi la solucion devuelta pasa siempre el verificador de
 * restricciones del apartado 12.4 y su H refleja con honestidad lo que quedo sin atender. El
 * valor objetivo lo calcula {@link FuncionObjetivoJerarquica}, la misma implementacion que
 * consume el otro algoritmo.</p>
 *
 * <p>Que una entrega fuera de plazo no valga nada tiene una consecuencia sobre la busqueda
 * que conviene subrayar, porque separa a esta implementacion de un HGS de libro: la
 * penalizacion del desfase no es un peso pequeno que se pueda ir subiendo con calma, sino
 * que arranca alta y nunca baja de un suelo. Con una penalizacion pequena el optimo interno
 * de la busqueda es servirlo todo con horas de retraso, que al materializar el plan vale lo
 * mismo que no haber servido nada. {@link ParametrosHgs#penalizacionDesfaseInicial} recoge la
 * medicion que lo sostiene.</p>
 */
public final class BusquedaGeneticaHibrida implements Algoritmo {

    /** Nombre con el que aparece en los reportes de la experimentacion. */
    public static final String NOMBRE = "HGS";
    /** Semilla por defecto de la corrida, para que una ejecucion sin semilla sea reproducible. */
    public static final long SEMILLA_POR_DEFECTO = 20262L;
    /**
     * Fraccion de los genes del vector de tipos que arranca sesgada hacia el tipo de la unidad
     * vigente en la poblacion inicial aleatoria. La otra mitad se sortea, para no colapsar la
     * diversidad en la dimension que sostiene la distancia entre individuos del apartado 6.3.3.
     */
    private static final double PROPORCION_SESGO_TIPO_VIGENTE = 0.50;

    private final HeuristicaConstructiva heuristica;
    private final ParametrosHgs parametros;
    private final FuncionObjetivo funcionObjetivo;
    private final long semilla;

    /** Busqueda con los parametros del apartado 6.4 y la semilla por defecto. */
    public BusquedaGeneticaHibrida(HeuristicaConstructiva heuristica) {
        this(heuristica, ParametrosHgs.porDefecto(), SEMILLA_POR_DEFECTO);
    }

    /**
     * @param heuristica heuristica constructiva comun que produce el individuo de elite
     *                   inicial; admite {@code null}, en cuyo caso se ordena por holgura
     * @param parametros parametros de la corrida
     * @param semilla    semilla del generador de la corrida
     */
    public BusquedaGeneticaHibrida(HeuristicaConstructiva heuristica, ParametrosHgs parametros, long semilla) {
        if (parametros == null) {
            throw new IllegalArgumentException("La busqueda genetica hibrida necesita parametros");
        }
        this.heuristica = heuristica;
        this.parametros = parametros;
        this.funcionObjetivo = new FuncionObjetivoJerarquica();
        this.semilla = semilla;
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
        return new Corrida(instancia, presupuesto).ejecutar();
    }

    /**
     * Estado de una ejecucion. Todo el estado mutable vive aqui y no en el algoritmo, de modo
     * que una misma instancia de {@link BusquedaGeneticaHibrida} puede reutilizarse entre
     * iteraciones de planificacion sin arrastrar nada de la anterior.
     */
    private final class Corrida {

        private final InstanciaPlanificacion instancia;
        private final PresupuestoComputo presupuesto;
        private final Aleatorio aleatorio;
        private final ProgramadorRuta programador;
        private final TareasEntrega tareas;
        private final EstabilidadPlan estabilidad;
        private final Split split;
        private final Educacion educacion;
        private final CruceOrdenado cruce;
        private final Poblacion factibles;
        private final Poblacion infactibles;

        private final int cantidadTareas;
        private final int maximoRutas;

        private final Individuo descendiente;
        private final Individuo respaldo;
        private final Individuo elite;

        private final int[] pedidosBuffer;
        private final int[] cantidadesBuffer;
        private final int[] pedidosReparacion;
        private final int[] cantidadesReparacion;
        private final int[] entregado;
        private final int[] usadaDePedido;
        private final int[] marcaCota;
        private int selloCota;
        private int cotaPedidos;
        private double cotaCosto;

        private Solucion mejorSolucion;
        private ValorObjetivo mejorValor = ValorObjetivo.PEOR;

        private double pesoDesfase;
        private int descendientesRecientes;
        private int factiblesRecientes;

        Corrida(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            this.instancia = instancia;
            this.presupuesto = presupuesto;
            this.aleatorio = new Aleatorio(semilla);
            this.programador = new ProgramadorRuta(instancia);
            this.tareas = new TareasEntrega(instancia, parametros.granularidadVecindario());
            // El plan vigente se resuelve una sola vez, al arrancar la corrida, a arreglos
            // primitivos indexados por tarea: dentro del bucle de busqueda no se consulta ni el
            // mapa de la instancia ni ningun codigo TTNN.
            this.estabilidad = new EstabilidadPlan(instancia, tareas, parametros.pesoEstabilidad());
            this.split = new Split(instancia, tareas, programador, parametros, estabilidad);
            this.educacion = new Educacion(instancia, tareas, programador, aleatorio, parametros,
                    presupuesto, estabilidad);
            this.cantidadTareas = tareas.cantidad();
            this.maximoRutas = Math.max(1, instancia.cantidadUnidades());
            this.cruce = new CruceOrdenado(aleatorio, cantidadTareas);
            this.factibles = new Poblacion(parametros, cantidadTareas, maximoRutas);
            this.infactibles = new Poblacion(parametros, cantidadTareas, maximoRutas);
            this.descendiente = new Individuo(cantidadTareas, maximoRutas);
            this.respaldo = new Individuo(cantidadTareas, maximoRutas);
            this.elite = new Individuo(cantidadTareas, maximoRutas);
            this.pedidosBuffer = new int[Math.max(1, cantidadTareas)];
            this.cantidadesBuffer = new int[Math.max(1, cantidadTareas)];
            this.pedidosReparacion = new int[Math.max(1, cantidadTareas)];
            this.cantidadesReparacion = new int[Math.max(1, cantidadTareas)];
            this.entregado = new int[Math.max(1, instancia.cantidadPedidos())];
            this.usadaDePedido = new int[Math.max(1, instancia.cantidadPedidos())];
            this.marcaCota = new int[Math.max(1, instancia.cantidadPedidos())];
            this.pesoDesfase = parametros.penalizacionDesfaseInicial();
        }

        ResultadoPlanificacion ejecutar() {
            if (cantidadTareas == 0) {
                return terminar(Solucion.vacia(instancia));
            }

            construirElite();
            completarPoblacionInicial();

            long generaciones = 0;
            long sinMejora = 0;
            while (!presupuesto.agotado()
                    && generaciones < parametros.maximoGeneraciones()
                    && sinMejora < parametros.maximoGeneracionesSinMejora()) {

                if (factibles.vacia() && infactibles.vacia()) {
                    break;
                }
                Individuo primero = seleccionarPorTorneo();
                Individuo segundo = seleccionarPorTorneo();
                cruce.cruzar(primero, segundo, descendiente);
                split.ejecutar(descendiente, pesoDesfase);
                educacion.educar(descendiente, pesoDesfase);

                if (!descendiente.factible()
                        && aleatorio.conProbabilidad(parametros.probabilidadReparacion())) {
                    respaldo.copiarDe(descendiente);
                    educacion.educar(descendiente, pesoDesfase * parametros.factorPenalizacionReparacion());
                    if (!descendiente.factible()) {
                        descendiente.copiarDe(respaldo);
                    }
                }

                descendientesRecientes++;
                if (descendiente.factible()) {
                    factiblesRecientes++;
                }
                insertar(descendiente);
                boolean mejoro = registrarMejor(descendiente, false);
                // Sondeo periodico sobre los dos campeones: la reparacion de rutas que hace
                // la materializacion puede rescatar visitas que la cota entregable da por
                // perdidas, de modo que un individuo infactible puede rendir mas de lo que su
                // cota promete.
                if (generaciones % parametros.frecuenciaMaterializacion() == 0) {
                    mejoro |= sondearCampeones();
                }
                sinMejora = mejoro ? 0 : sinMejora + 1;

                generaciones++;
                presupuesto.contarIteracion();
                presupuesto.muestrear(() -> mejorValor);
                if (generaciones % parametros.frecuenciaAjustePenalizacion() == 0) {
                    ajustarPenalizacion();
                }
            }

            return terminar(mejorSolucion);
        }

        private ResultadoPlanificacion terminar(Solucion solucion) {
            Solucion resultado = solucion;
            if (resultado == null) {
                Solucion vacia = Solucion.vacia(instancia);
                resultado = vacia.conValor(funcionObjetivo.evaluar(instancia, vacia));
            }
            presupuesto.cerrarPerfil(resultado.valor());
            return new ResultadoPlanificacion(NOMBRE, resultado, presupuesto.perfil(),
                    presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semilla);
        }

        // ------------------------------------------------------ poblacion inicial

        /**
         * Individuo de elite: cromosoma tomado de la heuristica constructiva comun, o de la
         * ordenacion por holgura creciente si no hay heuristica, decodificado y educado de
         * forma repetida mientras siga mejorando y no se agote la fraccion de presupuesto
         * reservada para esta fase.
         */
        private void construirElite() {
            Solucion semillaConstructiva = null;
            if (heuristica != null) {
                programador.reiniciarInventarios();
                semillaConstructiva = heuristica.construir(instancia, programador, aleatorio, presupuesto);
                programador.reiniciarInventarios();
            }
            if (semillaConstructiva != null) {
                cromosomaDesdeSolucion(semillaConstructiva, elite);
            } else {
                cromosomaPorHolgura(elite);
            }

            split.ejecutar(elite, pesoDesfase);
            educacion.educar(elite, pesoDesfase);
            registrarMejor(elite);

            double anterior = elite.costoInterno(pesoDesfase, parametros.pesoEstabilidad());
            while (!presupuesto.agotado() && presupuesto.fraccionConsumida() < parametros.esfuerzoElite()) {
                educacion.educar(elite, pesoDesfase);
                double actual = elite.costoInterno(pesoDesfase, parametros.pesoEstabilidad());
                if (actual >= anterior - 1e-7) {
                    break;
                }
                anterior = actual;
                registrarMejor(elite);
            }
            registrarMejor(elite);
            insertar(elite);
        }

        /** Rellena las dos subpoblaciones con individuos aleatorios hasta el tamano minimo. */
        private void completarPoblacionInicial() {
            final int objetivo = parametros.tamanoMinimoPoblacion();
            int intentos = 0;
            final int maximoIntentos = 2 * objetivo;
            // La inicializacion nunca se come mas de la mitad del reloj: con presupuestos de
            // dos segundos, poblar es util pero evolucionar lo es mas.
            while (!presupuesto.agotado() && intentos < maximoIntentos
                    && presupuesto.fraccionConsumida() < 0.5
                    && (factibles.cantidad() < objetivo || infactibles.cantidad() < objetivo)
                    && factibles.cantidad() + infactibles.cantidad() < 2 * objetivo) {
                cromosomaAleatorio(descendiente);
                split.ejecutar(descendiente, pesoDesfase);
                educacion.educar(descendiente, pesoDesfase);
                insertar(descendiente);
                registrarMejor(descendiente);
                intentos++;
                presupuesto.muestrear(() -> mejorValor);
            }
        }

        /**
         * Cromosoma con las tareas en orden aleatorio y tipos admisibles al azar, sesgados
         * hacia el <b>tipo de la unidad vigente</b> de cada tarea.
         *
         * <p>El vector de tipos es lo que decide que grafos por tipo considera el Split para
         * cada arco, de modo que sesgarlo hacia el tipo que ya atendia el pedido acerca la
         * particion inicial al plan vigente y le ahorra a la busqueda tener que reconquistar
         * esa estructura movimiento a movimiento. El sesgo se aplica a la mitad de los genes y
         * no a todos porque el vector de tipos es la mitad de la distancia entre individuos del
         * apartado 6.3.3: fijarlo entero colapsaria la diversidad de la poblacion inicial justo
         * en la dimension que la sostiene en una flota heterogenea.</p>
         */
        private void cromosomaAleatorio(Individuo individuo) {
            final int[] permutacion = individuo.permutacion();
            final byte[] tipo = individuo.tipo();
            for (int t = 0; t < cantidadTareas; t++) {
                permutacion[t] = t;
                int vigente = estabilidad.tipoVigente(t);
                boolean sesgar = vigente >= 0 && split.tipoAdmisible(vigente, t)
                        && aleatorio.conProbabilidad(PROPORCION_SESGO_TIPO_VIGENTE);
                tipo[t] = (byte) (sesgar ? vigente : split.tipoAleatorioAdmisible(aleatorio, t));
            }
            aleatorio.barajar(permutacion, cantidadTareas);
        }

        /**
         * Cromosoma con las tareas ordenadas por holgura creciente, que es el orden con el
         * que el apartado 6.3.1 alimenta la heuristica constructiva. Los tipos se reparten de
         * forma ciclica entre los admisibles, para que el Split arranque con los tres grafos
         * por tipo a la vista y no encerrado en uno solo.
         */
        private void cromosomaPorHolgura(Individuo individuo) {
            final int[] permutacion = individuo.permutacion();
            for (int t = 0; t < cantidadTareas; t++) {
                permutacion[t] = t;
            }
            ordenarPorHolgura(permutacion, 0, cantidadTareas);
            asignarTiposCiclicos(individuo);
        }

        /** Ordenacion por insercion del tramo dado por instante limite creciente. */
        private void ordenarPorHolgura(int[] orden, int desde, int hasta) {
            for (int i = desde + 1; i < hasta; i++) {
                int actual = orden[i];
                long clave = tareas.limite(actual);
                int j = i - 1;
                while (j >= desde && tareas.limite(orden[j]) > clave) {
                    orden[j + 1] = orden[j];
                    j--;
                }
                orden[j + 1] = actual;
            }
        }

        /** Reparte los tipos admisibles de forma ciclica sobre las tareas. */
        private void asignarTiposCiclicos(Individuo individuo) {
            final byte[] tipo = individuo.tipo();
            int siguiente = 0;
            for (int t = 0; t < cantidadTareas; t++) {
                int elegido = -1;
                for (int intento = 0; intento < 3; intento++) {
                    int candidato = (siguiente + intento) % 3;
                    if (split.tipoAdmisible(candidato, t)) {
                        elegido = candidato;
                        siguiente = candidato + 1;
                        break;
                    }
                }
                tipo[t] = (byte) (elegido >= 0 ? elegido : split.tipoAleatorioAdmisible(aleatorio, t));
            }
        }

        /**
         * Traduce un plan de la heuristica constructiva a cromosoma. Se conserva el orden en
         * que sus rutas visitan los pedidos y el tipo de unidad que las atiende; las tareas
         * que el plan dejo sin atender se anaden al final por holgura creciente. No se copian
         * las cantidades: el reparto en tareas lo fija {@link TareasEntrega} y el Split
         * vuelve a decidir los cortes.
         */
        private void cromosomaDesdeSolucion(Solucion solucion, Individuo individuo) {
            final int[] permutacion = individuo.permutacion();
            final byte[] tipo = individuo.tipo();
            asignarTiposCiclicos(individuo);
            Arrays.fill(usadaDePedido, 0, instancia.cantidadPedidos(), 0);

            int n = 0;
            for (Ruta ruta : solucion.rutas()) {
                byte tipoRuta = (byte) ruta.tipoUnidad().ordinal();
                for (Parada parada : ruta.paradas()) {
                    if (parada.tipo() != TipoParada.ENTREGA) {
                        continue;
                    }
                    int pedido = instancia.indiceDePedido(parada.idPedido());
                    if (pedido < 0 || usadaDePedido[pedido] >= tareas.tareasDePedido(pedido)) {
                        continue;
                    }
                    int tarea = tareas.primeraDePedido(pedido) + usadaDePedido[pedido];
                    usadaDePedido[pedido]++;
                    permutacion[n++] = tarea;
                    if (split.tipoAdmisible(tipoRuta, tarea)) {
                        tipo[tarea] = tipoRuta;
                    }
                }
            }
            final int inicioResto = n;
            for (int pedido = 0; pedido < instancia.cantidadPedidos(); pedido++) {
                for (int k = usadaDePedido[pedido]; k < tareas.tareasDePedido(pedido); k++) {
                    permutacion[n++] = tareas.primeraDePedido(pedido) + k;
                }
            }
            // El tramo heredado del plan conserva su orden; solo se ordena por holgura la
            // cola de tareas que el plan no llego a atender.
            ordenarPorHolgura(permutacion, inicioResto, n);
        }

        // ----------------------------------------------------------- ciclo genetico

        /**
         * Torneo binario sobre la aptitud combinada. Los dos candidatos se sortean sobre la
         * union de las dos subpoblaciones, de modo que un individuo infactible puede ser
         * progenitor: es la via por la que la busqueda atraviesa la frontera de factibilidad.
         */
        private Individuo seleccionarPorTorneo() {
            Individuo primero = sortearIndividuo();
            Individuo segundo = sortearIndividuo();
            if (segundo == null) {
                return primero;
            }
            if (primero == null) {
                return segundo;
            }
            return primero.aptitud() <= segundo.aptitud() ? primero : segundo;
        }

        private Individuo sortearIndividuo() {
            final int total = factibles.cantidad() + infactibles.cantidad();
            if (total == 0) {
                return null;
            }
            int indice = aleatorio.siguienteEntero(total);
            return indice < factibles.cantidad()
                    ? factibles.individuo(indice)
                    : infactibles.individuo(indice - factibles.cantidad());
        }

        private void insertar(Individuo individuo) {
            Poblacion destino = individuo.factible() ? factibles : infactibles;
            destino.insertar(individuo);
            destino.actualizarAptitudes();
        }

        /**
         * Ajuste de la penalizacion del desfase segun la proporcion de descendientes
         * factibles producidos desde el ajuste anterior. Es el control que mantiene a las dos
         * subpoblaciones pobladas: con la penalizacion demasiado baja todo el mundo cruza la
         * frontera y con ella demasiado alta nadie la roza.
         */
        private void ajustarPenalizacion() {
            if (descendientesRecientes == 0) {
                return;
            }
            double proporcion = (double) factiblesRecientes / descendientesRecientes;
            double objetivo = parametros.proporcionObjetivoFactibles();
            double holgura = parametros.holguraProporcionFactibles();
            if (proporcion < objetivo - holgura) {
                pesoDesfase = Math.min(parametros.penalizacionDesfaseMaxima(),
                        pesoDesfase * parametros.factorAumentoPenalizacion());
            } else if (proporcion > objetivo + holgura) {
                pesoDesfase = Math.max(parametros.penalizacionDesfaseMinima(),
                        pesoDesfase * parametros.factorReduccionPenalizacion());
            }
            descendientesRecientes = 0;
            factiblesRecientes = 0;
        }

        // -------------------------------------------------------- mejor solucion

        /**
         * Materializa el individuo si promete mejorar la incumbente y se queda con la mejor
         * de las dos. Devuelve {@code true} si la mejor solucion conocida cambio.
         *
         * <p>El filtro previo no es la factibilidad del individuo entero sino la <b>cota
         * entregable</b>: H y costo contando solo las rutas sin desfase. Es una cota
         * conservadora, porque al materializar una ruta con desfase se le retiran visitas
         * hasta volverla factible en lugar de descartarla entera, de modo que el resultado
         * nunca es peor que la cota. Con ella un individuo de la subpoblacion infactible
         * puede aportar la mejor solucion conocida, que es lo que hace util a esa
         * subpoblacion cuando la instancia esta apretada de plazos.</p>
         */
        private boolean registrarMejor(Individuo individuo, boolean sondeo) {
            calcularCotaEntregable(individuo);
            if (mejorSolucion != null && !sondeo) {
                boolean mejora = cotaPedidos < mejorValor.pedidosNoAtendidos()
                        || (cotaPedidos == mejorValor.pedidosNoAtendidos()
                            && cotaCosto < mejorValor.costo() - 1e-9);
                if (!mejora) {
                    return false;
                }
            }
            Solucion candidata = materializar(individuo);
            if (mejorSolucion == null || candidata.valor().mejorQue(mejorValor)) {
                mejorSolucion = candidata;
                mejorValor = candidata.valor();
                return true;
            }
            return false;
        }

        private boolean registrarMejor(Individuo individuo) {
            return registrarMejor(individuo, false);
        }

        /** Materializa el mejor individuo de cada subpoblacion, sin exigirles cota mejor. */
        private boolean sondearCampeones() {
            boolean mejoro = false;
            Individuo campeonFactible = factibles.mejor();
            if (campeonFactible != null) {
                mejoro = registrarMejor(campeonFactible, true);
            }
            Individuo campeonInfactible = infactibles.mejor();
            if (campeonInfactible != null) {
                mejoro |= registrarMejor(campeonInfactible, true);
            }
            return mejoro;
        }

        /**
         * H y costo del individuo contando solo sus rutas sin desfase. Las tareas de las
         * rutas con desfase se cuentan como no atendidas, que es el peor caso de la
         * materializacion.
         */
        private void calcularCotaEntregable(Individuo individuo) {
            selloCota++;
            int pedidos = 0;
            double costo = 0.0;
            for (int i = 0; i < individuo.cantidadBanco(); i++) {
                int pedido = tareas.pedido(individuo.banco()[i]);
                if (marcaCota[pedido] != selloCota) {
                    marcaCota[pedido] = selloCota;
                    pedidos++;
                }
            }
            final int[] visitas = individuo.visitas();
            final int[] inicio = individuo.rutaInicio();
            final int[] longitud = individuo.rutaLongitud();
            for (int r = 0; r < individuo.cantidadRutas(); r++) {
                if (individuo.rutaDesfase()[r] == 0) {
                    costo += individuo.rutaCosto()[r];
                    continue;
                }
                for (int i = 0; i < longitud[r]; i++) {
                    int pedido = tareas.pedido(visitas[inicio[r] + i]);
                    if (marcaCota[pedido] != selloCota) {
                        marcaCota[pedido] = selloCota;
                        pedidos++;
                    }
                }
            }
            cotaPedidos = pedidos;
            cotaCosto = costo;
        }

        /**
         * Construye la solucion concreta del individuo con el decodificador comun,
         * consumiendo el inventario de los almacenes intermedios ruta a ruta. Solo entran las
         * rutas que resultan factibles con la unidad y el inventario que les toca; las tareas
         * de las demas quedan sin atender y elevan H.
         */
        private Solucion materializar(Individuo individuo) {
            programador.reiniciarInventarios();
            Arrays.fill(entregado, 0, instancia.cantidadPedidos(), 0);
            List<Ruta> rutas = new ArrayList<>(individuo.cantidadRutas());

            final int[] visitas = individuo.visitas();
            final int[] inicio = individuo.rutaInicio();
            final int[] longitud = individuo.rutaLongitud();
            final int[] unidades = individuo.rutaUnidad();
            for (int r = 0; r < individuo.cantidadRutas(); r++) {
                final int largo = longitud[r];
                if (largo == 0) {
                    continue;
                }
                for (int i = 0; i < largo; i++) {
                    int tarea = visitas[inicio[r] + i];
                    pedidosBuffer[i] = tareas.pedido(tarea);
                    cantidadesBuffer[i] = tareas.cantidadUnidades(tarea);
                }
                // Se tantea sin consumir inventario y solo se confirma lo que resulta factible,
                // para no gastar inventario compartido en una ruta que se va a descartar.
                int reparada = repararRuta(unidades[r], largo);
                if (reparada <= 0) {
                    continue;
                }
                Programacion programacion =
                        programador.programar(unidades[r], pedidosBuffer, cantidadesBuffer, reparada, true);
                if (programacion.sinProgramacion() || !programacion.factible()) {
                    continue;
                }
                rutas.add(programacion.ruta());
                for (int i = 0; i < reparada; i++) {
                    entregado[pedidosBuffer[i]] += cantidadesBuffer[i];
                }
            }
            programador.reiniciarInventarios();

            Map<Integer, Integer> banco = new LinkedHashMap<>();
            for (int pedido = 0; pedido < instancia.cantidadPedidos(); pedido++) {
                int falta = instancia.pedidoCantidad(pedido) - entregado[pedido];
                if (falta > 0) {
                    banco.put(instancia.pedidoId(pedido), falta);
                }
            }
            Solucion solucion = new Solucion(rutas, banco, ValorObjetivo.PEOR);
            return solucion.conValor(funcionObjetivo.evaluar(instancia, solucion));
        }

        /**
         * Deja en los arreglos de trabajo el prefijo mas largo de la ruta que resulta
         * factible, retirando de una en una las visitas que la vuelven infactible. Retirar la
         * visita cuya ausencia mas reduce el desfase, y no la ruta entera, conserva las
         * entregas que si llegaban a tiempo, que es lo que exige el nivel 1 del objetivo:
         * cada visita rescatada es un pedido menos en H.
         *
         * @return numero de visitas que quedan, cero si la ruta no se puede salvar
         */
        private int repararRuta(int unidad, int longitud) {
            int actual = longitud;
            while (actual > 0) {
                if (programador.evaluar(unidad, pedidosBuffer, cantidadesBuffer, actual)
                        && programador.ultimaFactible()) {
                    return actual;
                }
                int mejorRetirada = -1;
                int mejorDesfase = Integer.MAX_VALUE;
                double mejorCosto = Double.POSITIVE_INFINITY;
                for (int quitada = 0; quitada < actual; quitada++) {
                    int n = 0;
                    for (int i = 0; i < actual; i++) {
                        if (i == quitada) {
                            continue;
                        }
                        pedidosReparacion[n] = pedidosBuffer[i];
                        cantidadesReparacion[n] = cantidadesBuffer[i];
                        n++;
                    }
                    if (!programador.evaluar(unidad, pedidosReparacion, cantidadesReparacion, n)) {
                        continue;
                    }
                    int desfase = programador.ultimoDesfase();
                    double costo = programador.ultimoCosto();
                    if (desfase < mejorDesfase || (desfase == mejorDesfase && costo < mejorCosto)) {
                        mejorDesfase = desfase;
                        mejorCosto = costo;
                        mejorRetirada = quitada;
                    }
                }
                if (mejorRetirada < 0) {
                    return 0;
                }
                int n = 0;
                for (int i = 0; i < actual; i++) {
                    if (i == mejorRetirada) {
                        continue;
                    }
                    pedidosBuffer[n] = pedidosBuffer[i];
                    cantidadesBuffer[n] = cantidadesBuffer[i];
                    n++;
                }
                actual = n;
            }
            return 0;
        }
    }
}
