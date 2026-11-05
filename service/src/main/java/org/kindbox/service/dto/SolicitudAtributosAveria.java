package org.kindbox.service.dto;

// Cuerpo de PUT /api/parametros/averias; los atributos que no lleguen conservan su valor vigente.
public record SolicitudAtributosAveria(Integer tipo, Integer minutosEnElLugar,
                                       Integer turnosHastaReincorporacion, Integer diasMantenimiento) {
}
