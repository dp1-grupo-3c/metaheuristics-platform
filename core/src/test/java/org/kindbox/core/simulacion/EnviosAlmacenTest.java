package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;

class EnviosAlmacenTest {

    private static final VistaAlmacen NOR_OESTE = new VistaAlmacen(1, "Nor-Oeste", 12, 38, false, 900, 1000,
            ColorSemaforo.VERDE);

    private static VistaUnidad unidad(String codigo, VistaUnidad.PedidoABordo... aBordo) {
        TipoUnidad tipo = TipoUnidad.porCodigo(codigo);
        return new VistaUnidad(codigo, tipo, EstadoUnidad.EN_RUTA, 0, 0, 0, tipo.capacidad(), ColorSemaforo.VACIO,
                new int[0], new int[0], -1, -1, 0L, List.of(aBordo), 0, -1L, -1L, -1L,
                VistaUnidad.Fase.LLEGANDO, 0L, 0L, 1, 0L);
    }

    private static VistaUnidad.PedidoABordo parte(int pedido, int cantidad, int viaje, int almacen, long salida,
                                                  long llegada) {
        return new VistaUnidad.PedidoABordo(pedido, cantidad, cantidad, viaje, cantidad, almacen, salida, llegada, 1);
    }

    // Fotografia del minuto 60.
    private static InstantaneaSimulacion foto(List<VistaUnidad> unidades) {
        ParametrosOperacion.Instantanea p = new ParametrosOperacion().instantanea();
        MetricasSimulacion metricas = new MetricasSimulacion(0, 0, 0, 0, 0, 0, 0.0,
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(), 0L, 0L);
        return new InstantaneaSimulacion(60L, LocalDateTime.of(2026, 9, 1, 1, 0), EstadoCorrida.EN_CURSO, 0L,
                unidades, List.of(NOR_OESTE), List.of(), 0, metricas,
                IndicadorOcupacion.deFlota(unidades, p), IndicadorOcupacion.deAlmacenes(List.of(NOR_OESTE), p));
    }

    private static EntregaRegistrada entrega(long minuto, int pedido, int almacen, int cantidad) {
        return new EntregaRegistrada(minuto, LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(minuto), pedido, "TA09",
                almacen, cantidad);
    }

    @Test
    @DisplayName("El almacen separa lo que va en camino, lo que sale en la ventana y lo entregado en ella")
    void clasificaLosEnviosDelAlmacen() {
        InstantaneaSimulacion foto = foto(List.of(
                unidad("TA01", parte(7, 5, 0, 1, 30L, 120L), parte(8, 4, 1, 2, 150L, 200L)),
                unidad("TM01", parte(9, 3, 0, 1, 90L, 130L)),
                unidad("TB01", parte(10, 2, 1, 1, 400L, 450L))));
        List<EntregaRegistrada> entregas = List.of(entrega(-300L, 1, 1, 9), entrega(10L, 2, 1, 2),
                entrega(50L, 3, 1, 3), entrega(50L, 4, 2, 6), entrega(61L, 5, 1, 1));

        EnviosAlmacen cuatro = EnviosAlmacen.de(foto, entregas, NOR_OESTE, 4);
        assertEquals(List.of(7), cuatro.enTransito().stream().map(EnviosAlmacen.EnvioDelAlmacen::idPedido).toList(),
                "solo lo que ya salio de este almacen");
        assertEquals(List.of(9), cuatro.porSalir().stream().map(EnviosAlmacen.EnvioDelAlmacen::idPedido).toList(),
                "la moto aun carga y sale a los 90; la bici sale despues de las 4 horas");
        assertEquals(List.of(2, 3), cuatro.entregados().stream().map(EntregaRegistrada::idPedido).toList(),
                "entregas de este almacen en las ultimas 4 horas, sin las futuras ni las de otro almacen");
        assertEquals(List.of(5, 3, 5), List.of(cuatro.paquetesEnTransito(), cuatro.paquetesPorSalir(),
                cuatro.paquetesEntregados()));
        assertEquals(60L, cuatro.minuto());

        EnviosAlmacen seis = EnviosAlmacen.de(foto, entregas, NOR_OESTE, 6);
        assertEquals(List.of(9, 10), seis.porSalir().stream().map(EnviosAlmacen.EnvioDelAlmacen::idPedido).toList());
        assertEquals(List.of(1, 2, 3), seis.entregados().stream().map(EntregaRegistrada::idPedido).toList());
    }
}
