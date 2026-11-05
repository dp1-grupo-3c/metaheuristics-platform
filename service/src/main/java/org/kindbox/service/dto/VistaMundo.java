package org.kindbox.service.dto;

import java.util.List;
import org.kindbox.core.configuracion.ParametrosMundo;

/**
 * Respuesta de {@code GET} y {@code PUT /api/parametros/mundo}.
 *
 * @param version         version de los parametros, que crece con cada cambio
 * @param mapa            dimensiones de la ciudad
 * @param almacenes       almacenes de la red
 * @param ejecucionActiva tipo de la ejecucion en curso, que impide editar; {@code null} si no hay
 */
public record VistaMundo(long version, ParametrosMundo.Mapa mapa, List<ParametrosMundo.AlmacenParam> almacenes,
                         String ejecucionActiva) {
}
