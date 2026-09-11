package org.kindbox.core.simulacion;

import org.kindbox.core.modelo.Almacen;
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
     * la capacidad de un almacen intermedio, de modo que un solo juego de parametros gobierne
     * ambos usos: con los valores por defecto, 500 y 250 sobre una capacidad de 1000, el corte
     * verde queda en la mitad del plazo y el ambar en la cuarta parte.
     *
     * <p>Los dos cortes se acotan a {@code [0,1]} y el ambar nunca supera al verde, de modo
     * que un juego de umbrales por encima de la capacidad deja el corte verde en el plazo
     * entero en lugar de invertir el orden de los colores.</p>
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
        double capacidad = Almacen.CAPACIDAD_INTERMEDIO;
        double corteVerde = Math.min(1.0, parametros.umbralSemaforoVerde() / capacidad);
        double corteAmbar = Math.min(corteVerde, parametros.umbralSemaforoAmbar() / capacidad);
        if (fraccion >= corteVerde) {
            return VERDE;
        }
        return fraccion >= corteAmbar ? AMBAR : ROJO;
    }
}
