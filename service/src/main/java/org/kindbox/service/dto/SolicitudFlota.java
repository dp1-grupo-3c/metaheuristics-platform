package org.kindbox.service.dto;

import java.util.Map;

// Cuerpo de PUT /api/parametros/flota: cantidad y velocidad por tipo, ambas opcionales por tipo.
public record SolicitudFlota(Map<String, UnidadesTipo> unidades) {

    // Number para poder rechazar una cantidad con decimales en lugar de truncarla.
    public record UnidadesTipo(Number cantidad, Number kmPorHora) {
    }
}
