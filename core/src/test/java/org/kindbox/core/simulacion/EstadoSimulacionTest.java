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
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;

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

    /**
     * Itinerario de un auto con dos recargas intercaladas y una pausa de alimentacion:
     * entrega 1, abastecimiento, entrega 2, alimentacion, entrega 3, abastecimiento, entrega 4.
     */
    private static UnidadEnCurso autoConRecargas(EstadoSimulacion estado) {
        int[] camino = new int[7];
        for (int k = 0; k < camino.length; k++) {
            camino[k] = Ciudad.nodo(10 + k, 10);
        }
        List<Parada> paradas = List.of(
                Parada.entrega(camino[0], 1, 5, 0, 60, 0),
                Parada.abastecimiento(camino[1], 2, 20, 61, 61, 1),
                Parada.entrega(camino[2], 2, 9, 62, 122, 1),
                Parada.alimentacion(camino[3], 123, 183),
                Parada.entrega(camino[4], 3, 7, 184, 244, 1),
                Parada.abastecimiento(camino[5], 2, 10, 245, 245, 1),
                Parada.entrega(camino[6], 4, 8, 246, 306, 1));
        long[] llegadas = paradas.stream().mapToLong(Parada::minutoLlegada).toArray();
        long[] salidas = paradas.stream().mapToLong(Parada::minutoSalida).toArray();
        UnidadEnCurso u = estado.unidad(estado.indiceDeUnidad("TA01"));
        u.unidad().nodo(camino[0]);
        u.asignarItinerario(new Ruta("TA01", TipoUnidad.AUTO, camino[0], 0L, paradas), camino,
                new int[] {0, 1, 2, 3, 4, 5, 6}, llegadas, salidas, 0L, new ParametrosOperacion().instantanea());
        return u;
    }

    @Test
    @DisplayName("Los pedidos a bordo distinguen la carga actual de los viajes tras cada recarga")
    void losPedidosABordoSeparanLosViajes() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(1, 5, 0L, 36), pedido(2, 9, 0L, 36),
                pedido(3, 7, 0L, 36), pedido(4, 8, 0L, 36)));
        UnidadEnCurso u = autoConRecargas(estado);

        List<VistaUnidad.PedidoABordo> enRuta = estado.vistaDe(u, 0L, new ParametrosOperacion().instantanea()).pedidosABordo();
        assertEquals(List.of(1, 2, 3, 4), enRuta.stream().map(VistaUnidad.PedidoABordo::idPedido).toList(),
                "la lista conserva todas las entregas pendientes, en orden");
        assertEquals(List.of(0, 1, 1, 2), enRuta.stream().map(VistaUnidad.PedidoABordo::viaje).toList(),
                "cada abastecimiento abre un viaje; la alimentacion no");
        assertTrue(enRuta.get(0).cargado() && !enRuta.get(1).cargado(), "solo el viaje cero va a bordo");

        // Sirviendo la primera recarga, lo cargado ya incluye esos paquetes.
        u.llegarA(1);
        List<VistaUnidad.PedidoABordo> recargando = estado.vistaDe(u, 61L, new ParametrosOperacion().instantanea()).pedidosABordo();
        assertEquals(List.of(2, 3, 4), recargando.stream().map(VistaUnidad.PedidoABordo::idPedido).toList(),
                "la entrega ya hecha sale de la lista");
        assertEquals(List.of(0, 0, 1), recargando.stream().map(VistaUnidad.PedidoABordo::viaje).toList(),
                "la recarga en curso ya no separa viajes");
    }

    @Test
    @DisplayName("La vista de la unidad trae el color de su ocupacion con los umbrales recibidos")
    void elColorDeLaUnidadSigueSuCarga() {
        EstadoSimulacion estado = estadoCon(List.of());
        UnidadEnCurso u = estado.unidad(estado.indiceDeUnidad("TA01"));
        ParametrosOperacion parametros = new ParametrosOperacion();
        ParametrosOperacion.Instantanea porDefecto = parametros.instantanea();

        assertEquals(ColorSemaforo.VACIO, estado.vistaDe(u, 0L, porDefecto).color(), "sale sin carga");
        u.unidad().cargaABordo(12);
        assertEquals(ColorSemaforo.AMBAR, estado.vistaDe(u, 0L, porDefecto).color(), "12 de 24 es la mitad");

        parametros.umbralesOcupacion(20, 40);
        assertEquals(ColorSemaforo.ROJO, estado.vistaDe(u, 0L, parametros.instantanea()).color(),
                "con los umbrales nuevos la mitad ya es rojo");
    }
}
