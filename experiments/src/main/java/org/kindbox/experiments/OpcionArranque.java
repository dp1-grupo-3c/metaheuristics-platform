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
 * {@code -DarranqueDesdePlanVigente=true}, igual que los parametros {@code -Dhgs.*} y
 * {@code -Dalns.*} llegan a {@code FabricaAlgoritmos}. Un valor que no sea {@code true} ni
 * {@code false} detiene el ejecutable.</p>
 *
 * <p>ALNS y HGS admiten el plan vigente. El indicador permite comparar ese arranque,
 * activo por defecto, contra la construccion desde cero con {@code false}.</p>
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
            return "Arranque: heuristica constructiva comun (-D"
                    + ConfiguracionEscenario.PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE
                    + "=true para partir del plan vigente)";
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
