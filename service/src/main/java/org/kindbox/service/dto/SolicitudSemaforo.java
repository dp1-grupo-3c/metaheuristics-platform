package org.kindbox.service.dto;

/**
 * Cuerpo de {@code PUT /api/parametros/semaforo}.
 *
 * <p>Es el parametro configurable que exige el requisito no funcional (d) del enunciado:
 * los rangos de los colores verde, ambar y rojo del semaforo de almacenes. Los dos umbrales
 * bastan para definir los tres intervalos: verde desde {@code verde} hacia arriba, ambar
 * entre {@code ambar} y {@code verde}, y rojo por debajo de {@code ambar}.</p>
 *
 * @param ambar umbral inferior del intervalo ambar, en unidades del producto P
 * @param verde umbral inferior del intervalo verde, en unidades del producto P
 */
public record SolicitudSemaforo(Integer ambar, Integer verde) {
}
