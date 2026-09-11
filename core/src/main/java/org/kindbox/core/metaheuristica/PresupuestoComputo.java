package org.kindbox.core.metaheuristica;

import java.util.function.Supplier;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Control de presupuesto del apartado 2.3 del ISA.
 *
 * <p>El presupuesto real por ejecucion se deriva del salto de planificacion SA dividido
 * entre el factor de aceleracion K, afectado por una fraccion conservadora que reserva
 * reloj para el motor de simulacion y el visualizador. En las configuraciones previstas
 * se situa entre 2 y 18 segundos, de modo que ambos algoritmos deben ser interrumpibles
 * en cualquier instante y devolver siempre la mejor solucion factible conocida.</p>
 *
 * <h2>Dos modos</h2>
 * <p>En <b>modo por reloj</b>, el de operacion, la medicion es de reloj de pared y no de
 * tiempo de CPU, conforme a la verificacion de validez del apartado 12.4. Es el unico modo
 * que respeta el presupuesto del apartado 2.3, pero ninguna corrida es reproducible bit a
 * bit: el numero de iteraciones que caben en el reloj depende de la maquina y de su carga, y
 * con el cambian todas las decisiones que se toman en funcion de la fraccion consumida.</p>
 *
 * <p>En <b>modo por iteraciones</b>, que se crea con {@link #deIteraciones(long)}, el
 * presupuesto es un numero fijo de iteraciones o generaciones del bucle principal:
 * {@link #agotado()} y {@link #fraccionConsumida()} pasan a ser funciones puras del contador
 * de {@link #contarIteracion()}, y el reloj queda solo como dato informativo en
 * {@link #milisegundosTranscurridos()}. Con la misma semilla y la misma instancia, dos
 * corridas toman entonces exactamente las mismas decisiones y devuelven el mismo plan, que
 * es lo que necesitan las pruebas de regresion y la depuracion de las corridas repetidas del
 * apartado 12.3. El contador solo avanza en el bucle principal de cada algoritmo, de modo que
 * durante las fases previas la fraccion consumida vale cero y {@link #agotado()} solo se
 * vuelve cierto por cancelacion; cada algoritmo documenta como acota esas fases.</p>
 */
public final class PresupuestoComputo {

    /**
     * Fraccion del presupuesto teorico que se asigna de forma efectiva. El apartado 2.3
     * la fija de forma conservadora en el 60 por ciento.
     */
    public static final double FRACCION_EFECTIVA = 0.60;

    private final long nanosPresupuesto;
    /** Tope de iteraciones del modo por iteraciones; cero en modo por reloj. */
    private final long limiteIteraciones;
    private final PerfilConvergencia perfil;
    private final boolean registrarPerfil;

    private long nanosInicio;
    private int siguienteHito;
    private long iteraciones;
    private volatile boolean cancelado;

    private PresupuestoComputo(long nanosPresupuesto, long limiteIteraciones, boolean registrarPerfil) {
        this.nanosPresupuesto = nanosPresupuesto;
        this.limiteIteraciones = limiteIteraciones;
        this.registrarPerfil = registrarPerfil;
        this.perfil = registrarPerfil ? new PerfilConvergencia() : null;
        this.nanosInicio = System.nanoTime();
    }

    /** Presupuesto expresado en milisegundos de reloj de pared. */
    public static PresupuestoComputo deMilisegundos(long milisegundos) {
        return new PresupuestoComputo(milisegundos * 1_000_000L, 0L, false);
    }

    /** Presupuesto expresado en milisegundos, con registro del perfil de convergencia. */
    public static PresupuestoComputo deMilisegundosConPerfil(long milisegundos) {
        return new PresupuestoComputo(milisegundos * 1_000_000L, 0L, true);
    }

    /**
     * Presupuesto determinista de {@code n} iteraciones o generaciones del bucle principal.
     * Se agota cuando el contador alcanza {@code n} o cuando se cancela, nunca por reloj.
     *
     * @param n numero de iteraciones, mayor que cero
     */
    public static PresupuestoComputo deIteraciones(long n) {
        return new PresupuestoComputo(0L, exigirIteraciones(n), false);
    }

    /** Presupuesto determinista de {@code n} iteraciones, con registro del perfil de convergencia. */
    public static PresupuestoComputo deIteracionesConPerfil(long n) {
        return new PresupuestoComputo(0L, exigirIteraciones(n), true);
    }

    /**
     * Presupuesto derivado de la configuracion de la simulacion, conforme al apartado 2.3.
     *
     * @param saltoMinutosSimulados salto SA entre dos ejecuciones sucesivas del planificador
     * @param factorAceleracion     minutos simulados que transcurren por cada minuto real
     */
    public static PresupuestoComputo deSimulacion(int saltoMinutosSimulados, double factorAceleracion) {
        if (saltoMinutosSimulados <= 0 || factorAceleracion <= 0) {
            throw new IllegalArgumentException("Salto o factor de aceleracion invalidos");
        }
        double segundosTeoricos = saltoMinutosSimulados * 60.0 / factorAceleracion;
        long milisegundos = Math.max(50L, Math.round(segundosTeoricos * FRACCION_EFECTIVA * 1000.0));
        return new PresupuestoComputo(milisegundos * 1_000_000L, 0L, true);
    }

    /** Reinicia el cronometro y el contador. Se invoca al arrancar cada ejecucion del planificador. */
    public PresupuestoComputo arrancar() {
        this.nanosInicio = System.nanoTime();
        this.siguienteHito = 0;
        this.iteraciones = 0;
        this.cancelado = false;
        return this;
    }

    /** Indica si el presupuesto es un numero fijo de iteraciones y no un intervalo de reloj. */
    public boolean porIteraciones() {
        return limiteIteraciones > 0L;
    }

    /** Tope de iteraciones del modo por iteraciones, o cero en modo por reloj. */
    public long limiteIteraciones() {
        return limiteIteraciones;
    }

    /** Presupuesto total en milisegundos; cero en modo por iteraciones, que no tiene reloj. */
    public long milisegundosTotales() {
        return nanosPresupuesto / 1_000_000L;
    }

    /** Milisegundos de reloj de pared transcurridos desde el arranque. En modo por iteraciones es solo informativo. */
    public long milisegundosTranscurridos() {
        return (System.nanoTime() - nanosInicio) / 1_000_000L;
    }

    /**
     * Fraccion del presupuesto consumida, acotada a {@code [0,1]}. En modo por iteraciones es
     * {@code min(1, iteraciones / n)} y no depende del reloj.
     */
    public double fraccionConsumida() {
        if (limiteIteraciones > 0L) {
            return Math.min(1.0, (double) iteraciones / limiteIteraciones);
        }
        double f = (double) (System.nanoTime() - nanosInicio) / nanosPresupuesto;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /**
     * Indica si el presupuesto se agoto o si la ejecucion fue cancelada. En modo por
     * iteraciones se agota exactamente cuando el contador alcanza el tope.
     */
    public boolean agotado() {
        if (limiteIteraciones > 0L) {
            return cancelado || iteraciones >= limiteIteraciones;
        }
        return cancelado || (System.nanoTime() - nanosInicio) >= nanosPresupuesto;
    }

    /** Cancela la ejecucion en curso desde otro hilo. */
    public void cancelar() {
        this.cancelado = true;
    }

    /** Incrementa el contador de iteraciones o generaciones. */
    public void contarIteracion() {
        iteraciones++;
    }

    /** Iteraciones o generaciones completadas. */
    public long iteraciones() {
        return iteraciones;
    }

    /**
     * Toma las mediciones del perfil de convergencia que correspondan al instante actual.
     * Se invoca desde el bucle principal de cada algoritmo; es barata cuando no hay hito
     * pendiente, porque solo compara la fraccion consumida contra el siguiente umbral. En
     * modo por iteraciones los hitos caen siempre en la misma iteracion.
     *
     * @param mejorConocida proveedor perezoso del mejor valor conocido, evaluado solo si hay hito
     */
    public void muestrear(Supplier<ValorObjetivo> mejorConocida) {
        if (!registrarPerfil || siguienteHito >= PerfilConvergencia.HITOS.length) {
            return;
        }
        double f = fraccionConsumida();
        while (siguienteHito < PerfilConvergencia.HITOS.length
                && f >= PerfilConvergencia.HITOS[siguienteHito]) {
            perfil.registrar(PerfilConvergencia.HITOS[siguienteHito],
                    milisegundosTranscurridos(), mejorConocida.get(), iteraciones);
            siguienteHito++;
        }
    }

    /** Cierra el perfil registrando los hitos que quedaron pendientes con el valor final. */
    public void cerrarPerfil(ValorObjetivo valorFinal) {
        if (!registrarPerfil) {
            return;
        }
        long ms = milisegundosTranscurridos();
        while (siguienteHito < PerfilConvergencia.HITOS.length) {
            perfil.registrar(PerfilConvergencia.HITOS[siguienteHito], ms, valorFinal, iteraciones);
            siguienteHito++;
        }
    }

    /** Perfil de convergencia registrado, o {@code null} si el registro esta desactivado. */
    public PerfilConvergencia perfil() {
        return perfil;
    }

    private static long exigirIteraciones(long n) {
        if (n <= 0L) {
            throw new IllegalArgumentException("El presupuesto por iteraciones debe ser positivo: " + n);
        }
        return n;
    }
}
