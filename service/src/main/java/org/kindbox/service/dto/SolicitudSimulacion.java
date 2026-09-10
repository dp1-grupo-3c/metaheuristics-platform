package org.kindbox.service.dto;

import java.time.LocalDate;
import org.kindbox.core.simulacion.TipoEscenario;

/**
 * Cuerpo de {@code POST /api/simulaciones}. Es exactamente lo que ofrece la pantalla de
 * inicio y configuracion del prototipo: escenario, fecha de inicio, fecha de fin y la
 * duracion objetivo de la corrida, mas los parametros del planificador.
 *
 * <p>Todos los campos admiten {@code null}: lo que falte se completa con los valores de
 * {@code application.properties}, de modo que un {@code POST} con cuerpo vacio arranque la
 * simulacion de cinco dias por defecto. La duracion objetivo es la que fija el factor de
 * aceleracion K del apartado 2.3 del ISA, y de el sale el presupuesto por ejecucion del
 * planificador.</p>
 *
 * @param tipo                    escenario a ejecutar; por defecto la simulacion de cinco dias
 * @param primerDia               primer dia del horizonte
 * @param ultimoDia               ultimo dia del horizonte, incluido
 * @param duracionMinutosReales   minutos de reloj real que debe durar la corrida
 * @param saltoMinutos            SA, minutos simulados entre dos ejecuciones del planificador
 * @param minutosEntreFotografias cadencia de las fotografias que recibe el visualizador
 * @param algoritmo               nombre del algoritmo del planificador: HGS o ALNS
 * @param semilla                 semilla del generador, que hace reproducible la corrida
 * @param generarAverias          si el motor genera averias por reglas ademas de las registradas
 */
public record SolicitudSimulacion(
        TipoEscenario tipo,
        LocalDate primerDia,
        LocalDate ultimoDia,
        Integer duracionMinutosReales,
        Integer saltoMinutos,
        Integer minutosEntreFotografias,
        String algoritmo,
        Long semilla,
        Boolean generarAverias) {
}
