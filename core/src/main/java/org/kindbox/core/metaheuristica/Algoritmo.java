package org.kindbox.core.metaheuristica;

import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;

/**
 * Contrato comun de los dos algoritmos seleccionados en el ISA: la busqueda genetica
 * hibrida del apartado 6 y la busqueda adaptativa de vecindad amplia del apartado 7.
 *
 * <p>Ambos resuelven de forma completa la fotografia estatica que reciben, dentro del
 * presupuesto de reloj de pared indicado, y deben devolver en todo momento la mejor
 * solucion factible conocida. La interrupcion por agotamiento del presupuesto no es una
 * caracteristica opcional sino una condicion de funcionamiento.</p>
 *
 * <p>Ambas implementaciones comparten de forma deliberada el modelo del problema, la
 * funcion objetivo, el verificador de factibilidad, la evaluacion en tiempo constante de
 * secuencias y la heuristica constructiva, conforme al apartado 10. Esa comunidad es la
 * condicion de validez del experimento del apartado 12.</p>
 */
public interface Algoritmo {

    /** Nombre del algoritmo, tal como aparece en los reportes de la experimentacion. */
    String nombre();

    /**
     * Resuelve la instancia dentro del presupuesto indicado.
     *
     * @param instancia   fotografia estatica del problema
     * @param presupuesto control de tiempo, ya arrancado por el invocante
     * @return mejor solucion conocida junto con el perfil de convergencia
     */
    ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto);

    /**
     * Resuelve la instancia con una semilla fijada por el invocante en lugar de la semilla
     * con que se construyo el algoritmo.
     *
     * <p>Existe para el motor de simulacion: cada replanificacion recibe una semilla propia,
     * derivada de la semilla de la configuracion y del numero de replanificacion, de modo que
     * las iteraciones sucesivas no repitan la misma secuencia aleatoria y la corrida entera
     * siga siendo reproducible a partir de una sola semilla, como pide el apartado 10 del
     * ISA. La implementacion por defecto ignora la semilla y delega en
     * {@link #resolver(InstanciaPlanificacion, PresupuestoComputo)}, para no obligar a
     * reescribir a quien ya implementa la interfaz; los dos algoritmos del proyecto la
     * implementan de verdad.</p>
     *
     * @param instancia   fotografia estatica del problema
     * @param presupuesto control de tiempo, ya arrancado por el invocante
     * @param semilla     semilla del generador de esta ejecucion
     * @return mejor solucion conocida junto con el perfil de convergencia
     */
    default ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                            long semilla) {
        return resolver(instancia, presupuesto);
    }

    /**
     * Indica si el algoritmo sabe usar el plan vigente como solucion de partida.
     * ALNS inicia su trayectoria desde el plan reparado; HGS conserva un respaldo factible
     * y lo incorpora como individuo de su poblacion. Un envoltorio debe reenviar la capacidad.
     */
    default boolean admiteArranqueDesdePlanVigente() {
        return false;
    }

    /**
     * Resuelve la instancia arrancando desde el plan vigente de la ejecucion anterior, ya
     * adaptado a la fotografia, en lugar de construir la solucion de partida desde cero. Es la
     * capacidad sobre la que se sostiene la hipotesis experimental de los apartados 7.3.5 y
     * 11.4 del ISA: partir del plan vigente favorece de forma natural la estabilidad entre
     * replanificaciones.
     *
     * <p>La implementacion por defecto ignora el plan y delega en
     * {@link #resolver(InstanciaPlanificacion, PresupuestoComputo, long)}, que es lo que
     * corresponde a un algoritmo que no admite este arranque; la busqueda adaptativa de
     * vecindad amplia la implementa de verdad.</p>
     *
     * @param instancia   fotografia estatica del problema
     * @param presupuesto control de tiempo, ya arrancado por el invocante
     * @param semilla     semilla del generador de esta ejecucion
     * @param planVigente plan de partida: solo pedidos que siguen pendientes y unidades que
     *                    siguen disponibles en la fotografia; con {@code null} el arranque es
     *                    el ordinario
     * @return mejor solucion conocida junto con el perfil de convergencia
     */
    default ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                                 long semilla, Solucion planVigente) {
        return resolver(instancia, presupuesto, semilla);
    }
}
