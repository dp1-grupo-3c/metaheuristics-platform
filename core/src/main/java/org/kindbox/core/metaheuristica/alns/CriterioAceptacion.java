package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.util.Aleatorio;

/**
 * Criterio de aceptacion por recocido simulado con enfriamiento exponencial, apartado 7.3.3
 * del ISA.
 *
 * <p>Una solucion candidata mejor que la vigente se acepta siempre; una peor se acepta con
 * probabilidad {@code exp(-delta / T)}. La temperatura arranca en un valor fijado en relacion
 * con el costo de la solucion inicial, de modo que al principio se acepte con probabilidad un
 * medio un empeoramiento de la fraccion configurada, y desciende de forma geometrica hasta una
 * temperatura final tan baja que aceptar cualquier empeoramiento resulte despreciable.</p>
 *
 * <h2>Por que el enfriamiento va contra el tiempo y no contra las iteraciones</h2>
 * <p>Este es el punto critico del diseno. El presupuesto del apartado 2.3 se mide en
 * <b>segundos de reloj de pared</b>, entre 2 y 18 segun la configuracion de la simulacion, y
 * no en iteraciones. Un esquema clasico {@code T <- alfa * T} por iteracion produciria
 * temperaturas finales completamente distintas en cada configuracion: con 18 segundos se
 * completan del orden de diez veces mas iteraciones que con 2, de modo que la corrida larga
 * terminaria congelada desde mucho antes del final y la corta terminaria todavia caliente, sin
 * haber explotado nunca. El comportamiento del algoritmo dejaria de ser comparable entre las
 * tres configuraciones de presupuesto, que es justo lo que el experimento del apartado 12
 * necesita medir.</p>
 *
 * <p>Por eso la temperatura se parametriza contra la <b>fraccion de presupuesto consumida</b>
 * que devuelve {@code PresupuestoComputo.fraccionConsumida()}:</p>
 *
 * <pre>  T(f) = T0 * (Tf / T0)^f,  f en [0,1]</pre>
 *
 * <p>Asi el perfil de enfriamiento es el mismo en las tres configuraciones: al 50 por ciento
 * del presupuesto la temperatura es la misma fraccion de la inicial corran las iteraciones que
 * corran, y toda corrida termina igual de fria. El numero de iteraciones deja de ser un
 * parametro implicito del criterio de aceptacion y pasa a ser lo unico que distingue a una
 * configuracion de otra, que es lo que se quiere medir.</p>
 *
 * <h2>Modo por iteraciones</h2>
 * <p>Con un presupuesto de {@code PresupuestoComputo.deIteraciones(n)} la fraccion consumida
 * vale {@code k / n} tras {@code k} iteraciones y el reloj no interviene, de modo que la
 * temperatura de cada iteracion es una funcion pura del contador:
 * {@code T(k) = T0 * (Tf / T0)^(k/n)}. Es exactamente el enfriamiento geometrico clasico
 * {@code T <- alfa * T} con {@code alfa = (Tf / T0)^(1/n)}, calibrado para terminar igual de
 * frio que la corrida por reloj, y con la misma semilla dos corridas aceptan y rechazan los
 * mismos candidatos. La reproduccion bit a bit se garantiza sobre la misma maquina virtual y
 * la misma plataforma: {@code Math.pow} y {@code Math.exp} admiten un error de un ulp que
 * puede variar entre plataformas, y en un caso limite ese ulp decide una aceptacion.</p>
 */
public final class CriterioAceptacion {

    /** Exponente por debajo del cual la probabilidad de aceptar es indistinguible de cero. */
    private static final double EXPONENTE_DESPRECIABLE = -40.0;

    private final double fraccionTemperaturaInicial;
    private final double fraccionTemperaturaFinal;

    private double temperaturaInicial;
    private double razonEnfriamiento;

    public CriterioAceptacion(ParametrosAlns parametros) {
        this.fraccionTemperaturaInicial = parametros.fraccionTemperaturaInicial();
        this.fraccionTemperaturaFinal = parametros.fraccionTemperaturaFinal();
        calibrar(1.0);
    }

    /**
     * Fija las temperaturas en relacion con el valor de la solucion inicial.
     *
     * @param valorInicial escalar interno de la solucion de partida, en unidades de costo
     */
    public void calibrar(double valorInicial) {
        double referencia = Math.max(1.0, valorInicial);
        // Un empeoramiento de la fraccion configurada se acepta con probabilidad un medio.
        this.temperaturaInicial = fraccionTemperaturaInicial * referencia / Math.log(2.0);
        double temperaturaFinal = Math.max(1.0e-9, fraccionTemperaturaFinal * referencia);
        this.razonEnfriamiento = temperaturaFinal / temperaturaInicial;
    }

    /** Temperatura correspondiente a la fraccion de presupuesto ya consumida. */
    public double temperatura(double fraccionConsumida) {
        return temperaturaInicial * Math.pow(razonEnfriamiento, fraccionConsumida);
    }

    /**
     * Decide si la solucion candidata sustituye a la vigente.
     *
     * @param valorCandidato    escalar interno de la solucion candidata
     * @param valorVigente      escalar interno de la solucion vigente
     * @param fraccionConsumida fraccion del presupuesto ya consumida
     * @param aleatorio         generador de la corrida
     */
    public boolean aceptar(double valorCandidato, double valorVigente, double fraccionConsumida,
                           Aleatorio aleatorio) {
        double delta = valorCandidato - valorVigente;
        if (delta <= 0.0) {
            return true;
        }
        double temperatura = temperatura(fraccionConsumida);
        if (temperatura <= 0.0) {
            return false;
        }
        double exponente = -delta / temperatura;
        if (exponente < EXPONENTE_DESPRECIABLE) {
            return false;
        }
        return aleatorio.conProbabilidad(Math.exp(exponente));
    }
}
