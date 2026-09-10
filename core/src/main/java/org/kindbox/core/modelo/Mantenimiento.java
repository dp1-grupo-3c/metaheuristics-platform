package org.kindbox.core.modelo;

import java.time.LocalDate;

/**
 * Entrada del plan de mantenimiento preventivo de la respuesta 19 del cuestionario.
 * El registro del archivo tiene el formato {@code aaaammdd:TTNN}.
 *
 * <p>Nota 1 de la respuesta: la unidad programada no esta disponible para la
 * planificacion de rutas desde las 00:00 hasta las 23:59 del dia indicado.
 * Nota 2: si la unidad esta en ruta al iniciar su mantenimiento debe retornar de
 * inmediato a las 00:00, aunque no haya entregado un pedido; la planificacion debe
 * evitar esa situacion.</p>
 *
 * @param fecha        dia calendario del mantenimiento
 * @param codigoUnidad codigo TTNN de la unidad
 */
public record Mantenimiento(LocalDate fecha, String codigoUnidad) {

    /** Tipo de la unidad programada. */
    public TipoUnidad tipoUnidad() {
        return TipoUnidad.porCodigo(codigoUnidad);
    }

    /** Representacion en el formato del archivo, {@code aaaammdd:TTNN}. */
    public String aRegistro() {
        return String.format("%04d%02d%02d:%s",
                fecha.getYear(), fecha.getMonthValue(), fecha.getDayOfMonth(), codigoUnidad);
    }
}
