package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Contabilidad de pedidos y de kilometros del estado del mundo simulado.
 *
 * <p>Cubre dos puntos que el visualizador consume directamente. El primero es el faltante de un
 * pedido, que la tabla del panel lateral muestra en su columna de pendientes: un pedido puede
 * repartirse entre varias unidades, de modo que uno a medias tiene ya una parte entregada, y la
 * cifra debe descontarla. El segundo es la clave del desglose de kilometros, que ahora es el
 * nombre del enumerado, {@code AUTO}, {@code MOTO} y {@code BICICLETA}, el mismo identificador
 * que usa el resto de la API en lugar de la etiqueta de presentacion.</p>
 */
class EstadoSimulacionTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    /** Estado con los pedidos dados, sin bloqueos y con una flota minima en el almacen central. */
    private static EstadoSimulacion estadoCon(List<Pedido> pedidos) {
        List<UnidadTransporte> flota = new ArrayList<>();
        int central = Almacen.crearCentral().nodo();
        for (String codigo : List.of("TA01", "TM01", "TB01")) {
            flota.add(UnidadTransporte.de(codigo, central));
        }
        return new EstadoSimulacion(CalendarioEscenario.desde(DIA), new RegistroBloqueos(List.of()),
                Almacen.todos(), pedidos, flota);
    }

    private static Pedido pedido(int id, int cantidad, long minutoRegistro, int plazoHoras) {
        return new Pedido(id, "c" + (1000 + id), Ciudad.nodo(20, 30), cantidad, minutoRegistro, plazoHoras);
    }

    @Test
    @DisplayName("El faltante de un pedido descuenta las entregas parciales")
    void elFaltanteDescuentaLasEntregasParciales() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(7, 10, 0L, 36)));
        int i = estado.indiceDePedido(7);

        assertEquals(10, estado.noEntregadoDe(i), "antes de registrarse falta el pedido entero");

        estado.registrarPedido(i);
        assertEquals(10, estado.noEntregadoDe(i), "recien registrado falta el pedido entero");

        assertTrue(!estado.entregar(i, 4, 60L), "con cuatro de diez el pedido no queda completo");
        assertEquals(6, estado.noEntregadoDe(i), "faltan seis tras la primera entrega");
        assertEquals(6, estado.pendienteDe(i), "el pedido sigue abierto");

        assertTrue(estado.entregar(i, 6, 120L), "con los seis restantes el pedido queda completo");
        assertEquals(0, estado.noEntregadoDe(i), "no falta nada de un pedido completo");
        assertEquals(1, estado.pedidosEntregados(), "pedidos entregados");
    }

    @Test
    @DisplayName("De un pedido incumplido el faltante dice lo que se quedo sin entregar")
    void elFaltanteDeUnIncumplido() {
        // Plazo de 4 horas: a los 300 minutos ya vencio.
        EstadoSimulacion estado = estadoCon(List.of(pedido(3, 8, 0L, 4)));
        int i = estado.indiceDePedido(3);
        estado.registrarPedido(i);
        estado.entregar(i, 3, 60L);

        assertEquals(List.of(i), estado.vencidosHasta(300L), "el pedido vence a las cuatro horas");

        assertEquals(5, estado.noEntregadoDe(i), "quedaron cinco paquetes sin entregar");
        assertEquals(0, estado.pendienteDe(i), "un pedido incumplido ya no esta pendiente de reparto");
        assertEquals(1, estado.pedidosIncumplidos(), "pedidos incumplidos");
    }

    @Test
    @DisplayName("El desglose de kilometros usa el nombre del enumerado como clave")
    void elDesgloseDeKilometrosUsaElNombreDelEnumerado() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(1, 2, 0L, 36)));
        estado.anotarRecorrido(TipoUnidad.AUTO, 30);
        estado.anotarRecorrido(TipoUnidad.MOTO, 12);

        MetricasSimulacion metricas = estado.metricas();

        assertEquals(List.of("AUTO", "MOTO", "BICICLETA"),
                new ArrayList<>(metricas.kilometrosPorTipo().keySet()),
                "claves en el orden del enumerado y con su nombre");
        assertEquals(30, metricas.kilometrosPorTipo().get("AUTO"), "kilometros del auto");
        assertEquals(12, metricas.kilometrosPorTipo().get("MOTO"), "kilometros de la moto");
        assertEquals(0, metricas.kilometrosPorTipo().get("BICICLETA"), "la bicicleta no salio");
        assertEquals(42, metricas.kilometrosTotales(), "kilometros totales de la flota");
    }
}
