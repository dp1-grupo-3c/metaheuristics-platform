package org.kindbox.core.modelo;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Parametros de operacion modificables en caliente.
 *
 * <p>La respuesta 6 del cuestionario exige que las velocidades puedan cambiarse por
 * parametro mientras el software esta funcionando, y la respuesta 15 precisa que el
 * cambio es por tipo de unidad y no por unidad individual. La respuesta 16 fija la
 * semantica: el nuevo valor se aplica a partir de la siguiente iteracion de
 * planificacion.</p>
 *
 * <p>Esa semantica se implementa con dos objetos. Esta clase es el estado vivo, que el
 * servicio REST modifica en cualquier momento. Al arrancar cada iteracion el planificador
 * toma un {@link Instantanea} inmutable y trabaja sobre el, de modo que un cambio a mitad
 * de iteracion nunca produce una solucion evaluada con dos juegos de parametros.</p>
 */
public final class ParametrosOperacion {

    /** Tiempo de entrega al destinatario, en minutos. Es por entrega, incluso si es parcial (respuesta 14). */
    public static final int MINUTOS_ACONDICIONAMIENTO_POR_DEFECTO = 60;
    /** Duracion de la hora de alimentacion del conductor, en minutos. */
    public static final int MINUTOS_ALIMENTACION_POR_DEFECTO = 60;
    /** Separacion minima entre la alimentacion y un cambio de turno, en minutos. */
    public static final int MINUTOS_SEPARACION_CAMBIO_TURNO_POR_DEFECTO = 60;
    /** Tiempo de trasvase de carga entre unidades, en minutos (respuesta 13). */
    public static final int MINUTOS_TRASVASE_POR_DEFECTO = 30;

    private final ReentrantLock candado = new ReentrantLock();

    private final Map<TipoUnidad, Double> velocidades = new EnumMap<>(TipoUnidad.class);
    private volatile int minutosAcondicionamiento = MINUTOS_ACONDICIONAMIENTO_POR_DEFECTO;
    private volatile int minutosAlimentacion = MINUTOS_ALIMENTACION_POR_DEFECTO;
    private volatile int minutosSeparacionCambioTurno = MINUTOS_SEPARACION_CAMBIO_TURNO_POR_DEFECTO;
    private volatile int minutosTrasvase = MINUTOS_TRASVASE_POR_DEFECTO;
    private volatile int umbralSemaforoVerde = 500;
    private volatile int umbralSemaforoAmbar = 250;
    private volatile long version;

    public ParametrosOperacion() {
        for (TipoUnidad t : TipoUnidad.values()) {
            velocidades.put(t, t.velocidadPorDefecto());
        }
    }

