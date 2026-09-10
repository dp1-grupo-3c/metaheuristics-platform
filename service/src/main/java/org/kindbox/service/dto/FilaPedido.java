package org.kindbox.service.dto;

import java.time.LocalDateTime;
import org.kindbox.core.simulacion.ColorSemaforo;

/**
 * Renglon de la tabla de pedidos del panel lateral del prototipo, que muestra identificador,
 * numero de paquetes, direccion en coordenadas y plazo.
 *
 * <p>Se anaden la holgura y su color de semaforo porque son la lectura util para el
 * operador: el requisito no funcional (d) pide rangos configurables, y aplicados a la
 * holgura remanente indican de un vistazo que pedidos aprietan.</p>
 *
 * @param id             identificador del pedido
 * @param cliente        identificador del cliente, con prefijo c
 * @param paquetes       unidades del producto P solicitadas
 * @param pendientes     unidades que aun faltan por entregar
 * @param x              coordenada X del destino
 * @param y              coordenada Y del destino
 * @param plazoHoras     plazo comprometido: 36 estandar, o 18, 12, 8 y 4 priorizados
 * @param registro       instante de llegada del pedido en el calendario real
 * @param limite         instante limite de llegada de la unidad
 * @param holguraMinutos minutos que faltan hasta el limite; negativo si ya vencio
 * @param entregado      indica si el pedido se completo
 * @param semaforo       color de la holgura segun los umbrales vigentes
 */
public record FilaPedido(
        int id,
        String cliente,
        int paquetes,
        int pendientes,
        int x,
        int y,
        int plazoHoras,
        LocalDateTime registro,
        LocalDateTime limite,
        long holguraMinutos,
        boolean entregado,
        ColorSemaforo semaforo) {

    /** Direccion tal como la pinta el prototipo, en coordenadas de la reticula. */
    public String direccion() {
        return "(" + x + "," + y + ")";
    }
}
