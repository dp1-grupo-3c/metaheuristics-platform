package org.kindbox.service.dto;

public record SolicitudOperacion(String zonaHoraria, Integer saltoMinutos, String algoritmo,
                                 Long semilla, Long presupuestoPlanificacionMs) { }
