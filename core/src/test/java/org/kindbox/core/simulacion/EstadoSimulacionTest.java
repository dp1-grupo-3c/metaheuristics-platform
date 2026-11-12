package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.TipoParada;

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
    @DisplayName("El estado copia la flota: mover sus unidades no cambia las de los datos")
    void elEstadoNoTocaLaFlotaDeLosDatos() {
        int central = Almacen.crearCentral().nodo();
        UnidadTransporte original = UnidadTransporte.de("TA01", central);
        EstadoSimulacion estado = new EstadoSimulacion(CalendarioEscenario.desde(DIA), new RegistroBloqueos(List.of()),
                Almacen.todos(), List.of(), List.of(original));

        UnidadTransporte enCurso = estado.unidades()[0].unidad();
        enCurso.nodo(Ciudad.nodo(5, 5));
        enCurso.cargaABordo(7);
        enCurso.estado(EstadoUnidad.EN_RUTA);

        assertEquals("TA01", enCurso.codigo());
        assertEquals(central, original.nodo());
        assertEquals(0, original.cargaABordo());
        assertEquals(EstadoUnidad.DISPONIBLE, original.estado());
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
        assertEquals(List.of(0L, 62L, 184L, 246L), enRuta.stream().map(VistaUnidad.PedidoABordo::minutoLlegada).toList(),
                "cada entrega trae su llegada comprometida");
        assertEquals(List.of(-1, 2, 2, 2), enRuta.stream().map(VistaUnidad.PedidoABordo::idAlmacenCarga).toList(),
                "el viaje cero no tiene almacen conocido; los demas salen de su recarga");
        assertEquals(List.of(-1L, 61L, 61L, 245L), enRuta.stream().map(VistaUnidad.PedidoABordo::minutoSalida).toList());
        assertEquals(List.of(5, 9, 7, 8), enRuta.stream().map(VistaUnidad.PedidoABordo::pendienteDelPedido).toList(),
                "sin registrar falta el pedido entero");
        assertEquals(List.of(1, 3, 5, 7), enRuta.stream().map(VistaUnidad.PedidoABordo::nodosHastaEntrega).toList(),
                "nodos del camino pendiente hasta cada entrega, incluida");

        // Sirviendo la primera recarga, lo cargado ya incluye esos paquetes.
        u.llegarA(1);
        List<VistaUnidad.PedidoABordo> recargando = estado.vistaDe(u, 61L, new ParametrosOperacion().instantanea()).pedidosABordo();
        assertEquals(List.of(2, 3, 4), recargando.stream().map(VistaUnidad.PedidoABordo::idPedido).toList(),
                "la entrega ya hecha sale de la lista");
        assertEquals(List.of(0, 0, 1), recargando.stream().map(VistaUnidad.PedidoABordo::viaje).toList(),
                "la recarga en curso ya no separa viajes");
        assertEquals(List.of(2, 4, 6), recargando.stream().map(VistaUnidad.PedidoABordo::nodosHastaEntrega).toList(),
                "el camino pendiente empieza en la parada donde esta");
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

    @Test
    @DisplayName("La vista distingue si la unidad va llegando o esta por salir y de que almacen salio")
    void elTramoYElOrigenDeLaUnidad() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(1, 5, 0L, 36), pedido(2, 9, 0L, 36),
                pedido(3, 7, 0L, 36), pedido(4, 8, 0L, 36)));
        ParametrosOperacion.Instantanea p = new ParametrosOperacion().instantanea();

        VistaUnidad sinRuta = estado.vistaDe(estado.unidad(estado.indiceDeUnidad("TM01")), 0L, p);
        assertEquals(VistaUnidad.Fase.SIN_RUTA, sinRuta.fase());
        assertEquals(List.of(-1L, -1L, -1L), List.of(sinRuta.minutoSalidaTramo(), sinRuta.minutoLlegadaTramo(),
                sinRuta.minutoSalidaAlmacen()));
        assertEquals(-1, sinRuta.idAlmacenOrigen());

        UnidadEnCurso u = autoConRecargas(estado);
        u.llegarA(1);
        u.cargarEnAlmacen(2, 61L);
        VistaUnidad cargando = estado.vistaDe(u, 61L, p);
        assertEquals(VistaUnidad.Fase.SALIENDO, cargando.fase(), "sirviendo una parada esta por salir");
        assertEquals(61L, cargando.minutoSalidaTramo(), "sale cuando termina el abastecimiento");
        assertEquals(62L, cargando.minutoLlegadaTramo(), "llega a la parada siguiente");
        assertEquals(2, cargando.idAlmacenOrigen());

        u.partirHacia(2, 61L);
        VistaUnidad enCamino = estado.vistaDe(u, 61L, p);
        assertEquals(VistaUnidad.Fase.LLEGANDO, enCamino.fase());
        assertEquals(List.of(61L, 62L, 61L), List.of(enCamino.minutoSalidaTramo(), enCamino.minutoLlegadaTramo(),
                enCamino.minutoSalidaAlmacen()));

        int[] camino = {Ciudad.nodo(5, 5), Ciudad.nodo(6, 5)};
        Parada entrega = Parada.entrega(camino[1], 1, 2, 130, 190, 1);
        UnidadEnCurso bici = estado.unidad(estado.indiceDeUnidad("TB01"));
        bici.asignarItinerario(new Ruta("TB01", TipoUnidad.BICICLETA, camino[0], 120L, List.of(entrega)), camino,
                new int[] {1}, new long[] {130L}, new long[] {190L}, 120L, p);
        VistaUnidad esperando = estado.vistaDe(bici, 100L, p);
        assertEquals(VistaUnidad.Fase.SALIENDO, esperando.fase(), "antes de su hora de salida espera");
        assertEquals(List.of(120L, 130L), List.of(esperando.minutoSalidaTramo(), esperando.minutoLlegadaTramo()));
        assertEquals(VistaUnidad.Fase.LLEGANDO, estado.vistaDe(bici, 125L, p).fase());
    }

    @Test
    @DisplayName("Los kilometros recorridos de la unidad se acumulan y no se reinician al replanificar")
    void losKilometrosRecorridosNoSeReinician() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(1, 2, 0L, 36), pedido(2, 2, 0L, 36)));
        ParametrosOperacion.Instantanea p = new ParametrosOperacion().instantanea();
        UnidadEnCurso bici = estado.unidad(estado.indiceDeUnidad("TB01"));
        assertEquals(0, estado.vistaDe(bici, 0L, p).kilometrosRecorridos(), "sin itinerario no ha recorrido nada");

        int[] primero = {Ciudad.nodo(5, 5), Ciudad.nodo(6, 5), Ciudad.nodo(7, 5), Ciudad.nodo(8, 5)};
        bici.asignarItinerario(new Ruta("TB01", TipoUnidad.BICICLETA, primero[0], 0L,
                        List.of(Parada.entrega(primero[3], 1, 2, 15, 75, 3))), primero,
                new int[] {3}, new long[] {15L}, new long[] {75L}, 0L, p);
        assertEquals(2, bici.cobrarHasta(2));
        assertEquals(2, estado.vistaDe(bici, 10L, p).kilometrosRecorridos());

        // La replanificacion cobra lo recorrido y asigna un camino nuevo: el acumulado sigue.
        int[] segundo = {Ciudad.nodo(7, 5), Ciudad.nodo(7, 6), Ciudad.nodo(7, 7)};
        bici.asignarItinerario(new Ruta("TB01", TipoUnidad.BICICLETA, segundo[0], 100L,
                        List.of(Parada.entrega(segundo[2], 2, 2, 110, 170, 2))), segundo,
                new int[] {2}, new long[] {110L}, new long[] {170L}, 100L, p);
        assertEquals(2, estado.vistaDe(bici, 90L, p).kilometrosRecorridos(), "antes de salir no suma");
        assertEquals(2, bici.cobrarHasta(2));
        assertEquals(4, estado.vistaDe(bici, 120L, p).kilometrosRecorridos());
    }

    @Test
    @DisplayName("El registro de entregas guarda solo las ultimas 24 horas")
    void elRegistroDeEntregasSeRecorta() {
        EstadoSimulacion estado = estadoCon(List.of());
        estado.anotarEntrega(0L, 1, "TA01", 0, 3);
        estado.anotarEntrega(1000L, 2, "TM01", 1, 2);
        estado.anotarEntrega(1440L, 3, "TB01", 2, 1);
        assertEquals(List.of(1, 2, 3), estado.entregasRecientes().stream().map(EntregaRegistrada::idPedido).toList(),
                "a los 1440 la del minuto cero aun cae en la ventana");
        estado.anotarEntrega(2000L, 4, "TA01", 0, 4);
        assertEquals(List.of(2, 3, 4), estado.entregasRecientes().stream().map(EntregaRegistrada::idPedido).toList());
        assertEquals(LocalDate.of(2026, 9, 2).atTime(9, 20), estado.entregasRecientes().get(2).fechaHora());
    }

    @Test
    @DisplayName("El plan vigente recoge la ruta comprometida de cada unidad y lo que quedo sin asignar")
    void elPlanVigenteRecogeLasRutasComprometidas() {
        EstadoSimulacion estado = estadoCon(List.of(pedido(1, 5, 0L, 36), pedido(2, 9, 0L, 36),
                pedido(3, 7, 0L, 36), pedido(4, 8, 0L, 36)));
        autoConRecargas(estado);

        PlanVigente plan = PlanVigente.de(0L, CalendarioEscenario.desde(DIA), "PRUEBA", estado.unidades(),
                Map.of(9, 3));
        assertEquals(1, plan.rutas().size(), "solo el auto tiene itinerario");
        PlanVigente.RutaPlan ruta = plan.rutas().get(0);
        assertEquals("TA01", ruta.codigoUnidad());
        assertEquals(7, ruta.paradas().size());
        assertEquals(List.of(0L, 61L, 62L, 123L, 184L, 245L, 246L),
                ruta.paradas().stream().map(PlanVigente.ParadaPlan::minutoLlegada).toList());
        PlanVigente.ParadaPlan recarga = ruta.paradas().get(1);
        assertEquals(TipoParada.ABASTECIMIENTO, recarga.tipo());
        assertEquals(2, recarga.idAlmacen());
        assertEquals(List.of(11, 10), List.of(recarga.x(), recarga.y()));
        assertEquals(DIA.atTime(1, 1), recarga.fechaHoraLlegada());
        assertEquals(29, plan.paquetesPlanificados(), "5 + 9 + 7 + 8");
        assertEquals(5, plan.kilometros());
        assertEquals(Map.of(9, 3), plan.sinAsignar());
        assertEquals("PRUEBA", plan.algoritmo());
    }
}
