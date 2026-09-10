package org.kindbox.core.metaheuristica;

import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Registro del perfil de convergencia de una ejecucion, exigido por el apartado 12.1
 * del ISA.
 *
 * <p>Se toma una medicion al 1, 2, 5, 10, 15, 20, 30, 50, 75 y 100 por ciento del
 * presupuesto asignado. Esa distribucion concentra las mediciones en el tramo inicial,
 * que es el decisivo bajo un presupuesto de segundos.</p>
 *
 * <p>Con esas mediciones se calcula la integral primal, que es la metrica principal de
 * comparacion entre los dos algoritmos.</p>
 */
public final class PerfilConvergencia {

    /** Fracciones del presupuesto en las que se toma una medicion. */
    public static final double[] HITOS = {0.01, 0.02, 0.05, 0.10, 0.15, 0.20, 0.30, 0.50, 0.75, 1.00};

    private final List<Medicion> mediciones = new ArrayList<>(HITOS.length);

    /**
     * Medicion tomada en un hito del presupuesto.
     *
     * @param fraccionPresupuesto fraccion del presupuesto consumida
     * @param milisegundos        milisegundos de reloj de pared transcurridos
     * @param valor               mejor valor objetivo conocido en ese instante
     * @param iteraciones         iteraciones o generaciones completadas
     */
    public record Medicion(double fraccionPresupuesto, long milisegundos, ValorObjetivo valor, long iteraciones) {
    }

    /** Anota una medicion. */
    public void registrar(double fraccionPresupuesto, long milisegundos, ValorObjetivo valor, long iteraciones) {
        mediciones.add(new Medicion(fraccionPresupuesto, milisegundos, valor, iteraciones));
    }

    /** Mediciones tomadas, en orden cronologico. */
    public List<Medicion> mediciones() {
        return List.copyOf(mediciones);
    }

    /** Indica si aun no se registro ninguna medicion. */
    public boolean vacio() {
        return mediciones.isEmpty();
    }

    /**
     * Integral primal normalizada respecto de una cota de referencia, segun Berthold (2013).
     *
     * <p>Equivale al area bajo la curva de convergencia dividida entre el presupuesto, es
     * decir, a la brecha esperada si la ejecucion se interrumpiera en un instante
     * cualquiera. Un valor menor indica mejor comportamiento anytime.</p>
     *
     * @param referencia mejor costo conocido para la instancia, con H igual a cero
     * @return integral primal en el intervalo {@code [0,1]}
     */
    public double integralPrimal(double referencia) {
        if (mediciones.isEmpty()) {
            return 1.0;
        }
        double area = 0.0;
        double fraccionPrevia = 0.0;
        double brechaPrevia = 1.0;
        for (Medicion m : mediciones) {
            double brecha = brecha(m.valor(), referencia);
            // Regla del trapecio sobre el tramo transcurrido desde el hito anterior.
            area += (m.fraccionPresupuesto() - fraccionPrevia) * (brecha + brechaPrevia) / 2.0;
            fraccionPrevia = m.fraccionPresupuesto();
            brechaPrevia = brecha;
        }
        if (fraccionPrevia < 1.0) {
            area += (1.0 - fraccionPrevia) * brechaPrevia;
        }
        return area;
    }

    /**
     * Integral primal sobre el nivel 1 del objetivo.
     *
     * <p>La integral primal del apartado 12.1 se define sobre el costo y solo tiene sentido
     * entre soluciones con H igual a cero. En el escenario de colapso, y en general en todo
     * regimen saturado, ninguna solucion alcanza H igual a cero y aquella metrica devuelve la
     * brecha maxima en todos los hitos, con lo que deja de discriminar justo donde la
     * comparacion importa.</p>
     *
     * <p>Esta metrica cubre ese tramo. Mide el area bajo la curva de H normalizada por el
     * valor de partida, de modo que 0 significa haber vaciado el banco de inmediato y 1 no
     * haber reducido H en ningun momento. No sustituye a la integral primal del ISA: la
     * complementa, y ambas deben reportarse indicando cual gobierna en cada instancia.</p>
     *
     * @param hReferencia H de la solucion de partida, tipicamente la de la heuristica
     *                    constructiva; si es cero la metrica devuelve 0
     */
    public double integralPrimalPedidosNoAtendidos(int hReferencia) {
        if (mediciones.isEmpty()) {
            return 1.0;
        }
        if (hReferencia <= 0) {
            return 0.0;
        }
        double area = 0.0;
        double fraccionPrevia = 0.0;
        double previa = 1.0;
        for (Medicion m : mediciones) {
            double actual = m.valor() == null
                    ? 1.0
                    : Math.max(0.0, Math.min(1.0, (double) m.valor().h() / hReferencia));
            area += (m.fraccionPresupuesto() - fraccionPrevia) * (actual + previa) / 2.0;
            fraccionPrevia = m.fraccionPresupuesto();
            previa = actual;
        }
        if (fraccionPrevia < 1.0) {
            area += (1.0 - fraccionPrevia) * previa;
        }
        return area;
    }

    /**
     * Indica si la sucesion de hitos respeta la monotonia exigida por el apartado 12.4, es
     * decir si la mejor solucion conocida no empeora nunca en el orden lexicografico.
     */
    public boolean monotona() {
        ValorObjetivo previo = null;
        for (Medicion m : mediciones) {
            if (m.valor() == null) {
                continue;
            }
            if (previo != null && previo.mejorQue(m.valor())) {
                return false;
            }
            previo = m.valor();
        }
        return true;
    }

    /**
     * Brecha relativa de un valor respecto de la referencia. Una solucion con H mayor
     * que cero recibe la brecha maxima, porque el nivel 1 domina al nivel 2.
     */
    public static double brecha(ValorObjetivo valor, double referencia) {
        if (valor == null || !valor.sinPedidosPendientes()) {
            return 1.0;
        }
        if (referencia <= 0.0) {
            return valor.costo() <= 0.0 ? 0.0 : 1.0;
        }
        double brecha = (valor.costo() - referencia) / referencia;
        return Math.max(0.0, Math.min(1.0, brecha));
    }
}
