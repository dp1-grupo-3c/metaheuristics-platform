package org.kindbox.service.dto;

import java.util.List;

/**
 * Pagina de la tabla de pedidos, con el buscador y la paginacion que pide el panel lateral
 * del prototipo.
 *
 * <p>El escenario de septiembre de 2026 trae 5 892 pedidos, de modo que la tabla no puede
 * viajar entera en cada refresco: el recorte se hace en el servidor y el visualizador solo
 * pide la pagina que esta mostrando.</p>
 *
 * @param filas        pedidos de esta pagina
 * @param pagina       numero de pagina, empezando en cero
 * @param tamano       filas por pagina
 * @param totalFilas   filas que cumplen el filtro
 * @param totalPaginas paginas que ocupan esas filas
 * @param busqueda     texto buscado, o cadena vacia si no se filtro
 * @param soloActivos  si se excluyeron los pedidos ya entregados
 */
public record PaginaPedidos(
        List<FilaPedido> filas,
        int pagina,
        int tamano,
        int totalFilas,
        int totalPaginas,
        String busqueda,
        boolean soloActivos) {

    public PaginaPedidos {
        filas = List.copyOf(filas);
    }
}
