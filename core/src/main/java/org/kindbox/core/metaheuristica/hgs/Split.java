package org.kindbox.core.metaheuristica.hgs;

import java.util.Arrays;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Split adaptado a la flota heterogenea de PaqRap, conforme al apartado 6.3.2 del ISA.
 *
 * <p>El Split de Prins (2004), en la forma lineal de Vidal (2016), convierte una permutacion
 * sin delimitadores en el mejor conjunto de rutas que respeta ese orden: se construye un
 * grafo auxiliar aciclico en el que el arco {@code (i,j)} representa una ruta que atiende
 * las tareas {@code i+1..j}, y el camino minimo de {@code 0} a {@code n} da la particion
 * optima. Aqui esa idea se adapta en cuatro puntos, que son el grueso del trabajo de diseno
 * de este algoritmo.</p>
 *
 * <h2>1. Costo de arco: el decodificador, no una formula</h2>
 * <p>El costo de un arco es exactamente lo que devuelve {@link ProgramadorRuta#evaluar} para
 * esa subsecuencia: paradas de abastecimiento intercaladas donde la carga no alcanza, pausa
 * de alimentacion ubicada por enumeracion exacta e instantes de llegada con la velocidad
 * vigente del tipo. El Split no resuelve por su cuenta ni el abastecimiento ni la pausa. Se
 * usa siempre el camino rapido {@code evaluar}, que no construye objetos y no consume el
 * inventario compartido de los almacenes intermedios, porque valorar un arco es tantear un
 * candidato y no fijar un plan.</p>
 *
 * <h2>2. Un grafo por tipo, y el vector de tipos del cromosoma como filtro</h2>
 * <p>Con flota heterogenea el costo de una ruta depende de la capacidad, la velocidad y el
 * costo por kilometro del tipo que la atiende, de modo que hay un grafo por tipo y el Split
 * resuelve sobre su union. Los tipos candidatos de un arco no son los tres siempre: son los
 * <b>tipos que el cromosoma asigna a las tareas del segmento</b>. Esa es la funcion del
 * vector de tipos del apartado 6.3.1 y lo que lo vuelve una variable de decision real,
 * sometida al cruce y a la seleccion. Ademas abarata el Split, porque un individuo maduro
 * suele tener segmentos de un solo tipo.</p>
 *
 * <h2>3. Disponibilidad de unidades por turno: relajacion lagrangiana</h2>
 * <p>Hay 10 autos, 15 motos y 12 bicicletas, y ese limite es activo. El estado exacto de la
 * programacion dinamica seria {@code (tareas procesadas, rutas abiertas por tipo)}, con
 * {@code n x 11 x 16 x 13} etiquetas, es decir del orden de medio millon de etiquetas por
 * decodificacion para un centenar de tareas; con miles de decodificaciones dentro de un
 * presupuesto de entre 2 y 18 segundos eso es inviable. <b>Se elige una relajacion
 * lagrangiana</b> de la restriccion de cardinalidad por tipo: el estado vuelve a ser solo la
 * posicion, cada arco de tipo {@code t} paga un multiplicador {@code u[t]} y el multiplicador
 * se sube por subgradiente mientras el tipo se use por encima de su cuota. La decision se
 * apoya en dos hechos: los costos de arco se evaluan <b>una sola vez</b> y se guardan, de
 * modo que cada iteracion de subgradiente cuesta un recorrido lineal sobre arcos ya
 * valorados y no una nueva tanda de decodificaciones; y la cuota rara vez se roza, porque
 * una ruta no pasa de siete u ocho paradas y las tareas de una iteracion no suelen llenar la
 * flota. Cuando el subgradiente no llega a respetar la cuota, la asignacion a unidades
 * concretas remata el trabajo: una ruta que se queda sin unidad de su tipo prueba con
 * unidades de otro tipo y, si tampoco las hay, sus tareas pasan al banco, con lo que la
 * solucion devuelta nunca declara mas unidades de las que existen.</p>
 *
 * <h2>4. La dependencia circular unidad-ruta</h2>
 * <p>El costo de una ruta depende de la unidad concreta que la atienda, y la unidad no se
 * conoce hasta que la ruta deja de crecer. Se rompe en dos tiempos, tal como prescribe el
 * apartado 6.3.2. Al valorar un arco se supone que la ruta arranca en el <b>almacen mas
 * cercano a su primera tarea</b>, y se usa como representante del tipo la unidad de ese tipo
 * mas proxima a ese almacen. Una vez fijada la particion, cada ruta se asigna a una unidad
 * concreta y se <b>reevalua con el decodificador</b>, de modo que el costo, el desfase y los
 * instantes de la solucion devuelta son los de la unidad que realmente la atiende y no los
 * del representante.</p>
 *
 * <h2>Tareas sin particion factible</h2>
 * <p>Cuando un segmento no admite ninguna programacion, sus tareas no se rechazan de plano:
 * el grafo lleva ademas un arco de salto por posicion, que deja una tarea sin asignar al
 * precio de {@link ParametrosHgs#PENALIZACION_TAREA_NO_ATENDIDA}, es decir el peso del nivel
 * 1 del objetivo. Asi el camino minimo siempre existe y las tareas afectadas incrementan H
 * en lugar de invalidar el individuo entero.</p>
 *
 * <p>La clase mantiene arreglos de trabajo como campos de instancia y no es segura para uso
 * concurrente: cada hilo de busqueda necesita su propio Split y su propio
 * {@link ProgramadorRuta}.</p>
 */
public final class Split {

    /** Numero de tipos de unidad de la flota. */
    private static final int TIPOS = 3;
    /** Mascara con los tres tipos presentes. */
    private static final int MASCARA_COMPLETA = (1 << TIPOS) - 1;
    /** Valor con el que se marca un arco inexistente. */
    private static final double INFINITO = Double.MAX_VALUE / 4.0;

    private final InstanciaPlanificacion instancia;
    private final TareasEntrega tareas;
    private final ProgramadorRuta programador;
    private final ParametrosHgs parametros;

    private final int cantidadUnidades;
    private final int cantidadAlmacenes;
    private final int longitudMaxima;

    /** Unidades disponibles de cada tipo. Es la cuota que la relajacion trata de respetar. */
    private final int[] cuota;
    /** Unidad representante de cada par tipo-almacen, o {@code -1} si el tipo no tiene unidades. */
    private final int[] representante;
    /** Capacidad de cada tipo, para descartar tareas que no caben en una visita. */
    private final int[] capacidadTipo;

    // Arcos del grafo auxiliar, valorados una sola vez por decodificacion.
    private final double[] costoArco;
    private final int[] desfaseArco;
    private final boolean[] arcoValido;

    // Programacion dinamica.
    private final double[] mejor;
    private final int[] predecesorLongitud;
    private final int[] predecesorTipo;
    private final double[] multiplicador;
    private final int[] usadasPorTipo;

    // Reconstruccion de la particion.
    private final int[] rutaInicioTmp;
    private final int[] rutaLongitudTmp;
    private final int[] rutaTipoTmp;
    private final int[] bancoTmp;
    private int cantidadRutasTmp;
    private int cantidadBancoTmp;
    private double costoPuroTmp;

    private final int[] mejorInicio;
    private final int[] mejorLongitud;
    private final int[] mejorTipo;
    private final int[] mejorBanco;
    private int mejorCantidadRutas;
    private int mejorCantidadBanco;

    // Asignacion de rutas a unidades concretas.
    private final boolean[] libre;
    private final int[] orden;
    private final long[] urgencia;
    private final int[] candidatos;
    private final int[] distanciaCandidato;
    private final int[] pedidosBuffer;
    private final int[] cantidadesBuffer;
    private final int[] marcaPedido;
    private int sello;

    public Split(InstanciaPlanificacion instancia, TareasEntrega tareas,
                 ProgramadorRuta programador, ParametrosHgs parametros) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.programador = programador;
        this.parametros = parametros;
        this.cantidadUnidades = instancia.cantidadUnidades();
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();

        final int m = Math.max(1, tareas.cantidad());
        this.longitudMaxima = Math.max(1, Math.min(parametros.longitudMaximaArco(), m));

        this.cuota = new int[TIPOS];
        this.capacidadTipo = new int[TIPOS];
        for (TipoUnidad t : TipoUnidad.values()) {
            capacidadTipo[t.ordinal()] = t.capacidad();
        }
        for (int u = 0; u < cantidadUnidades; u++) {
            cuota[instancia.unidadTipo(u).ordinal()]++;
        }
        this.representante = calcularRepresentantes();

        int arcos = m * longitudMaxima * TIPOS;
        this.costoArco = new double[arcos];
        this.desfaseArco = new int[arcos];
        this.arcoValido = new boolean[arcos];

        this.mejor = new double[m + 1];
        this.predecesorLongitud = new int[m + 1];
        this.predecesorTipo = new int[m + 1];
        this.multiplicador = new double[TIPOS];
        this.usadasPorTipo = new int[TIPOS];

        this.rutaInicioTmp = new int[m];
        this.rutaLongitudTmp = new int[m];
        this.rutaTipoTmp = new int[m];
        this.bancoTmp = new int[m];
        this.mejorInicio = new int[m];
        this.mejorLongitud = new int[m];
        this.mejorTipo = new int[m];
        this.mejorBanco = new int[m];

        this.libre = new boolean[Math.max(1, cantidadUnidades)];
        this.orden = new int[m];
        this.urgencia = new long[m];
        this.candidatos = new int[Math.max(1, parametros.candidatosUnidadPorRuta())];
        this.distanciaCandidato = new int[candidatos.length];
        this.pedidosBuffer = new int[m];
        this.cantidadesBuffer = new int[m];
        this.marcaPedido = new int[Math.max(1, instancia.cantidadPedidos())];
    }

    // ------------------------------------------------------------------ tipos

    /**
     * Indica si el tipo puede atender la tarea, es decir si hay unidades de ese tipo y si su
     * capacidad cubre la cantidad de la visita. Una visita que no cabe en la unidad es
     * rechazada por el decodificador, de modo que asignar ese tipo condenaria a la tarea al
     * banco.
     */
    public boolean tipoAdmisible(int tipo, int tarea) {
        return cuota[tipo] > 0 && capacidadTipo[tipo] >= tareas.cantidadUnidades(tarea);
    }

    /**
     * Tipo admisible elegido al azar para la tarea. Si ninguno lo es, devuelve el de mayor
     * capacidad, para que el vector de tipos siempre este definido.
     */
    public int tipoAleatorioAdmisible(Aleatorio aleatorio, int tarea) {
        int candidatosValidos = 0;
        for (int t = 0; t < TIPOS; t++) {
            if (tipoAdmisible(t, tarea)) {
                candidatosValidos++;
            }
        }
        if (candidatosValidos == 0) {
            return tipoDeMayorCapacidad();
        }
        int elegido = aleatorio.siguienteEntero(candidatosValidos);
        for (int t = 0; t < TIPOS; t++) {
            if (tipoAdmisible(t, tarea) && elegido-- == 0) {
                return t;
            }
        }
        return tipoDeMayorCapacidad();
    }

    /** Unidades disponibles del tipo. */
    public int cuota(int tipo) {
        return cuota[tipo];
    }

    private int tipoDeMayorCapacidad() {
        int mejorTipoLocal = 0;
        for (int t = 1; t < TIPOS; t++) {
            if (capacidadTipo[t] > capacidadTipo[mejorTipoLocal]) {
                mejorTipoLocal = t;
            }
        }
        return mejorTipoLocal;
    }

    // -------------------------------------------------------------- ejecucion

    /**
     * Corta la permutacion del individuo en rutas, las asigna a unidades concretas y deja en
     * el individuo la decodificacion completa: rutas, banco, costo, desfase y H.
     *
     * @param individuo    individuo cuya permutacion y vector de tipos se decodifican
     * @param pesoDesfase  penalizacion vigente de cada minuto de violacion temporal
     */
    public void ejecutar(Individuo individuo, double pesoDesfase) {
        final int m = tareas.cantidad();
        if (m == 0 || cantidadUnidades == 0) {
            dejarTodoEnBanco(individuo);
            return;
        }
        valorarArcos(individuo.permutacion(), individuo.tipo());
        resolverConCuotas(pesoDesfase);
        asignarUnidades(individuo, pesoDesfase);
    }

    /** Decodificacion degenerada: sin tareas o sin unidades no hay ruta posible. */
    private void dejarTodoEnBanco(Individuo individuo) {
        final int m = tareas.cantidad();
        int[] banco = individuo.banco();
        for (int i = 0; i < m; i++) {
            banco[i] = individuo.permutacion()[i];
        }
        Arrays.fill(individuo.sucesor(), 0, m, -1);
        individuo.cantidadRutas(0);
        individuo.cantidadBanco(m);
        individuo.pedidosNoAtendidos(contarPedidos(banco, m));
        individuo.costo(0.0);
        individuo.desfase(0);
    }

    // ------------------------------------------------------- valoracion de arcos

    /**
     * Valora todos los arcos del grafo auxiliar con el decodificador. Es la parte cara de la
     * decodificacion, y por eso se hace una sola vez: las iteraciones de subgradiente
     * posteriores reutilizan estos valores.
     */
    private void valorarArcos(int[] permutacion, byte[] tipo) {
        final int m = tareas.cantidad();
        for (int i = 0; i < m; i++) {
            final int almacen = tareas.almacenCercano(permutacion[i]);
            final int maximo = Math.min(longitudMaxima, m - i);
            int mascara = 0;
            for (int l = 1; l <= maximo; l++) {
                final int tarea = permutacion[i + l - 1];
                pedidosBuffer[l - 1] = tareas.pedido(tarea);
                cantidadesBuffer[l - 1] = tareas.cantidadUnidades(tarea);
                mascara |= 1 << tipo[tarea];

                boolean alguno = false;
                int menorDesfase = Integer.MAX_VALUE;
                final int base = indiceArco(i, l, 0);
                for (int t = 0; t < TIPOS; t++) {
                    final int indice = base + t;
                    arcoValido[indice] = false;
                    if ((mascara & (1 << t)) == 0) {
                        continue;
                    }
                    final int unidad = representante[t * cantidadAlmacenes + almacen];
                    if (unidad < 0) {
                        continue;
                    }
                    if (!programador.evaluar(unidad, pedidosBuffer, cantidadesBuffer, l)) {
                        continue;
                    }
                    arcoValido[indice] = true;
                    costoArco[indice] = programador.ultimoCosto();
                    desfaseArco[indice] = programador.ultimoDesfase();
                    menorDesfase = Math.min(menorDesfase, desfaseArco[indice]);
                    alguno = true;
                }
                // Poda. Si ningun tipo de la flota puede recorrer el segmento, alargarlo
                // tampoco lo hara recorrible; y un segmento que ya incumple plazos por mas
                // del margen admitido solo empeora al anadirle visitas.
                if (!alguno && mascara == MASCARA_COMPLETA) {
                    break;
                }
                if (alguno && menorDesfase > parametros.desfaseMaximoDeArco()) {
                    break;
                }
            }
        }
    }

    private int indiceArco(int inicio, int longitud, int tipo) {
        return ((inicio * longitudMaxima) + (longitud - 1)) * TIPOS + tipo;
    }

    // ------------------------------------------------- programacion dinamica

    /**
     * Camino minimo con relajacion lagrangiana de la cuota de unidades por tipo. Repite el
     * camino minimo subiendo por subgradiente el multiplicador de los tipos que se usan por
     * encima de su cuota, y se queda con la mejor particion vista: primero la de menor
     * exceso de unidades, y a igualdad de exceso la de menor costo.
     */
    private void resolverConCuotas(double pesoDesfase) {
        Arrays.fill(multiplicador, 0.0);
        int mejorExceso = Integer.MAX_VALUE;
        double mejorCosto = Double.POSITIVE_INFINITY;

        for (int iteracion = 0; iteracion < parametros.iteracionesLagrangiana(); iteracion++) {
            resolverCaminoMinimo(pesoDesfase);
            reconstruir();
            int exceso = 0;
            for (int t = 0; t < TIPOS; t++) {
                exceso += Math.max(0, usadasPorTipo[t] - cuota[t]);
            }
            if (exceso < mejorExceso || (exceso == mejorExceso && costoPuroTmp < mejorCosto)) {
                mejorExceso = exceso;
                mejorCosto = costoPuroTmp;
                guardarParticion();
            }
            if (exceso == 0) {
                break;
            }
            final double paso = Math.max(1.0, costoPuroTmp / Math.max(1, cantidadRutasTmp));
            for (int t = 0; t < TIPOS; t++) {
                int excesoTipo = usadasPorTipo[t] - cuota[t];
                if (excesoTipo > 0) {
                    multiplicador[t] += paso * excesoTipo / Math.max(1, cuota[t]);
                } else {
                    multiplicador[t] = Math.max(0.0, multiplicador[t] - paso * 0.25);
                }
            }
        }
        restaurarParticion();
    }

    /** Camino minimo sobre el grafo aciclico, con los multiplicadores vigentes. */
    private void resolverCaminoMinimo(double pesoDesfase) {
        final int m = tareas.cantidad();
        Arrays.fill(mejor, 0, m + 1, INFINITO);
        mejor[0] = 0.0;
        for (int j = 0; j <= m; j++) {
            predecesorLongitud[j] = 0;
            predecesorTipo[j] = -1;
        }
        for (int j = 0; j < m; j++) {
            final double base = mejor[j];
            if (base >= INFINITO) {
                continue;
            }
            // Arco de salto: la tarea queda sin asignar y paga el peso del nivel 1.
            double conSalto = base + ParametrosHgs.PENALIZACION_TAREA_NO_ATENDIDA;
            if (conSalto < mejor[j + 1]) {
                mejor[j + 1] = conSalto;
                predecesorLongitud[j + 1] = 1;
                predecesorTipo[j + 1] = -1;
            }
            final int maximo = Math.min(longitudMaxima, m - j);
            for (int l = 1; l <= maximo; l++) {
                final int indiceBase = indiceArco(j, l, 0);
                for (int t = 0; t < TIPOS; t++) {
                    final int indice = indiceBase + t;
                    if (!arcoValido[indice]) {
                        continue;
                    }
                    double valor = base + costoArco[indice]
                            + pesoDesfase * desfaseArco[indice] + multiplicador[t];
                    if (valor < mejor[j + l]) {
                        mejor[j + l] = valor;
                        predecesorLongitud[j + l] = l;
                        predecesorTipo[j + l] = t;
                    }
                }
            }
        }
    }

    /** Recorre el camino minimo hacia atras y deja la particion en los arreglos temporales. */
    private void reconstruir() {
        final int m = tareas.cantidad();
        Arrays.fill(usadasPorTipo, 0);
        cantidadRutasTmp = 0;
        cantidadBancoTmp = 0;
        costoPuroTmp = 0.0;
        int j = m;
        while (j > 0) {
            int longitud = predecesorLongitud[j];
            int tipo = predecesorTipo[j];
            if (longitud <= 0) {
                // Defensa: sin arco de entrada la posicion se trata como salto.
                longitud = 1;
                tipo = -1;
            }
            int inicio = j - longitud;
            if (tipo < 0) {
                bancoTmp[cantidadBancoTmp++] = inicio;
            } else {
                rutaInicioTmp[cantidadRutasTmp] = inicio;
                rutaLongitudTmp[cantidadRutasTmp] = longitud;
                rutaTipoTmp[cantidadRutasTmp] = tipo;
                cantidadRutasTmp++;
                usadasPorTipo[tipo]++;
                costoPuroTmp += costoArco[indiceArco(inicio, longitud, tipo)];
            }
            j = inicio;
        }
        invertir(rutaInicioTmp, cantidadRutasTmp);
        invertir(rutaLongitudTmp, cantidadRutasTmp);
        invertir(rutaTipoTmp, cantidadRutasTmp);
        invertir(bancoTmp, cantidadBancoTmp);
    }

    private static void invertir(int[] arreglo, int longitud) {
        for (int i = 0, j = longitud - 1; i < j; i++, j--) {
            int t = arreglo[i];
            arreglo[i] = arreglo[j];
            arreglo[j] = t;
        }
    }

    private void guardarParticion() {
        System.arraycopy(rutaInicioTmp, 0, mejorInicio, 0, cantidadRutasTmp);
        System.arraycopy(rutaLongitudTmp, 0, mejorLongitud, 0, cantidadRutasTmp);
        System.arraycopy(rutaTipoTmp, 0, mejorTipo, 0, cantidadRutasTmp);
        System.arraycopy(bancoTmp, 0, mejorBanco, 0, cantidadBancoTmp);
        mejorCantidadRutas = cantidadRutasTmp;
        mejorCantidadBanco = cantidadBancoTmp;
    }

    private void restaurarParticion() {
        System.arraycopy(mejorInicio, 0, rutaInicioTmp, 0, mejorCantidadRutas);
        System.arraycopy(mejorLongitud, 0, rutaLongitudTmp, 0, mejorCantidadRutas);
        System.arraycopy(mejorTipo, 0, rutaTipoTmp, 0, mejorCantidadRutas);
        System.arraycopy(mejorBanco, 0, bancoTmp, 0, mejorCantidadBanco);
        cantidadRutasTmp = mejorCantidadRutas;
        cantidadBancoTmp = mejorCantidadBanco;
    }

    // -------------------------------------------- asignacion a unidades reales

    /**
     * Asigna cada ruta de la particion a una unidad concreta y la reevalua con esa unidad,
     * que es el segundo tiempo de la resolucion de la dependencia circular. Las rutas se
     * atienden por urgencia creciente del plazo mas apretado que contienen, de modo que las
     * unidades escasas se reservan para las rutas que menos margen tienen.
     */
    private void asignarUnidades(Individuo individuo, double pesoDesfase) {
        final int[] permutacion = individuo.permutacion();
        final int[] visitas = individuo.visitas();
        final int[] rutaInicio = individuo.rutaInicio();
        final int[] rutaLongitud = individuo.rutaLongitud();
        final int[] rutaUnidad = individuo.rutaUnidad();
        final int[] banco = individuo.banco();
        final int[] sucesor = individuo.sucesor();
        final int maximoRutas = rutaInicio.length;

        Arrays.fill(libre, true);
        Arrays.fill(sucesor, 0, tareas.cantidad(), -1);

        for (int r = 0; r < cantidadRutasTmp; r++) {
            orden[r] = r;
            long menor = Long.MAX_VALUE;
            for (int k = 0; k < rutaLongitudTmp[r]; k++) {
                menor = Math.min(menor, tareas.limite(permutacion[rutaInicioTmp[r] + k]));
            }
            urgencia[r] = menor;
        }
        ordenarPorUrgencia(cantidadRutasTmp);

        int cantidadBanco = 0;
        for (int i = 0; i < cantidadBancoTmp; i++) {
            banco[cantidadBanco++] = permutacion[bancoTmp[i]];
        }

        int rutas = 0;
        int posicion = 0;
        double costoTotal = 0.0;
        long desfaseTotal = 0;

        for (int k = 0; k < cantidadRutasTmp; k++) {
            final int r = orden[k];
            final int inicio = rutaInicioTmp[r];
            final int longitud = rutaLongitudTmp[r];
            for (int i = 0; i < longitud; i++) {
                int tarea = permutacion[inicio + i];
                pedidosBuffer[i] = tareas.pedido(tarea);
                cantidadesBuffer[i] = tareas.cantidadUnidades(tarea);
            }

            int unidadElegida = -1;
            double mejorValor = Double.POSITIVE_INFINITY;
            double costoElegido = 0.0;
            int desfaseElegido = 0;

            final int puntoPrimero = tareas.punto(permutacion[inicio]);
            // Primer intento con unidades del tipo que valoro el arco. Si su cuota se agoto o
            // ninguna de sus unidades sirve, el segundo intento abre la busqueda a toda la
            // flota libre antes de mandar las tareas al banco.
            for (int intento = 0; intento < 2 && unidadElegida < 0 && rutas < maximoRutas; intento++) {
                final int cantidadCandidatos =
                        seleccionarCandidatos(rutaTipoTmp[r], puntoPrimero, intento == 1);
                for (int c = 0; c < cantidadCandidatos; c++) {
                    final int unidad = candidatos[c];
                    if (!programador.evaluar(unidad, pedidosBuffer, cantidadesBuffer, longitud)) {
                        continue;
                    }
                    double valor = programador.ultimoCosto() + pesoDesfase * programador.ultimoDesfase();
                    if (valor < mejorValor) {
                        mejorValor = valor;
                        unidadElegida = unidad;
                        costoElegido = programador.ultimoCosto();
                        desfaseElegido = programador.ultimoDesfase();
                    }
                }
            }

            if (unidadElegida < 0) {
                for (int i = 0; i < longitud; i++) {
                    banco[cantidadBanco++] = permutacion[inicio + i];
                }
                continue;
            }

            libre[unidadElegida] = false;
            rutaInicio[rutas] = posicion;
            rutaLongitud[rutas] = longitud;
            rutaUnidad[rutas] = unidadElegida;
            individuo.rutaCosto()[rutas] = costoElegido;
            individuo.rutaDesfase()[rutas] = desfaseElegido;
            for (int i = 0; i < longitud; i++) {
                int tarea = permutacion[inicio + i];
                visitas[posicion + i] = tarea;
                sucesor[tarea] = i + 1 < longitud ? permutacion[inicio + i + 1] : -1;
            }
            posicion += longitud;
            rutas++;
            costoTotal += costoElegido;
            desfaseTotal += desfaseElegido;
        }

        individuo.cantidadRutas(rutas);
        individuo.cantidadBanco(cantidadBanco);
        individuo.costo(costoTotal);
        individuo.desfase((int) Math.min(desfaseTotal, Integer.MAX_VALUE / 4));
        individuo.pedidosNoAtendidos(contarPedidos(banco, cantidadBanco));
    }

    /** Ordenacion por insercion de las rutas por urgencia creciente; son pocas decenas. */
    private void ordenarPorUrgencia(int cantidad) {
        for (int i = 1; i < cantidad; i++) {
            int actual = orden[i];
            long clave = urgencia[actual];
            int j = i - 1;
            while (j >= 0 && urgencia[orden[j]] > clave) {
                orden[j + 1] = orden[j];
                j--;
            }
            orden[j + 1] = actual;
        }
    }

    /**
     * Unidades libres candidatas a atender una ruta, las mas proximas al destino de su
     * primera tarea. El desempate por mayor margen de turno y por menor indice hace la
     * seleccion reproducible.
     *
     * @param tipoRuta      tipo con el que el Split valoro la ruta
     * @param puntoPrimero  punto de la primera tarea de la ruta
     * @param cualquierTipo si {@code true} se consideran unidades de cualquier tipo
     * @return numero de candidatos dejados en {@code candidatos}
     */
    private int seleccionarCandidatos(int tipoRuta, int puntoPrimero, boolean cualquierTipo) {
        final MatrizDistancias matriz = instancia.matriz();
        final int maximo = candidatos.length;
        int cantidad = 0;
        for (int u = 0; u < cantidadUnidades; u++) {
            if (!libre[u]) {
                continue;
            }
            if (!cualquierTipo && instancia.unidadTipo(u).ordinal() != tipoRuta) {
                continue;
            }
            int distancia = matriz.km(instancia.puntoUnidad(u), puntoPrimero);
            if (distancia >= MatrizDistancias.INALCANZABLE) {
                continue;
            }
            int posicion = cantidad;
            while (posicion > 0 && distanciaCandidato[posicion - 1] > distancia) {
                if (posicion < maximo) {
                    candidatos[posicion] = candidatos[posicion - 1];
                    distanciaCandidato[posicion] = distanciaCandidato[posicion - 1];
                }
                posicion--;
            }
            if (posicion < maximo) {
                candidatos[posicion] = u;
                distanciaCandidato[posicion] = distancia;
                if (cantidad < maximo) {
                    cantidad++;
                }
            }
        }
        return cantidad;
    }

    /** Numero de pedidos distintos representados por las tareas del banco. Es H. */
    private int contarPedidos(int[] banco, int cantidad) {
        sello++;
        int total = 0;
        for (int i = 0; i < cantidad; i++) {
            int pedido = tareas.pedido(banco[i]);
            if (marcaPedido[pedido] != sello) {
                marcaPedido[pedido] = sello;
                total++;
            }
        }
        return total;
    }

    // ------------------------------------------------------------ preparacion

    /**
     * Unidad representante de cada par tipo-almacen: la unidad libre de ese tipo mas proxima
     * al almacen, con desempate por mayor margen de turno y por menor indice. Es la que
     * valora los arcos del grafo auxiliar, bajo el supuesto de que una ruta arranca en el
     * almacen mas cercano a su primera tarea.
     */
    private int[] calcularRepresentantes() {
        final MatrizDistancias matriz = instancia.matriz();
        int[] tabla = new int[TIPOS * cantidadAlmacenes];
        Arrays.fill(tabla, -1);
        for (int t = 0; t < TIPOS; t++) {
            for (int a = 0; a < cantidadAlmacenes; a++) {
                int elegido = -1;
                int mejorDistancia = Integer.MAX_VALUE;
                long mejorMargen = Long.MIN_VALUE;
                for (int u = 0; u < cantidadUnidades; u++) {
                    if (instancia.unidadTipo(u).ordinal() != t) {
                        continue;
                    }
                    int distancia = matriz.km(instancia.puntoUnidad(u), instancia.puntoAlmacen(a));
                    if (distancia >= MatrizDistancias.INALCANZABLE) {
                        continue;
                    }
                    long margen = instancia.unidadMinutoFinTurno(u) - instancia.unidadMinutoDisponible(u);
                    if (distancia < mejorDistancia || (distancia == mejorDistancia && margen > mejorMargen)) {
                        mejorDistancia = distancia;
                        mejorMargen = margen;
                        elegido = u;
                    }
                }
                tabla[t * cantidadAlmacenes + a] = elegido;
            }
        }
        return tabla;
    }
}
