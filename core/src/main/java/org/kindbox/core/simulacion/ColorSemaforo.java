package org.kindbox.core.simulacion;

import org.kindbox.core.modelo.ParametrosOperacion;

/**
 * Colores del semaforo exigido por el requisito no funcional (d) del enunciado, cuyos
 * rangos deben ser parametros configurables.
 *
 * <p>Se aplica al nivel de ocupacion de los almacenes intermedios, que es donde el
 * prototipo del visualizador lo situa, y a la holgura remanente de un pedido, que es la
 * lectura util para el operador: verde cuando sobra tiempo, ambar cuando aprieta y rojo
 * cuando esta a punto de incumplirse.</p>
 */
public enum ColorSemaforo {

    VERDE,
    AMBAR,
    ROJO;

    /**
     * Color del inventario de un almacen segun los umbrales vigentes.
     *
     * @param disponible unidades del producto P disponibles
     */
    public static ColorSemaforo deInventario(int disponible, ParametrosOperacion.Instantanea parametros) {
        if (disponible >= parametros.umbralSemaforoVerde()) {
            return VERDE;
        }
        return disponible >= parametros.umbralSemaforoAmbar() ? AMBAR : ROJO;
    }

    /**
     * Color de la holgura de un pedido, expresada como fraccion del plazo comprometido que
     * aun queda. Se usan las mismas proporciones que los umbrales de inventario, referidas a
     * su capacidad maxima, de modo que un solo juego de parametros gobierne ambos usos.
     *
     * @param holguraMinutos minutos que faltan hasta el instante limite; puede ser negativo
     * @param plazoMinutos   plazo total comprometido del pedido
     */
    public static ColorSemaforo deHolgura(long holguraMinutos, long plazoMinutos,
                                          ParametrosOperacion.Instantanea parametros) {
        if (holguraMinutos <= 0 || plazoMinutos <= 0) {
            return ROJO;
        }
        double fraccion = (double) holguraMinutos / plazoMinutos;
        double capacidad = Math.max(1.0, parametros.umbralSemaforoVerde()
                / Math.max(1.0, (double) org.kindbox.core.modelo.Almacen.CAPACIDAD_INTERMEDIO));
        double corteVerde = Math.min(1.0, capacidad);
        double corteAmbar = corteVerde * parametros.umbralSemaforoAmbar()
                / Math.max(1.0, (double) parametros.umbralSemaforoVerde());
        if (fraccion >= corteVerde) {
            return VERDE;
        }
        return fraccion >= corteAmbar ? AMBAR : ROJO;
    }
}
