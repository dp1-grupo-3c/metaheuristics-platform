package org.kindbox.core.evaluacion;

/**
 * Evaluacion en tiempo constante de la concatenacion de secuencias, segun Vidal, Crainic,
 * Gendreau y Prins (2013) y el apartado 10 del ISA.
 *
 * <p>El resumen de una secuencia se compone de la duracion acumulada, el desfase temporal
 * absorbido, la ventana de instantes de arranque admisibles y la carga, la distancia y las
 * paradas acumuladas. La concatenacion es asociativa: en general eso permite precalcular los
 * prefijos y sufijos de una ruta y valorar un movimiento de reubicacion, intercambio o
 * inversion combinando a lo sumo tres resumenes, que es el algoritmo 1 del Anexo E del ISA.
 * El planificador no la usa para valorar movimientos sino solo para acotarlos, como sigue.</p>
 *
 * <h2>Papel en el planificador: filtro de cota inferior</h2>
 * <p>El resumen <b>no</b> sustituye al decodificador. {@link ProgramadorRuta} intercala
 * abastecimientos segun la carga a bordo y el inventario, y ubica la pausa de alimentacion
 * por enumeracion; nada de eso cabe en un resumen concatenable, de modo que el valor exacto
 * de una ruta y su factibilidad los sigue decidiendo el decodificador, como establece el
 * apartado 10. Lo que el resumen si da es una <b>cota inferior</b> del desfase que el
 * decodificador va a medir, y {@link ResumenesRuta} la usa como filtro previo de dos
 * maneras:</p>
 * <ul>
 *   <li>en ALNS, con el filtro activo por defecto, los operadores de reconstruccion
 *       precalculan una vez por unidad los resumenes de prefijo y sufijo de su ruta, valoran
 *       cada posicion de insercion con dos concatenaciones en tiempo constante y saltan sin
 *       decodificar toda posicion cuya cota ya es positiva;</li>
 *   <li>en HGS, con el filtro desactivado por defecto ({@code ParametrosHgs.filtroCotaInferior}),
 *       la educacion acumula el resumen parada a parada sobre la secuencia que el movimiento ya
 *       escribio para el decodificador, solo cuando la cota de kilometros no basta por si sola
 *       para descartarlo, y descarta el movimiento si su cota penalizada ya no mejora. Ahi el
 *       coste es lineal en las siete u ocho paradas de la ruta y no constante.</li>
 * </ul>
 * <p>El filtro solo poda lo que el decodificador habria rechazado, de modo que no cambia el
 * resultado de ninguno de los dos algoritmos; la razon de que la cota sea valida esta en
 * {@link ResumenesRuta}.</p>
 *
 * <p>La concatenacion existe en dos formas con la misma formula: la de este registro, que
 * es la especificacion legible y la que ejercitan las pruebas del apartado 14 del ISA, y
 * {@link #concatenar(long[], int, long[], int, long, long[], int)}, que opera sobre arreglos
 * primitivos en aritmetica larga para no asignar memoria en los bucles calientes. La forma
 * larga solo lleva los cuatro campos temporales, porque la carga, la distancia y las paradas
 * no intervienen en la cota del desfase.</p>
 *
 * <p>En PaqRap las ventanas de tiempo son de un solo extremo: un pedido impone un
 * instante limite de llegada y no un instante mas temprano. El instante mas temprano de
 * arranque lo fija la disponibilidad de la unidad y el mas tardio el cierre de su turno.
 * La formulacion general se conserva porque es la que hace correcta la concatenacion.</p>
 *
 * @param duracion         tiempo total desde el arranque de la secuencia hasta su fin,
 *                         incluyendo servicio y espera, ya descontado el desfase absorbido
 * @param desfase          suma de violaciones de instante limite absorbidas (time warp)
 * @param inicioMasTemprano instante mas temprano en que puede arrancar la secuencia
 * @param inicioMasTardio  instante mas tardio en que puede arrancar sin desfase adicional
 * @param carga            unidades del producto P entregadas por la secuencia
 * @param kilometros       kilometros recorridos dentro de la secuencia
 * @param paradas          numero de paradas de la secuencia
 */
