package org.kindbox.core.evaluacion;

/**
 * Evaluacion en tiempo constante de la concatenacion de secuencias, segun Vidal, Crainic,
 * Gendreau y Prins (2013) y el apartado 10 del ISA.
 *
 * <p>Recalcular el valor completo de una ruta tras cada movimiento tiene costo lineal en
 * el numero de paradas. Con un presupuesto de entre 2 y 18 segundos y decenas de miles de
 * evaluaciones por ejecucion, esa diferencia decide si el algoritmo alcanza a mejorar la
 * solucion constructiva. Por eso cada subsecuencia guarda un resumen que permite evaluar
 * la concatenacion de dos subsecuencias en tiempo constante.</p>
 *
 * <p>El resumen se compone de la duracion acumulada, el desfase temporal absorbido, la
 * ventana de instantes de arranque admisibles y la carga y distancia acumuladas. La
 * operacion de concatenacion es asociativa, lo que permite precalcular los prefijos y
 * sufijos de una ruta y evaluar cualquier movimiento de reubicacion, intercambio o
 * inversion combinando a lo sumo tres resumenes.</p>
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
