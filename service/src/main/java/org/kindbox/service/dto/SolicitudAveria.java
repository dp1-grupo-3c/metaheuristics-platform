package org.kindbox.service.dto;

/**
 * Cuerpo de {@code POST /api/simulaciones/{id}/averias}: el registro individual de averias
 * del panel lateral del prototipo, que pide placa y tipo.
 *
 * <p>La placa es el codigo TTNN de la respuesta 18 del cuestionario y el tipo es 1, 2 o 3
 * segun la respuesta 3. La averia se aplica en el instante simulado en curso.</p>
 *
 * @param placa codigo TTNN de la unidad averiada
 * @param tipo  tipo de averia: 1 menor, 2 intermedia, 3 mayor
 */
public record SolicitudAveria(String placa, Integer tipo) {
}