    /** Velocidad vigente del tipo, en Km/h. */
    public double velocidad(TipoUnidad tipo) {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de unidad es obligatorio");
        }
        candado.lock();
        try {
            return velocidades.get(tipo);
        } finally {
            candado.unlock();
        }
    }

    /**
     * Cambia la velocidad de un tipo de unidad. El valor se aplica a partir de la
     * siguiente iteracion de planificacion.
     */
    public void velocidad(TipoUnidad tipo, double kmPorHora) {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de unidad es obligatorio");
        }
        if (kmPorHora <= 0 || !Double.isFinite(kmPorHora)) {
            throw new IllegalArgumentException("Velocidad invalida para " + tipo + ": " + kmPorHora);
        }
        candado.lock();
        try {
            velocidades.put(tipo, kmPorHora);
            version++;
        } finally {
            candado.unlock();
        }
    }

    public int minutosAcondicionamiento() {
        return minutosAcondicionamiento;
    }

    public void minutosAcondicionamiento(int minutos) {
        exigirPositivo(minutos, "acondicionamiento");
        this.minutosAcondicionamiento = minutos;
        bumpVersion();
    }

    public int minutosAlimentacion() {
        return minutosAlimentacion;
    }

    public void minutosAlimentacion(int minutos) {
        exigirPositivo(minutos, "alimentacion");
        this.minutosAlimentacion = minutos;
        bumpVersion();
    }

    public int minutosSeparacionCambioTurno() {
        return minutosSeparacionCambioTurno;
    }

    public void minutosSeparacionCambioTurno(int minutos) {
        if (minutos < 0) {
            throw new IllegalArgumentException("Separacion negativa del cambio de turno");
        }
        this.minutosSeparacionCambioTurno = minutos;
        bumpVersion();
    }

    public int minutosTrasvase() {
        return minutosTrasvase;
    }

    public void minutosTrasvase(int minutos) {
        exigirPositivo(minutos, "trasvase");
        this.minutosTrasvase = minutos;
        bumpVersion();
    }

    /** Umbral inferior del intervalo verde del semaforo de almacenes. */
    public int umbralSemaforoVerde() {
        return umbralSemaforoVerde;
    }

    /** Umbral inferior del intervalo ambar del semaforo de almacenes. */
    public int umbralSemaforoAmbar() {
        return umbralSemaforoAmbar;
    }

    /** Fija los umbrales del semaforo de almacenes exigido por el requisito no funcional (d). */
    public void umbralesSemaforo(int ambar, int verde) {
        if (ambar < 0 || verde <= ambar) {
            throw new IllegalArgumentException("Umbrales de semaforo invalidos: ambar=" + ambar + " verde=" + verde);
        }
        this.umbralSemaforoAmbar = ambar;
        this.umbralSemaforoVerde = verde;
        bumpVersion();
    }

    /** Numero de version, que se incrementa con cada cambio. Permite detectar reconfiguraciones. */
    public long version() {
        return version;
    }

    /**
     * Toma una fotografia inmutable de los parametros vigentes. El planificador la captura
     * al arrancar cada iteracion y no vuelve a consultar el estado vivo hasta la siguiente.
     */
    public Instantanea instantanea() {
        candado.lock();
        try {
            double[] velocidadPorTipo = new double[TipoUnidad.values().length];
            for (TipoUnidad t : TipoUnidad.values()) {
                velocidadPorTipo[t.ordinal()] = velocidades.get(t);
            }
            return new Instantanea(velocidadPorTipo, minutosAcondicionamiento, minutosAlimentacion,
                    minutosSeparacionCambioTurno, minutosTrasvase, umbralSemaforoAmbar, umbralSemaforoVerde, version);
        } finally {
            candado.unlock();
        }
    }

    private void bumpVersion() {
        candado.lock();
        try {
            version++;
        } finally {
            candado.unlock();
        }
    }

    private static void exigirPositivo(int minutos, String que) {
        if (minutos <= 0) {
            throw new IllegalArgumentException("Duracion de " + que + " no positiva: " + minutos);
        }
    }

    /**
     * Fotografia inmutable de los parametros, valida durante una iteracion completa
     * de planificacion.
     *
     * @param velocidadKmH              velocidad por tipo, indexada por {@code TipoUnidad.ordinal()}
     * @param minutosAcondicionamiento  tiempo de entrega en el cliente
     * @param minutosAlimentacion       duracion de la pausa de alimentacion
     * @param minutosSeparacionCambioTurno separacion minima entre la pausa y un cambio de turno
     * @param minutosTrasvase           tiempo de trasvase de carga entre unidades
     * @param umbralSemaforoAmbar       umbral inferior del intervalo ambar
     * @param umbralSemaforoVerde       umbral inferior del intervalo verde
     * @param version                   version de los parametros que produjo esta fotografia
     */
    public record Instantanea(
            double[] velocidadKmH,
            int minutosAcondicionamiento,
            int minutosAlimentacion,
            int minutosSeparacionCambioTurno,
            int minutosTrasvase,
            int umbralSemaforoAmbar,
            int umbralSemaforoVerde,
            long version) {

        public Instantanea {
            if (velocidadKmH == null || velocidadKmH.length != TipoUnidad.values().length) {
                throw new IllegalArgumentException("La instantanea necesita una velocidad por cada tipo de unidad");
            }
            for (double velocidad : velocidadKmH) {
                if (velocidad <= 0.0 || !Double.isFinite(velocidad)) {
                    throw new IllegalArgumentException("La instantanea contiene una velocidad invalida");
                }
            }
            if (minutosAcondicionamiento <= 0 || minutosAlimentacion <= 0
                    || minutosSeparacionCambioTurno < 0 || minutosTrasvase <= 0
                    || umbralSemaforoAmbar < 0 || umbralSemaforoVerde <= umbralSemaforoAmbar) {
                throw new IllegalArgumentException("La instantanea contiene parametros operativos invalidos");
            }
            velocidadKmH = velocidadKmH.clone();
        }

        /** Velocidad del tipo en Km/h. */
        public double velocidad(TipoUnidad tipo) {
            return velocidadKmH[tipo.ordinal()];
        }

        /**
         * Minutos que tarda el tipo indicado en recorrer los kilometros dados.
         * Se redondea hacia arriba porque el reloj de la simulacion avanza en minutos
         * enteros y adelantar la llegada haria factibles rutas que no lo son.
         */
        public int minutosDeViaje(TipoUnidad tipo, int kilometros) {
            if (kilometros <= 0) {
                return 0;
            }
            double horas = kilometros / velocidadKmH[tipo.ordinal()];
            return (int) Math.ceil(horas * 60.0 - 1e-9);
        }

        /** Costo en soles de recorrer los kilometros dados con el tipo indicado. */
        public double costo(TipoUnidad tipo, int kilometros) {
            return kilometros * tipo.costoPorKm();
        }
    }
}
