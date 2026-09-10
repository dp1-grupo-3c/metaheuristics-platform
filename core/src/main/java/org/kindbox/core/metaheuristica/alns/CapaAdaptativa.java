package org.kindbox.core.metaheuristica.alns;

import java.util.Arrays;
import org.kindbox.core.util.Aleatorio;

/**
 * Capa adaptativa de la busqueda de vecindad amplia, apartado 7.3.3 del ISA.
 *
 * <p>Mantiene un peso por operador de destruccion y por operador de reconstruccion. La
 * eleccion de cada iteracion es una ruleta ponderada sobre esos pesos. Cada iteracion reparte
 * una puntuacion que depende de lo que consiguio (nueva mejor solucion global, mejora sobre la
 * vigente, o solucion aceptada aunque peor) y al cerrarse un segmento de iteraciones los pesos
 * se actualizan mezclando el peso anterior con la puntuacion media del segmento segun la tasa
 * de reaccion:</p>
 *
 * <pre>  w &lt;- (1 - r) * w + r * (puntuacion acumulada / usos)</pre>
 *
 * <p>Un peso minimo impide que un operador que atraviesa un mal segmento quede apagado para
 * siempre, que es el modo tipico en que esta capa se degrada.</p>
 *
 * <h2>Cuanto aporta esta capa</h2>
 * <p>El metaanalisis de Turkes, Sorensen y Hvattum (2021) sobre 25 implementaciones de ALNS
 * cuantifica el aporte de la adaptatividad en una mejora media del <b>0.14 por ciento</b>
 * respecto de elegir los operadores de forma uniforme. Es una mejora real pero pequena: el
 * rendimiento del metodo proviene del diseno del conjunto de operadores y no de esta capa. La
 * consecuencia practica para el proyecto es donde poner el esfuerzo, y por eso los operadores
 * propios del apartado 7.3.2 estan dirigidos al nivel 1 del objetivo mientras que esta clase
 * se mantiene en su forma canonica, sin variantes ni ajustes finos que no tendrian efecto
 * medible.</p>
 */
public final class CapaAdaptativa {

    /** La iteracion produjo una nueva mejor solucion global. */
    public static final int NUEVA_MEJOR = 0;
    /** La iteracion mejoro la solucion vigente. */
    public static final int MEJORA = 1;
    /** La solucion se acepto pese a ser peor que la vigente. */
    public static final int ACEPTADA = 2;
    /** La solucion se rechazo. */
    public static final int RECHAZADA = 3;

    private final double[] pesosDestruccion;
    private final double[] pesosReconstruccion;
    private final double[] puntajeDestruccion;
    private final double[] puntajeReconstruccion;
    private final int[] usosDestruccion;
    private final int[] usosReconstruccion;
    private final double[] puntajePorResultado;
    private final double tasaReaccion;
    private final double pesoMinimo;
    private final int longitudSegmento;

    private int iteracionesDelSegmento;
    private long segmentosCerrados;

    /**
     * @param cantidadDestruccion    numero de operadores de destruccion
     * @param cantidadReconstruccion numero de operadores de reconstruccion
     * @param parametros             parametros del apartado 7.4
     */
    public CapaAdaptativa(int cantidadDestruccion, int cantidadReconstruccion, ParametrosAlns parametros) {
        this.pesosDestruccion = new double[cantidadDestruccion];
        this.pesosReconstruccion = new double[cantidadReconstruccion];
        this.puntajeDestruccion = new double[cantidadDestruccion];
        this.puntajeReconstruccion = new double[cantidadReconstruccion];
        this.usosDestruccion = new int[cantidadDestruccion];
        this.usosReconstruccion = new int[cantidadReconstruccion];
        this.tasaReaccion = parametros.tasaReaccion();
        this.pesoMinimo = parametros.pesoMinimoOperador();
        this.longitudSegmento = parametros.longitudSegmento();
        this.puntajePorResultado = new double[]{
                parametros.puntajeNuevaMejor(),
                parametros.puntajeMejora(),
                parametros.puntajeAceptada(),
                0.0};
        reiniciar();
    }

    /** Devuelve todos los pesos a uno y vacia el segmento en curso. */
    public void reiniciar() {
        Arrays.fill(pesosDestruccion, 1.0);
        Arrays.fill(pesosReconstruccion, 1.0);
        Arrays.fill(puntajeDestruccion, 0.0);
        Arrays.fill(puntajeReconstruccion, 0.0);
        Arrays.fill(usosDestruccion, 0);
        Arrays.fill(usosReconstruccion, 0);
        iteracionesDelSegmento = 0;
        segmentosCerrados = 0;
    }

    /** Elige un operador de destruccion por ruleta ponderada. */
    public int elegirDestruccion(Aleatorio aleatorio) {
        return aleatorio.ruleta(pesosDestruccion);
    }

    /** Elige un operador de reconstruccion por ruleta ponderada. */
    public int elegirReconstruccion(Aleatorio aleatorio) {
        return aleatorio.ruleta(pesosReconstruccion);
    }

    /**
     * Anota el resultado de una iteracion y avanza el segmento. Debe invocarse exactamente una
     * vez por iteracion del bucle principal.
     *
     * @param destruccion    operador de destruccion empleado
     * @param reconstruccion operador de reconstruccion empleado
     * @param resultado      una de las constantes de resultado de esta clase
     */
    public void registrar(int destruccion, int reconstruccion, int resultado) {
        double puntaje = puntajePorResultado[resultado];
        puntajeDestruccion[destruccion] += puntaje;
        puntajeReconstruccion[reconstruccion] += puntaje;
        usosDestruccion[destruccion]++;
        usosReconstruccion[reconstruccion]++;
        if (++iteracionesDelSegmento >= longitudSegmento) {
            cerrarSegmento();
        }
    }

    /** Segmentos de iteraciones completados durante la corrida. */
    public long segmentosCerrados() {
        return segmentosCerrados;
    }

    /** Peso vigente del operador de destruccion, para los reportes del apartado 12. */
    public double pesoDestruccion(int operador) {
        return pesosDestruccion[operador];
    }

    /** Peso vigente del operador de reconstruccion, para los reportes del apartado 12. */
    public double pesoReconstruccion(int operador) {
        return pesosReconstruccion[operador];
    }

    private void cerrarSegmento() {
        actualizar(pesosDestruccion, puntajeDestruccion, usosDestruccion);
        actualizar(pesosReconstruccion, puntajeReconstruccion, usosReconstruccion);
        iteracionesDelSegmento = 0;
        segmentosCerrados++;
    }

    private void actualizar(double[] pesos, double[] puntajes, int[] usos) {
        for (int i = 0; i < pesos.length; i++) {
            if (usos[i] > 0) {
                double medio = puntajes[i] / usos[i];
                pesos[i] = Math.max(pesoMinimo, (1.0 - tasaReaccion) * pesos[i] + tasaReaccion * medio);
            }
            puntajes[i] = 0.0;
            usos[i] = 0;
        }
    }
}
