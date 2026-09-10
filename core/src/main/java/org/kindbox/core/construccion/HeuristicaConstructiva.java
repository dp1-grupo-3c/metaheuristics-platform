package org.kindbox.core.construccion;

import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.util.Aleatorio;

/**
 * Heuristica constructiva comun a los dos algoritmos, conforme al apartado 10 del ISA.
 *
 * <p>Cumple un papel distinto en cada uno pero con el mismo proposito de fondo. En la
 * busqueda genetica hibrida produce el individuo de elite de la poblacion inicial del
 * apartado 6.3.1; en la busqueda adaptativa de vecindad amplia es la solucion vigente
 * inicial sobre la que opera el primer ciclo de destruccion y reconstruccion del apartado
 * 7.3.1. En ambos casos permite que la busqueda arranque desde una solucion ya cercana a
 * la factibilidad y de costo razonable, en lugar de gastar una fraccion no despreciable
 * del presupuesto en alcanzar por primera vez una region factible.</p>
 *
 * <p>Que la heuristica sea una sola implementacion consumida por ambos algoritmos es parte
 * de la comunidad que sostiene la validez del experimento del apartado 12.</p>
 */
public interface HeuristicaConstructiva {

    /** Nombre de la heuristica, tal como aparece en los reportes de la experimentacion. */
    String nombre();

    /**
     * Construye un plan para la instancia dada.
     *
     * @param instancia   fotografia estatica del problema
     * @param programador decodificador de rutas, cuyo estado de inventarios la heuristica
     *                    reinicia por su cuenta antes de empezar
     * @param aleatorio   generador de la corrida, para las variantes con desempate aleatorio
     * @return plan factible o, si no lo consigue, el mejor plan hallado con los pedidos que
     *         no pudo colocar en el banco de no atendidos
     */
    Solucion construir(InstanciaPlanificacion instancia, ProgramadorRuta programador, Aleatorio aleatorio);
}
