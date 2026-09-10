package org.kindbox.service.dto;

/**
 * Cuerpo de {@code PUT /api/parametros/velocidad}.
 *
 * <p>La respuesta 6 del cuestionario exige que las velocidades puedan cambiarse por
 * parametro mientras el software esta funcionando; la respuesta 15 precisa que el cambio es
 * <b>por tipo de unidad</b> y no por unidad individual, y la respuesta 16 fija que el nuevo
 * valor se aplica a partir de la siguiente iteracion de planificacion. De ahi que el campo
 * sea un tipo y nunca una placa.</p>
 *
 * @param tipo       tipo de unidad: AUTO, MOTO o BICICLETA, o su prefijo TA, TM o TB
 * @param kmPorHora  nueva velocidad promedio, en kilometros por hora
 */
public record SolicitudVelocidad(String tipo, Double kmPorHora) {
}
