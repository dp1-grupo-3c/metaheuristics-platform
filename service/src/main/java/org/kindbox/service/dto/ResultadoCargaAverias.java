package org.kindbox.service.dto;

import java.util.List;

/**
 * Respuesta de la carga masiva de averias por archivo.
 *
 * <p>Las averias del archivo llevan instante, de modo que unas se aplican de inmediato
 * (las que caen en un minuto ya recorrido o en el actual) y otras quedan programadas para
 * cuando el reloj simulado las alcance. La respuesta distingue ambos grupos para que el
 * operador sepa que va a pasar y cuando.</p>
 *
 * @param leidas      registros validos que traia el archivo
 * @param aplicadas   averias que se registraron de inmediato
 * @param programadas averias que esperan a que el reloj simulado alcance su instante
 * @param avisos      lineas descartadas y por que motivo
 */
public record ResultadoCargaAverias(
        int leidas,
        int aplicadas,
        int programadas,
        List<String> avisos) {

    public ResultadoCargaAverias {
        avisos = List.copyOf(avisos);
    }
}
