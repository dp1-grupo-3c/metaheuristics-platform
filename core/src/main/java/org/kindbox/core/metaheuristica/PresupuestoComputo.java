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
 * <p>La medicion es de reloj de pared y no de tiempo de CPU, conforme a la verificacion
 * de validez del apartado 12.4.</p>
 */
public final class PresupuestoComputo {

    /**
     * Fraccion del presupuesto teorico que se asigna de forma efectiva. El apartado 2.3
     * la fija de forma conservadora en el 60 por ciento.
     */
    public static final double FRACCION_EFECTIVA = 0.60;

    private final long nanosPresupuesto;
    private final PerfilConvergencia perfil;
    private final boolean registrarPerfil;

    private long nanosInicio;
    private int siguienteHito;
    private long iteraciones;
    private volatile boolean cancelado;

    private PresupuestoComputo(long nanosPresupuesto, boolean registrarPerfil) {
        this.nanosPresupuesto = nanosPresupuesto;
        this.registrarPerfil = registrarPerfil;
        this.perfil = registrarPerfil ? new PerfilConvergencia() : null;
        this.nanosInicio = System.nanoTime();
    }

    /** Presupuesto expresado en milisegundos de reloj de pared. */
    public static PresupuestoComputo deMilisegundos(long milisegundos) {
        return new PresupuestoComputo(milisegundos * 1_000_000L, false);
    }

    /** Presupuesto expresado en milisegundos, con registro del perfil de convergencia. */
    public static PresupuestoComputo deMilisegundosConPerfil(long milisegundos) {
        return new PresupuestoComputo(milisegundos * 1_000_000L, true);
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
        return new PresupuestoComputo(milisegundos * 1_000_000L, true);
    }

    /** Reinicia el cronometro. Se invoca al arrancar cada ejecucion del planificador. */
    public PresupuestoComputo arrancar() {
        this.nanosInicio = System.nanoTime();
        this.siguienteHito = 0;
        this.iteraciones = 0;
        this.cancelado = false;
        return this;
    }

    /** Presupuesto total en milisegundos. */
    public long milisegundosTotales() {
        return nanosPresupuesto / 1_000_000L;
    }

    /** Milisegundos de reloj de pared transcurridos desde el arranque. */
    public long milisegundosTranscurridos() {
        return (System.nanoTime() - nanosInicio) / 1_000_000L;
    }

    /** Fraccion del presupuesto consumida, acotada a {@code [0,1]}. */
    public double fraccionConsumida() {
        double f = (double) (System.nanoTime() - nanosInicio) / nanosPresupuesto;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /** Indica si el presupuesto se agoto o si la ejecucion fue cancelada. */
    public boolean agotado() {
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
     * pendiente, porque solo compara la fraccion consumida contra el siguiente umbral.
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
}