public record DatosSecuencia(
        int duracion,
        int desfase,
        int inicioMasTemprano,
        int inicioMasTardio,
        int carga,
        int kilometros,
        int paradas) {

    /** Cota superior usada como instante mas tardio cuando no hay restriccion. */
    public static final int SIN_LIMITE = Integer.MAX_VALUE / 4;

    /** Secuencia vacia, elemento neutro de la concatenacion. */
    public static final DatosSecuencia VACIA = new DatosSecuencia(0, 0, 0, SIN_LIMITE, 0, 0, 0);

    /**
     * Resumen de una parada aislada.
     *
     * @param instanteMasTemprano primer instante en que la parada puede atenderse
     * @param instanteLimite      ultimo instante admisible de llegada
     * @param minutosServicio     duracion del servicio en la parada
     * @param carga               unidades entregadas
     */
    public static DatosSecuencia deParada(int instanteMasTemprano, int instanteLimite,
                                          int minutosServicio, int carga) {
        return new DatosSecuencia(minutosServicio, 0, instanteMasTemprano, instanteLimite, carga, 0, 1);
    }

    /**
     * Concatena dos secuencias separadas por un tramo de viaje.
     *
     * @param a               secuencia previa
     * @param b               secuencia posterior
     * @param minutosDeViaje  duracion del tramo entre el fin de {@code a} y el inicio de {@code b}
     * @param kilometrosTramo longitud del tramo
     */
    public static DatosSecuencia concatenar(DatosSecuencia a, DatosSecuencia b,
                                            int minutosDeViaje, int kilometrosTramo) {
        if (a.paradas == 0) {
            return new DatosSecuencia(b.duracion, b.desfase, b.inicioMasTemprano, b.inicioMasTardio,
                    b.carga, b.kilometros, b.paradas);
        }
        if (b.paradas == 0) {
            return a;
        }

        // Tiempo transcurrido desde el arranque de "a" hasta la llegada al inicio de "b".
        int delta = a.duracion - a.desfase + minutosDeViaje;
        // Espera si "b" no puede atenderse todavia al llegar.
        int esperaExtra = Math.max(0, b.inicioMasTemprano - delta - a.inicioMasTardio);
        // Desfase si la llegada a "b" excede su instante limite.
        int desfaseExtra = Math.max(0, a.inicioMasTemprano + delta - b.inicioMasTardio);

        int duracion = a.duracion + b.duracion + minutosDeViaje + esperaExtra;
        int desfase = a.desfase + b.desfase + desfaseExtra;
        int inicioMasTemprano = Math.max(b.inicioMasTemprano - delta, a.inicioMasTemprano) - esperaExtra;
        int inicioMasTardio = Math.min(saturarResta(b.inicioMasTardio, delta), a.inicioMasTardio) + desfaseExtra;

        return new DatosSecuencia(duracion, desfase, inicioMasTemprano, inicioMasTardio,
                a.carga + b.carga, a.kilometros + b.kilometros + kilometrosTramo,
                a.paradas + b.paradas);
    }

    // ------------------------------------------------ forma sobre arreglos primitivos

    /** Campos temporales de un resumen en los arreglos primitivos. */
    public static final int CAMPOS = 4;
    /** Desplazamiento de la duracion dentro de un resumen en arreglo. */
    public static final int DURACION = 0;
    /** Desplazamiento del desfase dentro de un resumen en arreglo. */
    public static final int DESFASE = 1;
    /** Desplazamiento del instante mas temprano de arranque dentro de un resumen en arreglo. */
    public static final int TEMPRANO = 2;
    /** Desplazamiento del instante mas tardio de arranque dentro de un resumen en arreglo. */
    public static final int TARDIO = 3;
    /**
     * Instante mas tardio de una secuencia sin restriccion en la forma larga. Es un octavo del
     * maximo para que ninguna suma o resta de la concatenacion desborde.
     */
    public static final long SIN_LIMITE_LARGO = Long.MAX_VALUE / 8;
    /** Instante mas temprano de una parada que nunca obliga a esperar, en la forma larga. */
    public static final long SIN_ESPERA_LARGO = -SIN_LIMITE_LARGO;

    /**
     * Misma operacion que {@link #concatenar(DatosSecuencia, DatosSecuencia, int, int)} sobre
     * los campos temporales de dos resumenes guardados en arreglos, a razon de {@link #CAMPOS}
     * valores por resumen. No asigna memoria y admite que el destino coincida con uno de los
     * operandos, porque lee todos los valores antes de escribir.
     *
     * @param a              arreglo del resumen previo
     * @param ia             posicion del primer campo del resumen previo
     * @param b              arreglo del resumen posterior
     * @param ib             posicion del primer campo del resumen posterior
     * @param minutosDeViaje duracion del tramo entre el fin de {@code a} y el inicio de {@code b}
     * @param destino        arreglo donde se escribe el resumen concatenado
     * @param id             posicion del primer campo del resumen concatenado
     */
    public static void concatenar(long[] a, int ia, long[] b, int ib, long minutosDeViaje,
                                  long[] destino, int id) {
        final long aDuracion = a[ia + DURACION];
        final long aDesfase = a[ia + DESFASE];
        final long aTemprano = a[ia + TEMPRANO];
        final long aTardio = a[ia + TARDIO];
        final long bDuracion = b[ib + DURACION];
        final long bDesfase = b[ib + DESFASE];
        final long bTemprano = b[ib + TEMPRANO];
        final long bTardio = b[ib + TARDIO];

        final long delta = aDuracion - aDesfase + minutosDeViaje;
        final long esperaExtra = Math.max(0L, bTemprano - delta - aTardio);
        final long desfaseExtra = Math.max(0L, aTemprano + delta - bTardio);

        destino[id + DURACION] = aDuracion + bDuracion + minutosDeViaje + esperaExtra;
        destino[id + DESFASE] = aDesfase + bDesfase + desfaseExtra;
        destino[id + TEMPRANO] = Math.max(bTemprano - delta, aTemprano) - esperaExtra;
        destino[id + TARDIO] = Math.min(bTardio - delta, aTardio) + desfaseExtra;
    }

    /** Escribe en el arreglo el resumen de una parada aislada, en la forma larga. */
    public static void fijar(long[] destino, int id, long duracion, long instanteMasTemprano,
                             long instanteLimite) {
        destino[id + DURACION] = duracion;
        destino[id + DESFASE] = 0L;
        destino[id + TEMPRANO] = instanteMasTemprano;
        destino[id + TARDIO] = instanteLimite;
    }

    /** Concatena tres secuencias, patron habitual al evaluar un movimiento de reubicacion. */
    public static DatosSecuencia concatenar(DatosSecuencia a, DatosSecuencia b, DatosSecuencia c,
                                            int viajeAB, int kmAB, int viajeBC, int kmBC) {
        return concatenar(concatenar(a, b, viajeAB, kmAB), c, viajeBC, kmBC);
    }

    /** Indica si la secuencia respeta todos los instantes limite de sus paradas. */
    public boolean sinDesfase() {
        return desfase == 0;
    }

    /**
     * Instante en que termina la secuencia si arranca en el instante dado.
     * Solo tiene sentido cuando {@code inicio} pertenece a la ventana admisible.
     */
    public int instanteFin(int inicio) {
        return inicio + duracion;
    }

    /** Indica si la secuencia puede arrancar en el instante dado sin generar desfase. */
    public boolean arrancaEn(int inicio) {
        return inicio >= inicioMasTemprano && inicio <= inicioMasTardio;
    }

    /** Resta que no desborda cuando el minuendo es la cota {@link #SIN_LIMITE}. */
    private static int saturarResta(int valor, int resta) {
        long r = (long) valor - resta;
        if (r > SIN_LIMITE) {
            return SIN_LIMITE;
        }
        if (r < Integer.MIN_VALUE / 4) {
            return Integer.MIN_VALUE / 4;
        }
        return (int) r;
    }
}
