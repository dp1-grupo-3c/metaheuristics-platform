package org.kindbox.core.metaheuristica;

import org.kindbox.core.problema.InstanciaPlanificacion;

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
}
