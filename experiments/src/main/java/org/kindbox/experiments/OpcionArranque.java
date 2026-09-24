package org.kindbox.experiments;

import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;

/**
 * Modo de arranque de cada replanificacion en las corridas lanzadas desde la linea de
 * comandos: heuristica constructiva, que es la referencia, o plan vigente de la
 * replanificacion anterior.
 *
 * <p>El apartado 7.3.5 del ISA describe el segundo modo y el apartado 11.4 lo plantea como
 * hipotesis experimental: partir del plan vigente favorece de forma natural la estabilidad
 * entre replanificaciones, porque la busqueda arranca con desviacion nula respecto de la
 * asignacion vigente. Contrastar las dos variantes exige poder cambiar de modo sin recompilar
 * y sin tocar ninguna otra cosa, y por eso el indicador llega por la propiedad de sistema
 * {@code -DarranqueDesdePlanVigente}, activo por defecto y desactivable con {@code =false}, igual que los parametros {@code -Dhgs.*} y
 * {@code -Dalns.*} llegan a {@code FabricaAlgoritmos}. Un valor que no sea {@code true} ni
 * {@code false} detiene el ejecutable.</p>
 *
 * <p>Solo lo aprovecha el algoritmo que lo admite, hoy la busqueda adaptativa de vecindad
 * amplia; con la busqueda genetica hibrida el ejecutable avisa de que el indicador no tiene
 * efecto, en lugar de dejar creer que la corrida mide algo que no mide.</p>
 */
final class OpcionArranque {

    private OpcionArranque() {
    }

    /** La configuracion con el indicador que trae la propiedad de sistema. */
    static ConfiguracionEscenario aplicar(ConfiguracionEscenario base) {
        return base.conArranqueDesdePlanVigente(
                ConfiguracionEscenario.leerArranqueDesdePlanVigente(System.getProperties()));
    }

    /** Linea que se imprime al arrancar, con el aviso si el algoritmo no aprovecha el indicador. */
    static String describir(ConfiguracionEscenario configuracion, Algoritmo algoritmo) {
        if (!configuracion.arranqueDesdePlanVigente()) {
            return "Arranque: heuristica constructiva comun (pedido con -D"
                    + ConfiguracionEscenario.PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE + "=false)";
        }
        if (!algoritmo.admiteArranqueDesdePlanVigente()) {
            return "Arranque: heuristica constructiva comun"
                    + System.lineSeparator()
                    + "AVISO: " + algoritmo.nombre() + " no admite el arranque desde el plan vigente"
                    + " (apartado 7.3.5 del ISA); el indicador no tiene efecto en esta corrida.";
        }
        return "Arranque: desde el plan vigente de la replanificacion anterior"
                + " (apartados 7.3.5 y 11.4 del ISA)";
    }
}
