package org.kindbox.core.modelo;

/**
 * Pedido de la respuesta 8 del cuestionario. El registro del archivo mensual de ventas
 * tiene el formato {@code ##d##h##m:posX,posY,cIdCliente,qq,hl}.
 *
 * <p>El plazo se computa desde el instante de registro. El tiempo de entrega de una hora
 * dentro de las instalaciones del cliente no forma parte del plazo (respuesta 11), de modo
 * que la restriccion dura es que la unidad <em>llegue</em> al destino antes del limite.</p>
 *
 * @param id             identificador interno correlativo
 * @param idCliente      identificador del cliente tal como aparece en el archivo (cNNNN)
 * @param nodoDestino    nodo de la reticula donde se entrega
 * @param cantidad       unidades del producto P solicitadas
 * @param minutoRegistro instante de llegada del pedido, en minutos desde el inicio del escenario
 * @param plazoHoras     horas limite de entrega: 36 para el plazo estandar, o 4, 8, 12 y 18 priorizados
 */
public record Pedido(
        int id,
        String idCliente,
        int nodoDestino,
        int cantidad,
        long minutoRegistro,
        int plazoHoras) {

    /** Plazo estandar comprometido por la empresa. */
    public static final int PLAZO_ESTANDAR_HORAS = 36;
    /** Plazos priorizados que ofrece la empresa. */
    public static final int[] PLAZOS_PRIORIZADOS_HORAS = {4, 8, 12, 18};

    public Pedido {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("Cantidad de producto P no positiva en el pedido " + id);
        }
        if (plazoHoras <= 0) {
            throw new IllegalArgumentException("Plazo no positivo en el pedido " + id);
        }
    }

    /** Instante limite de llegada, en minutos desde el inicio del escenario. */
    public long minutoLimite() {
        return minutoRegistro + plazoHoras * 60L;
    }

    /** Holgura del pedido respecto de un instante dado, en minutos. Negativa si ya vencio. */
    public long holgura(long minutoActual) {
        return minutoLimite() - minutoActual;
    }

    /** Indica si el pedido usa uno de los plazos priorizados. */
    public boolean esPriorizado() {
        return plazoHoras != PLAZO_ESTANDAR_HORAS;
    }

    /** Coordenada X del destino. */
    public int x() {
        return Ciudad.x(nodoDestino);
    }

    /** Coordenada Y del destino. */
    public int y() {
        return Ciudad.y(nodoDestino);
    }
}
