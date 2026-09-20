package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.UnidadTransporte;

class PruebaFactibilidadIndependienteTest {

    @Test
    void pasaUnaSecuenciaReducidaConFlotaSuficiente() {
        long ahora = 420L;
        Pedido primero = pedido(1, 30, 14, 2, ahora, 4);
        Pedido segundo = pedido(2, 28, 16, 2, ahora + 5, 4);
        List<UnidadTransporte> unidades = List.of(
                UnidadTransporte.de("TA01", Almacen.crearCentral().nodo()),
                UnidadTransporte.de("TA02", Almacen.crearCentral().nodo()));

        PruebaFactibilidadIndependiente.Resultado resultado =
                PruebaFactibilidadIndependiente.evaluarPedidos(
                        List.of(primero, segundo), unidades, new ParametrosOperacion());

        assertTrue(resultado.pasa());
        assertEquals(0, resultado.individualesImposibles());
        assertEquals(0, resultado.noProgramables());
    }

    @Test
    void detectaPedidoImposibleAunqueNoHayaCongestion() {
        long registro = 420L;
        Pedido pedido = pedido(7, 0, 50, 1, registro, 1);
        List<UnidadTransporte> unidades = List.of(
                UnidadTransporte.de("TB01", Almacen.crearCentral().nodo()));

        PruebaFactibilidadIndependiente.Resultado resultado =
                PruebaFactibilidadIndependiente.evaluarPedidos(
                        List.of(pedido), unidades, new ParametrosOperacion());

        assertFalse(resultado.pasa());
        assertEquals(1, resultado.individualesImposibles());
        assertEquals("IMPOSIBLE_INDIVIDUAL", resultado.diagnosticos().get(0).codigo());
    }

    private static Pedido pedido(int id, int x, int y, int cantidad, long registro, int plazoHoras) {
        return new Pedido(id, "c" + id, Ciudad.nodo(x, y), cantidad, registro, plazoHoras);
    }
}
