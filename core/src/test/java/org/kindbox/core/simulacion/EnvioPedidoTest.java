package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;

class EnvioPedidoTest {

    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 9, 1, 1, 0);

    private static VistaUnidad unidad(String codigo, List<VistaUnidad.PedidoABordo> aBordo) {
        TipoUnidad tipo = TipoUnidad.porCodigo(codigo);
        return new VistaUnidad(codigo, tipo, EstadoUnidad.EN_RUTA, 0, 0, 0, tipo.capacidad(), ColorSemaforo.VACIO,
                new int[0], new int[0], -1, -1, 0L, aBordo, 0, -1L, -1L, -1L,
                VistaUnidad.Fase.LLEGANDO, 30L, 90L, 1, 30L);
    }

    // Fotografia del minuto 60, que es la 01:00 del calendario.
    private static InstantaneaSimulacion foto(List<VistaUnidad> unidades) {
        ParametrosOperacion.Instantanea p = new ParametrosOperacion().instantanea();
        MetricasSimulacion metricas = new MetricasSimulacion(0, 0, 0, 0, 0, 0, 0.0,
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(), 0L, 0L);
        return new InstantaneaSimulacion(60L, FECHA, EstadoCorrida.EN_CURSO, 0L, unidades, List.of(), List.of(), 0,
                metricas, IndicadorOcupacion.deFlota(unidades, p), IndicadorOcupacion.deAlmacenes(List.of(), p));
    }

    @Test
    @DisplayName("Los envios de un pedido se agrupan, se ordenan por llegada y numeran sus paquetes")
    void agrupaOrdenaYNumera() {
        // Pedido 7: 10 paquetes, 2 ya entregados; el auto lleva 5 y la moto 3 tras recargar.
        InstantaneaSimulacion foto = foto(List.of(
                unidad("TA01", List.of(new VistaUnidad.PedidoABordo(7, 5, 10, 0, 8, 1, 30L, 120L),
                        new VistaUnidad.PedidoABordo(9, 2, 2, 0, 2, 1, 30L, 150L))),
                unidad("TM01", List.of(new VistaUnidad.PedidoABordo(7, 3, 10, 1, 8, 2, 80L, 90L)))));

        Map<Integer, List<EnvioPedido>> envios = EnvioPedido.porPedido(foto);
        assertEquals(List.of(7, 9), List.copyOf(envios.keySet()));

        List<EnvioPedido> siete = envios.get(7);
        assertEquals(List.of("TM01", "TA01"), siete.stream().map(EnvioPedido::codigoUnidad).toList(),
                "primero el que llega antes");
        EnvioPedido moto = siete.get(0);
        assertEquals(List.of(3, 5), List.of(moto.primerPaquete(), moto.ultimoPaquete()), "sigue a los 2 entregados");
        assertFalse(moto.cargado(), "la moto lo recoge en su recarga");
        assertEquals(2, moto.idAlmacenCarga());
        assertEquals(LocalDateTime.of(2026, 9, 1, 1, 20), moto.fechaHoraSalida());
        assertEquals(LocalDateTime.of(2026, 9, 1, 1, 30), moto.fechaHoraLlegada());
        EnvioPedido auto = siete.get(1);
        assertEquals(List.of(6, 10), List.of(auto.primerPaquete(), auto.ultimoPaquete()));
        assertTrue(auto.cargado());
        assertEquals(TipoUnidad.AUTO, auto.tipoUnidad());

        assertEquals(List.of(1, 2), List.of(envios.get(9).get(0).primerPaquete(), envios.get(9).get(0).ultimoPaquete()));
    }

    @Test
    @DisplayName("Una salida desconocida no tiene fecha y una fotografia sin rutas no tiene envios")
    void casosBorde() {
        InstantaneaSimulacion foto = foto(List.of(
                unidad("TB01", List.of(new VistaUnidad.PedidoABordo(4, 1, 1, 0, 1, -1, -1L, 75L)))));
        EnvioPedido envio = EnvioPedido.porPedido(foto).get(4).get(0);
        assertNull(envio.fechaHoraSalida());
        assertEquals(-1, envio.idAlmacenCarga());
        assertEquals(LocalDateTime.of(2026, 9, 1, 1, 15), envio.fechaHoraLlegada());

        assertTrue(EnvioPedido.porPedido(foto(List.of(unidad("TA01", List.of())))).isEmpty());
    }
}
