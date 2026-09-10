package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.util.Aleatorio;

/**
 * Operador de destruccion de la busqueda adaptativa de vecindad amplia, apartado 7.3.2 del
 * ISA.
 *
 * <p>Retira pedidos de las rutas y los deja en el banco de no asignados. La destruccion
 * nunca puede producir un estado infactible en sentido duro, porque quitar visitas solo
 * relaja plazos, carga y cierre de turno; su unico efecto sobre el objetivo es aumentar la
 * H del nivel 1, que la reconstruccion vuelve a bajar.</p>
 *
 * <p>El conjunto de operadores sigue las guias de Voigt (2025): dos de diversificacion pura
 * (aleatorio y ruta completa), dos guiados por el objetivo (peor y criticidad) y dos
 * estructurales (afinidad de Shaw y cadenas adyacentes). Segun el metaanalisis de Turkes,
 * Sorensen y Hvattum (2021) el rendimiento del metodo proviene de este conjunto y no de la
 * capa adaptativa, de modo que aqui es donde el diseno importa.</p>
 *
 * <p>Las implementaciones guardan arreglos de trabajo como campos de instancia y por tanto
 * no son seguras para uso concurrente: cada corrida usa su propio juego de operadores.</p>
 */
public interface OperadorDestruccion {

    /** Nombre del operador, tal como aparece en los reportes de la experimentacion. */
    String nombre();

    /**
     * Retira pedidos del estado y los deja en el banco.
     *
     * @param estado    representacion de trabajo, que se modifica en el lugar
     * @param grado     numero de pedidos que se pretende retirar
     * @param aleatorio generador de la corrida
     * @return numero de pedidos efectivamente retirados
     */
    int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio);
}
