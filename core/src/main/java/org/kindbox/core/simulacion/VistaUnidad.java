package org.kindbox.core.simulacion;

import java.util.List;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Estado de una unidad tal como lo consume el visualizador.
 *
 * <p>El prototipo dibuja el trazo ya recorrido con linea continua y el pendiente con linea
 * discontinua, de ahi que la vista lleve ambos caminos por separado, y muestra una tarjeta
 * emergente con ubicacion, destino, tiempo estimado de llegada y carga.</p>
 *
 * @param codigo          codigo TTNN
 * @param tipo            tipo de unidad
 * @param estado          situacion operativa
 * @param x               coordenada X actual
 * @param y               coordenada Y actual
 * @param cargaABordo     paquetes del producto P a bordo
 * @param capacidad       capacidad nominal
 * @param caminoRecorrido nodos ya recorridos de la ruta vigente, como pares x,y consecutivos
 * @param caminoPendiente nodos por recorrer, como pares x,y consecutivos
 * @param destinoX        coordenada X de la proxima parada, o -1 si no tiene ruta
 * @param destinoY        coordenada Y de la proxima parada, o -1 si no tiene ruta
 * @param minutosHastaDestino tiempo estimado de llegada a la proxima parada
 * @param pedidosABordo   pedidos que transporta, con la cantidad que lleva y la total
 * @param tipoAveria      codigo de la averia que la inmoviliza, o 0 si esta operativa
 */
public record VistaUnidad(
        String codigo,
        TipoUnidad tipo,
        EstadoUnidad estado,
        int x,
        int y,
        int cargaABordo,
        int capacidad,
        int[] caminoRecorrido,
        int[] caminoPendiente,
        int destinoX,
        int destinoY,
        long minutosHastaDestino,
        List<PedidoABordo> pedidosABordo,
        int tipoAveria) {

    /**
     * Renglon de la tarjeta emergente de una unidad.
     *
     * @param idPedido        identificador del pedido
     * @param enLaUnidad      paquetes de ese pedido que van en esta unidad
     * @param totalDelPedido  paquetes que el pedido pidio en total
     */
    public record PedidoABordo(int idPedido, int enLaUnidad, int totalDelPedido) {
    }

    public VistaUnidad {
        caminoRecorrido = caminoRecorrido == null ? new int[0] : caminoRecorrido.clone();
        caminoPendiente = caminoPendiente == null ? new int[0] : caminoPendiente.clone();
        pedidosABordo = List.copyOf(pedidosABordo);
    }

    @Override
    public int[] caminoRecorrido() {
        return caminoRecorrido.clone();
    }

    @Override
    public int[] caminoPendiente() {
        return caminoPendiente.clone();
    }

    /** Indica si la unidad esta inmovilizada por una averia. */
    public boolean averiada() {
        return tipoAveria > 0;
    }
}
