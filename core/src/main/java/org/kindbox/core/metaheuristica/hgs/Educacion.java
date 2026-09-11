package org.kindbox.core.metaheuristica.hgs;

import java.util.Arrays;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Educacion de un descendiente: la busqueda local que convierte a la busqueda genetica
 * hibrida en hibrida, conforme al apartado 6.3.3 del ISA.
 *
 * <p>Se aplica a todo descendiente recien decodificado por el {@link Split} y recorre los
 * vecindarios clasicos de Vidal y otros (2012): reubicacion de uno o dos vertices, con y sin
 * inversion del par; intercambio de uno o dos vertices; dos-opt dentro de una ruta;
 * dos-opt asterisco entre dos rutas; y el <b>SWAP asterisco</b> de Vidal (2022), que
 * intercambia dos vertices entre rutas distintas insertando cada uno en su mejor posicion de
 * la ruta de destino y no en la posicion que ocupaba el otro. A ellos se anaden dos
 * movimientos que impone el nivel 1 del objetivo, ausentes del VRP clasico: la
 * <b>reinsercion</b> de una tarea del banco y la <b>retirada</b> al banco de una tarea que la
 * ruta no alcanza a servir en plazo. Y un tercero que impone la flota heterogenea: la
 * <b>reasignacion</b> de una ruta a otra unidad libre, que es lo que permite que el reparto
 * por tipo se afine dentro de la busqueda local y no solo entre generaciones.</p>
 *
 * <h2>Estabilidad del plan</h2>
 * <p>El valor penalizado de una ruta no es solo su costo mas su desfase: lleva ademas el
 * termino blando del apartado 11.4 del ISA, un peso por cada pedido de la ruta que cambia de
 * unidad respecto del plan vigente. El termino es aditivo por ruta, de modo que encaja sin mas
 * en {@link #valor(int, double)} y por tanto en todos los movimientos, que ya comparan sumas de
 * valores de ruta. La consecuencia buscada es que mover un pedido a otra unidad deje de ser
 * gratis cuando su costo empata, que es justo el caso en que la busqueda estocastica lo movia
 * sin ganar nada. {@link EstabilidadPlan} resuelve la consulta en un acceso a un arreglo
 * primitivo, sin tablas asociativas ni comparacion de cadenas dentro del bucle.</p>
 *
 * <h2>Granularidad</h2>
 * <p>Los movimientos se restringen a pares de vertices geograficamente proximos, con la
 * lista de vecinos que precalcula {@link TareasEntrega} a partir de
 * {@code MatrizDistancias.vecinosCercanos}. Con un parametro de granularidad de veinte
 * vecinos la exploracion pasa de cuadratica a lineal en el numero de tareas, que es lo que
 * la vuelve compatible con un presupuesto de segundos.</p>
 *
 * <h2>Por que se reevalua la ruta completa y no se concatena en tiempo constante</h2>
 * <p>{@code DatosSecuencia} evalua la concatenacion de dos subsecuencias en tiempo
 * constante, que es la tecnica habitual para que un movimiento no cueste lineal en la
 * longitud de la ruta. Aqui se opta por reevaluar la ruta entera con
 * {@link ProgramadorRuta#evaluar}, y la razon es de correccion antes que de velocidad: el
 * resumen de concatenacion no puede expresar ni la insercion de una parada de abastecimiento
 * en el almacen que minimiza el desvio, que depende de la carga a bordo en ese punto y del
 * inventario disponible, ni la reubicacion de la pausa de alimentacion, que se decide por
 * enumeracion sobre la ruta ya formada. Evaluar por concatenacion daria un valor distinto
 * del que devuelve el decodificador y romperia la equivalencia que exige el apartado 12.4.
 * El costo de esa decision es acotado: una entrega consume una hora de acondicionamiento, de
 * modo que una ruta no pasa de siete u ocho paradas dentro de un turno de ocho horas y
 * reevaluarla entera es, en la practica, tiempo constante.</p>
 *
 * <p>La clase mantiene todos sus arreglos de trabajo como campos de instancia y no asigna
 * memoria por movimiento evaluado. No es segura para uso concurrente.</p>
 */
public final class Educacion {

    /** Valor con el que se marca una secuencia que no admite programacion. */
    private static final double INFINITO = Double.MAX_VALUE / 4.0;
    /** Margen de mejora exigido para aceptar un movimiento, en soles. */
    private static final double EPSILON = 1e-7;

    private final InstanciaPlanificacion instancia;
    private final TareasEntrega tareas;
    private final ProgramadorRuta programador;
    private final Aleatorio aleatorio;
    private final ParametrosHgs parametros;
    private final PresupuestoComputo presupuesto;
    private final EstabilidadPlan estabilidad;

    private final int cantidadTareas;
    private final int cantidadUnidades;

    // Solucion de trabajo.
    private final int[][] ruta;
    private final int[] largo;
    private final int[] unidad;
    private final double[] costoRuta;
    private final int[] desfaseRuta;
    /** Pedidos de cada ruta que cambian de unidad respecto del plan vigente. */
    private final int[] desviacionRuta;
    private int cantidadRutas;

    private final int[] rutaDeTarea;
    private final int[] posicionDeTarea;
    private final int[] banco;
    private int cantidadBanco;

    // Arreglos de trabajo.
    private final int[] bufferA;
    private final int[] bufferB;
    private final int[] bufferC;
    private final int[] bufferD;
    private final int[] segmento = new int[2];
    private final int[] pedidosBuffer;
    private final int[] cantidadesBuffer;
    private final int[] ordenExploracion;
    private final int[] marcaPedido;
    private final int[] marcaTipo = new int[3];
    private final boolean[] libre;
    private final int[] rutaDeUnidad;
    private final int[] candidatos;
    private final int[] distanciaCandidato;
    /** Candidatas que se eligen por cercania; el resto de la lista la ocupan las vigentes. */
    private final int candidatosPorDistancia;
    private int sello;

    private double ultimoCosto;
    private int ultimoDesfase;

    public Educacion(InstanciaPlanificacion instancia, TareasEntrega tareas, ProgramadorRuta programador,
                     Aleatorio aleatorio, ParametrosHgs parametros, PresupuestoComputo presupuesto,
                     EstabilidadPlan estabilidad) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.programador = programador;
        this.aleatorio = aleatorio;
        this.parametros = parametros;
        this.presupuesto = presupuesto;
        this.estabilidad = estabilidad;
        this.cantidadTareas = tareas.cantidad();
        this.cantidadUnidades = instancia.cantidadUnidades();

        final int rutas = Math.max(1, cantidadUnidades);
        final int capacidad = cantidadTareas + 2;
        this.ruta = new int[rutas][capacidad];
        this.largo = new int[rutas];
        this.unidad = new int[rutas];
        this.costoRuta = new double[rutas];
        this.desfaseRuta = new int[rutas];
        this.desviacionRuta = new int[rutas];

        this.rutaDeTarea = new int[Math.max(1, cantidadTareas)];
        this.posicionDeTarea = new int[Math.max(1, cantidadTareas)];
        this.banco = new int[Math.max(1, cantidadTareas)];

        this.bufferA = new int[capacidad];
        this.bufferB = new int[capacidad];
        this.bufferC = new int[capacidad];
        this.bufferD = new int[capacidad];
        this.pedidosBuffer = new int[capacidad];
        this.cantidadesBuffer = new int[capacidad];
        this.ordenExploracion = new int[Math.max(1, cantidadTareas)];
        this.marcaPedido = new int[Math.max(1, instancia.cantidadPedidos())];
        this.libre = new boolean[rutas];
        this.rutaDeUnidad = new int[rutas];
        this.candidatosPorDistancia = Math.max(1, parametros.candidatosUnidadPorRuta());
        // A las candidatas por cercania se suman las unidades vigentes de las tareas de la ruta.
        this.candidatos = new int[candidatosPorDistancia + capacidad];
        this.distanciaCandidato = new int[candidatos.length];
    }

    /**
     * Mejora el individuo con la busqueda local y deja en el la solucion educada, con el
     * cromosoma reescrito a partir de las rutas resultantes. Reescribir el cromosoma es lo
     * que cierra el ciclo de la busqueda genetica hibrida: lo que aprende la busqueda local
     * pasa al material genetico y por tanto es heredable.
     *
     * <p>El presupuesto se consulta entre pasadas y dentro de los recorridos, para cortar la
     * educacion en cuanto se agota el reloj. Con un presupuesto por iteraciones el contador
     * solo avanza al cerrar cada generacion, de modo que {@code agotado()} no cambia de valor
     * durante una educacion: salvo cancelacion, la educacion corre hasta su optimo local o
     * hasta {@code pasadasEducacionMaximas}, y su resultado es funcion determinista del
     * individuo y del generador.</p>
     *
     * @param individuo   individuo ya decodificado por el Split
     * @param pesoDesfase penalizacion vigente de cada minuto de violacion temporal
     */
    public void educar(Individuo individuo, double pesoDesfase) {
        if (cantidadTareas == 0 || cantidadUnidades == 0) {
            return;
        }
        cargar(individuo, pesoDesfase);
        reinsertarBanco(pesoDesfase);
        for (int pasada = 0; pasada < parametros.pasadasEducacionMaximas(); pasada++) {
            if (presupuesto.agotado()) {
                break;
            }
            boolean mejoro = recorrerVecindarios(pesoDesfase);
            mejoro |= reasignarUnidades(pesoDesfase);
            if (!mejoro) {
                break;
            }
        }
        if (!presupuesto.agotado()) {
            reinsertarBanco(pesoDesfase);
        }
        volcar(individuo);
    }

    // --------------------------------------------------------- carga y volcado

    /**
     * Copia la decodificacion del individuo en los arreglos de trabajo y anade una ruta
     * vacia por cada unidad ociosa. Esas rutas vacias son las que permiten que la reinsercion
     * del banco abra una ruta nueva: sin ellas, una tarea que ninguna ruta existente puede
     * absorber quedaria sin atender aunque sobrasen unidades en el turno.
     */
    private void cargar(Individuo individuo, double pesoDesfase) {
        cantidadRutas = individuo.cantidadRutas();
        final int[] visitas = individuo.visitas();
        final int[] inicio = individuo.rutaInicio();
        final int[] longitud = individuo.rutaLongitud();
        final int[] unidades = individuo.rutaUnidad();

        Arrays.fill(rutaDeTarea, 0, cantidadTareas, -1);
        Arrays.fill(libre, true);
        for (int r = 0; r < cantidadRutas; r++) {
            largo[r] = longitud[r];
            unidad[r] = unidades[r];
            libre[unidad[r]] = false;
            System.arraycopy(visitas, inicio[r], ruta[r], 0, longitud[r]);
            for (int i = 0; i < longitud[r]; i++) {
                rutaDeTarea[ruta[r][i]] = r;
                posicionDeTarea[ruta[r][i]] = i;
            }
            evaluarSecuencia(unidad[r], ruta[r], largo[r], pesoDesfase);
            costoRuta[r] = ultimoCosto;
            desfaseRuta[r] = ultimoDesfase;
            desviacionRuta[r] = estabilidad.desviacion(unidad[r], ruta[r], largo[r]);
        }
        for (int u = 0; u < cantidadUnidades && cantidadRutas < largo.length; u++) {
            if (!libre[u]) {
                continue;
            }
            largo[cantidadRutas] = 0;
            unidad[cantidadRutas] = u;
            costoRuta[cantidadRutas] = 0.0;
            desfaseRuta[cantidadRutas] = 0;
            desviacionRuta[cantidadRutas] = 0;
            cantidadRutas++;
        }
        cantidadBanco = individuo.cantidadBanco();
        System.arraycopy(individuo.banco(), 0, banco, 0, cantidadBanco);
    }

    private void volcar(Individuo individuo) {
        final int[] visitas = individuo.visitas();
        final int[] inicio = individuo.rutaInicio();
        final int[] longitud = individuo.rutaLongitud();
        final int[] unidades = individuo.rutaUnidad();
        final int[] sucesor = individuo.sucesor();
        final int[] permutacion = individuo.permutacion();
        final byte[] tipo = individuo.tipo();

        Arrays.fill(sucesor, 0, cantidadTareas, -1);
        int rutas = 0;
        int posicion = 0;
        double costo = 0.0;
        long desfase = 0;
        int desviacion = 0;
        for (int r = 0; r < cantidadRutas; r++) {
            if (largo[r] == 0) {
                continue;
            }
            inicio[rutas] = posicion;
            longitud[rutas] = largo[r];
            unidades[rutas] = unidad[r];
            individuo.rutaCosto()[rutas] = costoRuta[r];
            individuo.rutaDesfase()[rutas] = desfaseRuta[r];
            byte tipoUnidad = (byte) instancia.unidadTipo(unidad[r]).ordinal();
            for (int i = 0; i < largo[r]; i++) {
                int tarea = ruta[r][i];
                visitas[posicion] = tarea;
                permutacion[posicion] = tarea;
                tipo[tarea] = tipoUnidad;
                sucesor[tarea] = i + 1 < largo[r] ? ruta[r][i + 1] : -1;
                posicion++;
            }
            costo += costoRuta[r];
            desfase += desfaseRuta[r];
            desviacion += desviacionRuta[r];
            rutas++;
        }
        for (int i = 0; i < cantidadBanco; i++) {
            permutacion[posicion + i] = banco[i];
        }
        System.arraycopy(banco, 0, individuo.banco(), 0, cantidadBanco);
        individuo.cantidadRutas(rutas);
        individuo.cantidadBanco(cantidadBanco);
        individuo.costo(costo);
        individuo.desfase((int) Math.min(desfase, Integer.MAX_VALUE / 4));
        individuo.desviacionPlan(desviacion);
        individuo.pedidosNoAtendidos(contarPedidos());
    }

    /** Numero de pedidos distintos con al menos una tarea en el banco. Es H. */
    private int contarPedidos() {
        sello++;
        int total = 0;
        for (int i = 0; i < cantidadBanco; i++) {
            int pedido = tareas.pedido(banco[i]);
            if (marcaPedido[pedido] != sello) {
                marcaPedido[pedido] = sello;
                total++;
            }
        }
        return total;
    }

    // ------------------------------------------------------------- vecindarios

    /**
     * Una pasada completa sobre las tareas, en orden aleatorio, probando los movimientos
     * contra los vecinos geograficos de cada una. Se acepta la primera mejora encontrada,
     * que es la estrategia de Vidal y otros (2012): explorar el vecindario completo para
     * quedarse con la mejor mejora cuesta mas de lo que aporta.
     */
    private boolean recorrerVecindarios(double pesoDesfase) {
        boolean mejoro = false;
        for (int i = 0; i < cantidadTareas; i++) {
            ordenExploracion[i] = i;
        }
        aleatorio.barajar(ordenExploracion, cantidadTareas);

        for (int indice = 0; indice < cantidadTareas; indice++) {
            if ((indice & 15) == 0 && presupuesto.agotado()) {
                return mejoro;
            }
            final int u = ordenExploracion[indice];
            if (rutaDeTarea[u] < 0) {
                continue;
            }
            if (retirar(u, pesoDesfase)) {
                mejoro = true;
                continue;
            }
            final int[] vecinos = tareas.vecinos(u);
            for (int k = 0; k < vecinos.length; k++) {
                final int v = vecinos[k];
                if (v == u || rutaDeTarea[v] < 0) {
                    continue;
                }
                if (intentarMovimientos(u, v, pesoDesfase)) {
                    mejoro = true;
                    break;
                }
            }
        }
        return mejoro;
    }

    /** Prueba en orden los movimientos sobre el par de tareas y aplica la primera mejora. */
    private boolean intentarMovimientos(int u, int v, double peso) {
        final int r1 = rutaDeTarea[u];
        final int r2 = rutaDeTarea[v];
        final int p2 = posicionDeTarea[v];

        // Reubicacion de uno y de dos vertices, con y sin inversion del par.
        if (reubicar(u, 1, false, r2, p2 + 1, peso)) {
            return true;
        }
        if (p2 == 0 && reubicar(u, 1, false, r2, 0, peso)) {
            return true;
        }
        if (reubicar(u, 2, false, r2, p2 + 1, peso)) {
            return true;
        }
        if (reubicar(u, 2, true, r2, p2 + 1, peso)) {
            return true;
        }
        // Intercambio de uno y de dos vertices.
        if (intercambiar(u, v, 1, 1, peso)) {
            return true;
        }
        if (intercambiar(u, v, 2, 1, peso)) {
            return true;
        }
        if (intercambiar(u, v, 2, 2, peso)) {
            return true;
        }
        if (r1 == r2) {
            return dosOptInterno(u, v, peso);
        }
        if (dosOptAsterisco(u, v, peso)) {
            return true;
        }
        return swapAsterisco(u, v, peso);
    }

    /**
     * Reubicacion: mueve el segmento que arranca en {@code u} a la posicion indicada de la
     * ruta de destino, opcionalmente invertido.
     *
     * @param u          primera tarea del segmento
     * @param tamano     numero de tareas del segmento, uno o dos
     * @param invertido  si se inserta en orden inverso
     * @param destino    ruta de destino
     * @param posicion   posicion de insercion, referida a la ruta de destino original
     */
    private boolean reubicar(int u, int tamano, boolean invertido, int destino, int posicion, double peso) {
        final int origen = rutaDeTarea[u];
        final int desde = posicionDeTarea[u];
        if (desde + tamano > largo[origen]) {
            return false;
        }
        if (origen == destino && posicion >= desde && posicion <= desde + tamano) {
            return false;
        }
        segmento[0] = ruta[origen][desde];
        if (tamano == 2) {
            segmento[1] = ruta[origen][desde + 1];
        }
        if (origen == destino) {
            // El segmento se quita antes de reinsertarlo, de modo que la posicion de destino
            // se corre si estaba a la derecha del hueco.
            int largoBase = copiarQuitando(origen, desde, tamano, bufferA);
            int posicionAjustada = posicion > desde ? posicion - tamano : posicion;
            int largoNuevo = copiarInsertando(bufferA, largoBase, posicionAjustada, tamano, invertido, bufferB);
            if (descartablePorDistancia(origen, bufferB, largoNuevo, peso)) {
                return false;
            }
            double nuevo = evaluarSecuencia(unidad[origen], bufferB, largoNuevo, peso);
            if (nuevo >= INFINITO || nuevo >= valor(origen, peso) - EPSILON) {
                return false;
            }
            aplicarUna(origen, bufferB, largoNuevo, ultimoCosto, ultimoDesfase);
            return true;
        }
        int largoOrigen = copiarQuitando(origen, desde, tamano, bufferA);
        int largoDestino = copiarInsertando(ruta[destino], largo[destino], posicion, tamano, invertido, bufferB);
        if (descartablePorDistancia(origen, bufferA, largoOrigen, destino, bufferB, largoDestino, peso)) {
            return false;
        }
        double valorOrigen = evaluarSecuencia(unidad[origen], bufferA, largoOrigen, peso);
        if (valorOrigen >= INFINITO) {
            return false;
        }
        double costoOrigen = ultimoCosto;
        int desfaseOrigen = ultimoDesfase;
        double valorDestino = evaluarSecuencia(unidad[destino], bufferB, largoDestino, peso);
        if (valorDestino >= INFINITO) {
            return false;
        }
        if (valorOrigen + valorDestino >= valor(origen, peso) + valor(destino, peso) - EPSILON) {
            return false;
        }
        aplicarDos(origen, bufferA, largoOrigen, costoOrigen, desfaseOrigen,
                destino, bufferB, largoDestino, ultimoCosto, ultimoDesfase);
        return true;
    }

    /** Intercambio de dos segmentos de uno o dos vertices entre dos posiciones. */
    private boolean intercambiar(int u, int v, int tamanoU, int tamanoV, double peso) {
        final int r1 = rutaDeTarea[u];
        final int r2 = rutaDeTarea[v];
        final int p1 = posicionDeTarea[u];
        final int p2 = posicionDeTarea[v];
        if (p1 + tamanoU > largo[r1] || p2 + tamanoV > largo[r2]) {
            return false;
        }
        if (r1 == r2) {
            if (p1 + tamanoU > p2 && p2 + tamanoV > p1) {
                return false;
            }
            int largoNuevo = copiarIntercambiando(r1, p1, tamanoU, p2, tamanoV, bufferA);
            if (descartablePorDistancia(r1, bufferA, largoNuevo, peso)) {
                return false;
            }
            double nuevo = evaluarSecuencia(unidad[r1], bufferA, largoNuevo, peso);
            if (nuevo >= INFINITO || nuevo >= valor(r1, peso) - EPSILON) {
                return false;
            }
            aplicarUna(r1, bufferA, largoNuevo, ultimoCosto, ultimoDesfase);
            return true;
        }
        int largo1 = copiarReemplazando(r1, p1, tamanoU, ruta[r2], p2, tamanoV, bufferA);
        int largo2 = copiarReemplazando(r2, p2, tamanoV, ruta[r1], p1, tamanoU, bufferB);
        if (descartablePorDistancia(r1, bufferA, largo1, r2, bufferB, largo2, peso)) {
            return false;
        }
        double valor1 = evaluarSecuencia(unidad[r1], bufferA, largo1, peso);
        if (valor1 >= INFINITO) {
            return false;
        }
        double costo1 = ultimoCosto;
        int desfase1 = ultimoDesfase;
        double valor2 = evaluarSecuencia(unidad[r2], bufferB, largo2, peso);
        if (valor2 >= INFINITO) {
            return false;
        }
        if (valor1 + valor2 >= valor(r1, peso) + valor(r2, peso) - EPSILON) {
            return false;
        }
        aplicarDos(r1, bufferA, largo1, costo1, desfase1, r2, bufferB, largo2, ultimoCosto, ultimoDesfase);
        return true;
    }

    /**
     * Dos-opt dentro de una ruta: invierte el tramo comprendido entre las dos tareas. Como la
     * ruta es un camino abierto que arranca donde esta la unidad y no regresa al almacen,
     * invertir el tramo final tambien es un movimiento legitimo.
     */
    private boolean dosOptInterno(int u, int v, double peso) {
        final int r = rutaDeTarea[u];
        int p1 = posicionDeTarea[u];
        int p2 = posicionDeTarea[v];
        if (p1 > p2) {
            int intercambio = p1;
            p1 = p2;
            p2 = intercambio;
        }
        if (p2 - p1 < 2) {
            return false;
        }
        int n = 0;
        for (int i = 0; i <= p1; i++) {
            bufferA[n++] = ruta[r][i];
        }
        for (int i = p2; i > p1; i--) {
            bufferA[n++] = ruta[r][i];
        }
        for (int i = p2 + 1; i < largo[r]; i++) {
            bufferA[n++] = ruta[r][i];
        }
        if (descartablePorDistancia(r, bufferA, n, peso)) {
            return false;
        }
        double nuevo = evaluarSecuencia(unidad[r], bufferA, n, peso);
        if (nuevo >= INFINITO || nuevo >= valor(r, peso) - EPSILON) {
            return false;
        }
        aplicarUna(r, bufferA, n, ultimoCosto, ultimoDesfase);
        return true;
    }

    /** Dos-opt asterisco: las dos rutas intercambian sus colas a partir de las tareas dadas. */
    private boolean dosOptAsterisco(int u, int v, double peso) {
        final int r1 = rutaDeTarea[u];
        final int r2 = rutaDeTarea[v];
        final int p1 = posicionDeTarea[u];
        final int p2 = posicionDeTarea[v];
        // Con las dos colas vacias el movimiento no cambia nada.
        if (p1 + 1 == largo[r1] && p2 + 1 == largo[r2]) {
            return false;
        }
        int n1 = 0;
        for (int i = 0; i <= p1; i++) {
            bufferA[n1++] = ruta[r1][i];
        }
        for (int i = p2 + 1; i < largo[r2]; i++) {
            bufferA[n1++] = ruta[r2][i];
        }
        int n2 = 0;
        for (int i = 0; i <= p2; i++) {
            bufferB[n2++] = ruta[r2][i];
        }
        for (int i = p1 + 1; i < largo[r1]; i++) {
            bufferB[n2++] = ruta[r1][i];
        }
        if (descartablePorDistancia(r1, bufferA, n1, r2, bufferB, n2, peso)) {
            return false;
        }
        double valor1 = evaluarSecuencia(unidad[r1], bufferA, n1, peso);
        if (valor1 >= INFINITO) {
            return false;
        }
        double costo1 = ultimoCosto;
        int desfase1 = ultimoDesfase;
        double valor2 = evaluarSecuencia(unidad[r2], bufferB, n2, peso);
        if (valor2 >= INFINITO) {
            return false;
        }
        if (valor1 + valor2 >= valor(r1, peso) + valor(r2, peso) - EPSILON) {
            return false;
        }
        aplicarDos(r1, bufferA, n1, costo1, desfase1, r2, bufferB, n2, ultimoCosto, ultimoDesfase);
        return true;
    }

    /**
     * SWAP asterisco de Vidal (2022): las dos tareas se intercambian de ruta, pero cada una
     * entra en <b>su mejor posicion</b> de la ruta de destino y no en la que dejo la otra. La
     * posicion de insercion se elige por menor incremento de distancia, que es una estimacion
     * barata; el par de rutas resultante se valora despues una sola vez con el decodificador.
     */
    private boolean swapAsterisco(int u, int v, double peso) {
        final int r1 = rutaDeTarea[u];
        final int r2 = rutaDeTarea[v];
        int largoSinU = copiarQuitando(r1, posicionDeTarea[u], 1, bufferA);
        int largoSinV = copiarQuitando(r2, posicionDeTarea[v], 1, bufferC);

        int posicionV = mejorPosicionPorDistancia(bufferA, largoSinU, unidad[r1], v);
        segmento[0] = v;
        int largo1 = copiarInsertando(bufferA, largoSinU, posicionV, 1, false, bufferB);
        int posicionU = mejorPosicionPorDistancia(bufferC, largoSinV, unidad[r2], u);
        segmento[0] = u;
        int largo2 = copiarInsertando(bufferC, largoSinV, posicionU, 1, false, bufferD);
        if (descartablePorDistancia(r1, bufferB, largo1, r2, bufferD, largo2, peso)) {
            return false;
        }

        double valor1 = evaluarSecuencia(unidad[r1], bufferB, largo1, peso);
        if (valor1 >= INFINITO) {
            return false;
        }
        double costo1 = ultimoCosto;
        int desfase1 = ultimoDesfase;
        double valor2 = evaluarSecuencia(unidad[r2], bufferD, largo2, peso);
        if (valor2 >= INFINITO) {
            return false;
        }
        if (valor1 + valor2 >= valor(r1, peso) + valor(r2, peso) - EPSILON) {
            return false;
        }
        aplicarDos(r1, bufferB, largo1, costo1, desfase1, r2, bufferD, largo2, ultimoCosto, ultimoDesfase);
        return true;
    }

    // ------------------------------------------------------- banco y unidades

    /**
     * Retirada al banco. Solo se plantea sobre rutas que ya incumplen algun plazo: en una
     * ruta factible el ahorro de costo nunca llega al peso de un pedido no atendido, de modo
     * que probarlo seria gastar una evaluacion en balde.
     */
    private boolean retirar(int u, double peso) {
        final int r = rutaDeTarea[u];
        if (desfaseRuta[r] == 0) {
            return false;
        }
        int largoNuevo = copiarQuitando(r, posicionDeTarea[u], 1, bufferA);
        double nuevo = evaluarSecuencia(unidad[r], bufferA, largoNuevo, peso);
        if (nuevo >= INFINITO) {
            return false;
        }
        if (nuevo + ParametrosHgs.PENALIZACION_TAREA_NO_ATENDIDA >= valor(r, peso) - EPSILON) {
            return false;
        }
        banco[cantidadBanco++] = u;
        rutaDeTarea[u] = -1;
        aplicarUna(r, bufferA, largoNuevo, ultimoCosto, ultimoDesfase);
        return true;
    }

    /**
     * Reinsercion de las tareas del banco. Cada tarea se prueba junto a sus vecinos
     * geograficos y en las rutas todavia vacias, y se acepta la mejor insercion cuyo costo
     * quede por debajo del peso de dejar el pedido sin atender. Es el movimiento que baja H,
     * el nivel 1 del objetivo.
     */
    private boolean reinsertarBanco(double peso) {
        boolean mejoro = false;
        int i = 0;
        while (i < cantidadBanco) {
            if ((i & 7) == 0 && presupuesto.agotado()) {
                return mejoro;
            }
            final int w = banco[i];
            int mejorRuta = -1;
            int mejorPosicion = -1;
            double mejorIncremento = ParametrosHgs.PENALIZACION_TAREA_NO_ATENDIDA;
            double mejorCosto = 0.0;
            int mejorDesfase = 0;

            final int[] vecinos = tareas.vecinos(w);
            for (int k = 0; k < vecinos.length; k++) {
                final int v = vecinos[k];
                final int r = rutaDeTarea[v];
                if (r < 0) {
                    continue;
                }
                for (int desplazamiento = 0; desplazamiento <= 1; desplazamiento++) {
                    segmento[0] = w;
                    int posicion = posicionDeTarea[v] + desplazamiento;
                    int largoNuevo = copiarInsertando(ruta[r], largo[r], posicion, 1, false, bufferA);
                    double nuevo = evaluarSecuencia(unidad[r], bufferA, largoNuevo, peso);
                    if (nuevo >= INFINITO) {
                        continue;
                    }
                    double incremento = nuevo - valor(r, peso);
                    if (incremento < mejorIncremento - EPSILON) {
                        mejorIncremento = incremento;
                        mejorRuta = r;
                        mejorPosicion = posicion;
                        mejorCosto = ultimoCosto;
                        mejorDesfase = ultimoDesfase;
                    }
                }
            }
            // Abrir una ruta nueva, una por tipo de unidad ociosa: probar todas las unidades
            // libres seria repetir la misma evaluacion para unidades intercambiables.
            sello++;
            for (int r = 0; r < cantidadRutas; r++) {
                if (largo[r] != 0) {
                    continue;
                }
                int tipoUnidad = instancia.unidadTipo(unidad[r]).ordinal();
                if (marcaTipo[tipoUnidad] == sello) {
                    continue;
                }
                marcaTipo[tipoUnidad] = sello;
                segmento[0] = w;
                int largoNuevo = copiarInsertando(ruta[r], 0, 0, 1, false, bufferA);
                double nuevo = evaluarSecuencia(unidad[r], bufferA, largoNuevo, peso);
                if (nuevo < INFINITO && nuevo < mejorIncremento - EPSILON) {
                    mejorIncremento = nuevo;
                    mejorRuta = r;
                    mejorPosicion = 0;
                    mejorCosto = ultimoCosto;
                    mejorDesfase = ultimoDesfase;
                }
            }

            if (mejorRuta < 0) {
                i++;
                continue;
            }
            segmento[0] = w;
            int largoNuevo = copiarInsertando(ruta[mejorRuta], largo[mejorRuta], mejorPosicion, 1, false, bufferA);
            aplicarUna(mejorRuta, bufferA, largoNuevo, mejorCosto, mejorDesfase);
            banco[i] = banco[--cantidadBanco];
            mejoro = true;
        }
        return mejoro;
    }

    /**
     * Reasignacion de una ruta a otra unidad libre. Es el movimiento propio de la flota
     * heterogenea: la particion del Split fija que tareas van juntas, pero el tipo mas
     * conveniente para atenderlas puede cambiar cuando la busqueda local reorganiza la ruta.
     *
     * <p>Es tambien el movimiento por el que la ruta puede <b>volver</b> a la unidad que sus
     * pedidos tenian en el plan vigente, y por eso las candidatas no son solo las libres mas
     * proximas sino tambien las vigentes de sus tareas. Con el termino de estabilidad en el
     * valor de la ruta, ese retorno gana siempre que el costo empate.</p>
     */
    private boolean reasignarUnidades(double peso) {
        if (cantidadRutas == 0) {
            return false;
        }
        boolean mejoro = false;
        // Una unidad esta libre cuando su ruta esta vacia. Al reasignar se intercambian las
        // unidades de las dos rutas implicadas, de modo que nunca hay dos rutas con la misma.
        Arrays.fill(libre, true);
        Arrays.fill(rutaDeUnidad, -1);
        for (int r = 0; r < cantidadRutas; r++) {
            libre[unidad[r]] = largo[r] == 0;
            rutaDeUnidad[unidad[r]] = r;
        }
        for (int r = 0; r < cantidadRutas; r++) {
            if (largo[r] == 0) {
                continue;
            }
            int cantidad = seleccionarLibres(tareas.punto(ruta[r][0]), ruta[r], largo[r]);
            int elegida = -1;
            double mejorValor = valor(r, peso);
            double mejorCosto = 0.0;
            int mejorDesfase = 0;
            for (int c = 0; c < cantidad; c++) {
                if (cotaDeSecuencia(candidatos[c], ruta[r], largo[r]) >= mejorValor - EPSILON) {
                    continue;
                }
                double nuevo = evaluarSecuencia(candidatos[c], ruta[r], largo[r], peso);
                if (nuevo < INFINITO && nuevo < mejorValor - EPSILON) {
                    mejorValor = nuevo;
                    elegida = candidatos[c];
                    mejorCosto = ultimoCosto;
                    mejorDesfase = ultimoDesfase;
                }
            }
            if (elegida >= 0) {
                final int anterior = unidad[r];
                final int rutaVacia = rutaDeUnidad[elegida];
                if (rutaVacia >= 0) {
                    unidad[rutaVacia] = anterior;
                    rutaDeUnidad[anterior] = rutaVacia;
                } else {
                    rutaDeUnidad[anterior] = -1;
                }
                libre[anterior] = true;
                libre[elegida] = false;
                unidad[r] = elegida;
                rutaDeUnidad[elegida] = r;
                costoRuta[r] = mejorCosto;
                desfaseRuta[r] = mejorDesfase;
                desviacionRuta[r] = estabilidad.desviacion(elegida, ruta[r], largo[r]);
                mejoro = true;
            }
        }
        return mejoro;
    }

    /**
     * Unidades libres candidatas para una ruta: las mas proximas al punto dado, sin distincion
     * de tipo, mas las unidades vigentes de sus tareas que sigan libres.
     */
    private int seleccionarLibres(int punto, int[] secuencia, int longitud) {
        final MatrizDistancias matriz = instancia.matriz();
        final int maximo = candidatosPorDistancia;
        int cantidad = 0;
        for (int u = 0; u < cantidadUnidades; u++) {
            if (!libre[u]) {
                continue;
            }
            int distancia = matriz.km(instancia.puntoUnidad(u), punto);
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
        return anadirVigentesLibres(cantidad, secuencia, longitud);
    }

    /** Anade a la lista de candidatas las unidades vigentes de las tareas que sigan libres. */
    private int anadirVigentesLibres(int cantidad, int[] secuencia, int longitud) {
        if (!estabilidad.activa()) {
            return cantidad;
        }
        for (int i = 0; i < longitud && cantidad < candidatos.length; i++) {
            final int unidadPrevia = estabilidad.unidadVigente(secuencia[i]);
            if (unidadPrevia < 0 || !libre[unidadPrevia]) {
                continue;
            }
            boolean repetida = false;
            for (int c = 0; c < cantidad; c++) {
                if (candidatos[c] == unidadPrevia) {
                    repetida = true;
                    break;
                }
            }
            if (!repetida) {
                candidatos[cantidad++] = unidadPrevia;
            }
        }
        return cantidad;
    }

    // ------------------------------------------------------ primitivas de ruta

    /**
     * Valor penalizado de la ruta: costo de operacion, mas la penalizacion dinamica de sus
     * minutos de desfase, mas el termino blando de estabilidad del apartado 11.4 del ISA por
     * cada pedido que cambia de unidad respecto del plan vigente.
     */
    private double valor(int r, double peso) {
        return costoRuta[r] + peso * desfaseRuta[r] + estabilidad.peso() * desviacionRuta[r];
    }

    /**
     * Cota inferior del valor penalizado de una secuencia atendida por una unidad: el costo de
     * sus arcos sin resolver los abastecimientos, mas el termino de estabilidad, que si es
     * exacto. Es cota inferior porque las distancias de la matriz son caminos minimos sobre la
     * reticula y cumplen la desigualdad triangular, de modo que intercalar una parada de
     * abastecimiento nunca acorta el recorrido, y porque el desfase nunca es negativo.
     *
     * <p>Que el termino de estabilidad entre tambien aqui, y no solo en el valor exacto, es lo
     * que mantiene apretada la poda: si la cota se quedase en el costo de arcos mientras el
     * valor a batir ya incluye la estabilidad, se descartarian menos movimientos y cada uno de
     * los rescatados costaria una llamada al decodificador, que es varias veces mas cara.</p>
     */
    private double cotaDeSecuencia(int unidadRuta, int[] secuencia, int longitud) {
        if (longitud == 0) {
            return 0.0;
        }
        final MatrizDistancias matriz = instancia.matriz();
        int punto = instancia.puntoUnidad(unidadRuta);
        long kilometros = 0;
        for (int i = 0; i < longitud; i++) {
            int siguiente = tareas.punto(secuencia[i]);
            int tramo = matriz.km(punto, siguiente);
            if (tramo >= MatrizDistancias.INALCANZABLE) {
                return INFINITO;
            }
            kilometros += tramo;
            punto = siguiente;
        }
        return kilometros * instancia.unidadTipo(unidadRuta).costoPorKm()
                + estabilidad.penalizacion(unidadRuta, secuencia, longitud);
    }

    /**
     * Poda exacta de un movimiento que solo toca una ruta. El costo de arcos de la ruta
     * resultante es una cota inferior de su valor penalizado, porque el costo real solo puede
     * crecer con los desvios de abastecimiento y porque el desfase nunca es negativo. Si esa
     * cota ya alcanza al valor de la ruta actual, el movimiento no puede mejorar y se
     * descarta sin llamar al decodificador, que es varias veces mas caro. La poda no pierde
     * ninguna mejora: solo descarta movimientos que con seguridad no la son.
     */
    private boolean descartablePorDistancia(int r, int[] secuencia, int longitud, double peso) {
        return cotaDeSecuencia(unidad[r], secuencia, longitud) >= valor(r, peso) - EPSILON;
    }

    /** Misma poda para un movimiento que toca dos rutas. */
    private boolean descartablePorDistancia(int r1, int[] secuencia1, int longitud1,
                                            int r2, int[] secuencia2, int longitud2, double peso) {
        final double actual = valor(r1, peso) + valor(r2, peso) - EPSILON;
        double cota = cotaDeSecuencia(unidad[r1], secuencia1, longitud1);
        if (cota >= actual) {
            return true;
        }
        cota += cotaDeSecuencia(unidad[r2], secuencia2, longitud2);
        return cota >= actual;
    }

    /** Copia la ruta quitando un tramo. Devuelve la longitud resultante. */
    private int copiarQuitando(int r, int desde, int cuantos, int[] destino) {
        int n = 0;
        final int[] origen = ruta[r];
        for (int i = 0; i < largo[r]; i++) {
            if (i >= desde && i < desde + cuantos) {
                continue;
            }
            destino[n++] = origen[i];
        }
        return n;
    }

    /** Copia la base insertando el segmento de trabajo en la posicion indicada. */
    private int copiarInsertando(int[] base, int largoBase, int posicion, int cuantos,
                                 boolean invertido, int[] destino) {
        int n = 0;
        for (int i = 0; i < posicion; i++) {
            destino[n++] = base[i];
        }
        if (invertido) {
            for (int k = cuantos - 1; k >= 0; k--) {
                destino[n++] = segmento[k];
            }
        } else {
            for (int k = 0; k < cuantos; k++) {
                destino[n++] = segmento[k];
            }
        }
        for (int i = posicion; i < largoBase; i++) {
            destino[n++] = base[i];
        }
        return n;
    }

    /** Copia la ruta intercambiando dos tramos disjuntos de la misma ruta. */
    private int copiarIntercambiando(int r, int p1, int tamano1, int p2, int tamano2, int[] destino) {
        final int[] origen = ruta[r];
        int n = 0;
        int i = 0;
        while (i < largo[r]) {
            if (i == p1) {
                for (int k = 0; k < tamano2; k++) {
                    destino[n++] = origen[p2 + k];
                }
                i += tamano1;
            } else if (i == p2) {
                for (int k = 0; k < tamano1; k++) {
                    destino[n++] = origen[p1 + k];
                }
                i += tamano2;
            } else {
                destino[n++] = origen[i];
                i++;
            }
        }
        return n;
    }

    /** Copia la ruta sustituyendo un tramo propio por un tramo de otra ruta. */
    private int copiarReemplazando(int r, int posicion, int cuantos, int[] otra, int posicionOtra,
                                   int cuantosOtra, int[] destino) {
        final int[] origen = ruta[r];
        int n = 0;
        for (int i = 0; i < posicion; i++) {
            destino[n++] = origen[i];
        }
        for (int k = 0; k < cuantosOtra; k++) {
            destino[n++] = otra[posicionOtra + k];
        }
        for (int i = posicion + cuantos; i < largo[r]; i++) {
            destino[n++] = origen[i];
        }
        return n;
    }

    /**
     * Posicion de insercion de menor incremento de distancia. Es la estimacion barata que usa
     * el SWAP asterisco para no enumerar todas las inserciones con el decodificador.
     */
    private int mejorPosicionPorDistancia(int[] base, int largoBase, int unidadRuta, int tarea) {
        final MatrizDistancias matriz = instancia.matriz();
        final int punto = tareas.punto(tarea);
        int mejorPosicion = 0;
        long mejorIncremento = Long.MAX_VALUE;
        for (int posicion = 0; posicion <= largoBase; posicion++) {
            int anterior = posicion == 0
                    ? instancia.puntoUnidad(unidadRuta)
                    : tareas.punto(base[posicion - 1]);
            long incremento = matriz.km(anterior, punto);
            if (posicion < largoBase) {
                int siguiente = tareas.punto(base[posicion]);
                incremento += matriz.km(punto, siguiente) - (long) matriz.km(anterior, siguiente);
            }
            if (incremento < mejorIncremento) {
                mejorIncremento = incremento;
                mejorPosicion = posicion;
            }
        }
        return mejorPosicion;
    }

    /**
     * Valora una secuencia de tareas sobre una unidad concreta con el decodificador. Devuelve
     * el valor penalizado completo, con la misma composicion que {@link #valor(int, double)},
     * de modo que los dos son comparables termino a termino.
     */
    private double evaluarSecuencia(int unidadRuta, int[] secuencia, int longitud, double peso) {
        if (longitud == 0) {
            ultimoCosto = 0.0;
            ultimoDesfase = 0;
            return 0.0;
        }
        for (int i = 0; i < longitud; i++) {
            final int tarea = secuencia[i];
            pedidosBuffer[i] = tareas.pedido(tarea);
            cantidadesBuffer[i] = tareas.cantidadUnidades(tarea);
        }
        if (!programador.evaluar(unidadRuta, pedidosBuffer, cantidadesBuffer, longitud)) {
            ultimoCosto = 0.0;
            ultimoDesfase = 0;
            return INFINITO;
        }
        ultimoCosto = programador.ultimoCosto();
        ultimoDesfase = programador.ultimoDesfase();
        return ultimoCosto + peso * ultimoDesfase
                + estabilidad.penalizacion(unidadRuta, secuencia, longitud);
    }

    /**
     * Sustituye el contenido de una ruta y reindexa las posiciones de sus tareas. La desviacion
     * respecto del plan vigente se recalcula aqui y no se recibe como argumento porque es
     * funcion exacta de la secuencia y de la unidad, y porque no todo movimiento la aplica
     * sobre la ultima secuencia valorada: la reinsercion del banco reconstruye la mejor
     * insercion despues de haber tanteado otras. Recalcularla cuesta un recorrido de accesos a
     * un arreglo primitivo sobre una ruta de siete u ocho paradas.
     */
    private void aplicarUna(int r, int[] nuevo, int largoNuevo, double costo, int desfase) {
        System.arraycopy(nuevo, 0, ruta[r], 0, largoNuevo);
        largo[r] = largoNuevo;
        costoRuta[r] = costo;
        desfaseRuta[r] = desfase;
        desviacionRuta[r] = estabilidad.desviacion(unidad[r], ruta[r], largoNuevo);
        for (int i = 0; i < largoNuevo; i++) {
            rutaDeTarea[ruta[r][i]] = r;
            posicionDeTarea[ruta[r][i]] = i;
        }
    }

    private void aplicarDos(int r1, int[] nuevo1, int largo1, double costo1, int desfase1,
                            int r2, int[] nuevo2, int largo2, double costo2, int desfase2) {
        aplicarUna(r1, nuevo1, largo1, costo1, desfase1);
        aplicarUna(r2, nuevo2, largo2, costo2, desfase2);
    }
}
