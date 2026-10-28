package org.kindbox.core.operacion;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.io.LectorFlota;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.util.Aleatorio;

class MotorOperacionTest {
    static final class Reloj extends Clock {
        final AtomicReference<Instant> actual;
        Reloj(String instante) { actual = new AtomicReference<>(Instant.parse(instante)); }
        void avanzar(long minutos) { actual.updateAndGet(i -> i.plusSeconds(minutos * 60)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return actual.get(); }
    }

    static class Constructivo implements Algoritmo {
        final AtomicInteger llamadas = new AtomicInteger();
        @Override public String nombre() { return "PRUEBA"; }
        @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            llamadas.incrementAndGet();
            Solucion solucion = new AhorrosClarkeWright().construir(instancia, new ProgramadorRuta(instancia), new Aleatorio(7));
            return new ResultadoPlanificacion(nombre(), solucion, null, 1, 1, 7, Map.of());
        }
    }

    static FuenteEntornoOperacion entorno(List<Mantenimiento> mantenimientos, List<Bloqueo> bloqueos) {
        return (desde, hasta, calendario) -> new RepositorioDatos.DatosEscenario(calendario, desde, hasta,
                new LectorFlota.FlotaLeida(List.of(new UnidadTransporte("TA01", TipoUnidad.AUTO,
                        Almacen.crearCentral().nodo())), Map.of(), Map.of(), Map.of()),
                List.of(), bloqueos, mantenimientos, List.of());
    }

    static MotorOperacion crear(Reloj reloj, Algoritmo algoritmo) throws IOException {
        return new MotorOperacion("op-prueba", ConfiguracionOperacion.porDefecto(), reloj,
                entorno(List.of(), List.of()), algoritmo);
    }

    static SolicitudPedidoOperacion pedido(String clave, int cantidad, int x, int y) {
        return new SolicitudPedidoOperacion(clave, "c1", "Cliente", "999999999", "Direccion", x, y,
                cantidad, PrioridadPedido.NORMAL);
    }

    static void esperarPlan(MotorOperacion motor, Constructivo algoritmo) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            motor.actualizar();
            if (algoritmo.llamadas.get() > 0 && !motor.vista().calculando()) {
                assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado(), motor.vista().error());
                return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < limite);
        fail("El plan no termino: " + motor.vista());
    }

    @Test void iniciaEnHoraOperativaSinPedidosYEspera() throws Exception {
        var reloj = new Reloj("2026-10-28T17:34:00Z");
        try (var motor = crear(reloj, new Constructivo())) {
            assertEquals("2026-10-28T12:34", motor.vista().fechaHoraOperativa().toString());
            assertTrue(motor.vista().pedidos().isEmpty());
            long revision = motor.vista().revision();
            reloj.avanzar(1);
            motor.actualizar();
            assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado());
            assertTrue(motor.vista().revision() > revision);
            assertEquals(37, new LectorFlota().flotaPorDefecto().unidades().size());
        }
    }

    @Test void incorporaPedidosDespuesDeArrancarYConservaIndicesAlCrecer() throws Exception {
        var algoritmo = new Constructivo();
        try (var motor = crear(new Reloj("2026-10-28T17:00:00Z"), algoritmo)) {
            for (int i = 0; i < 40; i++) motor.aceptar(pedido("pedido" + i, 1, 28, 14));
            motor.actualizar();
            assertEquals(40, motor.vista().pedidos().size());
            assertEquals(40, motor.vista().instantanea().metricas().pedidosRegistrados());
            assertEquals(40, motor.vista().pedidos().stream().map(VistaPedidoOperacion::id).distinct().count());
            assertTrue(motor.vista().instantanea().metricas().particionCoherente());
            assertEquals(ComandoOperacion.Estado.APLICADO, motor.comando("pedido0").estado());
        }
    }

    @Test void deduplicaAntesYDespuesDeAplicarYRechazaContenidoDistinto() throws Exception {
        try (var motor = crear(new Reloj("2026-10-28T17:00:00Z"), new Constructivo())) {
            var solicitud = pedido("unico", 2, 28, 14);
            assertEquals(motor.aceptar(solicitud), motor.aceptar(solicitud));
            motor.actualizar();
            assertEquals(ComandoOperacion.Estado.APLICADO, motor.aceptar(solicitud).estado());
            assertEquals(1, motor.vista().pedidos().size());
            assertThrows(IllegalStateException.class, () -> motor.aceptar(pedido("unico", 3, 28, 14)));
        }
    }

    @Test void aplicaPrioridadYRegistroDesdeServidor() throws Exception {
        try (var motor = crear(new Reloj("2026-10-28T17:00:00Z"), new Constructivo())) {
            motor.aceptar(new SolicitudPedidoOperacion("prioritario", "c2", "Nombre", "123", "Calle", 70, 50,
                    2, PrioridadPedido.PRIORITARIO_1));
            motor.actualizar();
            var pedido = motor.vista().pedidos().getFirst();
            assertEquals(4, Duration.between(pedido.registro(), pedido.limite()).toHours());
            assertEquals("2026-10-28T12:00", pedido.registro().toString());
            assertEquals("Nombre", pedido.nombreCliente());
            assertEquals("Calle", pedido.direccion());
        }
    }

    @Test void validaCamposNodoCantidadYPrioridad() {
        assertThrows(IllegalArgumentException.class, () -> pedido("x", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> pedido("x", 1, 71, 0));
        assertThrows(IllegalArgumentException.class, () -> pedido("x", 1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SolicitudPedidoOperacion("x", "c", "", "1", "a",
                0, 0, 1, PrioridadPedido.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> new SolicitudPedidoOperacion("x", "c", "n", "1", "a",
                0, 0, 1, null));
    }

    @Test void planificaEntregaYMantieneLaHoraDeServicioAlCambiarDeDia() throws Exception {
        var reloj = new Reloj("2026-10-29T04:58:00Z");
        var algoritmo = new Constructivo();
        try (var motor = crear(reloj, algoritmo)) {
            motor.aceptar(pedido("entrega", 2, 28, 14));
            esperarPlan(motor, algoritmo);
            assertFalse(motor.vista().pedidos().getFirst().transiciones().isEmpty());
            reloj.avanzar(2);
            motor.actualizar();
            assertEquals(EstadoPedido.ENTREGADO, motor.vista().pedidos().getFirst().estado());
            assertEquals(EstadoUnidad.ENTREGANDO, motor.vista().instantanea().unidades().getFirst().estado());
            assertEquals("2026-10-29T00:00", motor.vista().fechaHoraOperativa().toString());
            assertEquals(1, motor.vista().instantanea().metricas().pedidosEntregados());
            reloj.avanzar(59);
            motor.actualizar();
            assertEquals(EstadoUnidad.ENTREGANDO, motor.vista().instantanea().unidades().getFirst().estado());
            reloj.avanzar(1);
            motor.actualizar();
            assertNotEquals(EstadoUnidad.ENTREGANDO, motor.vista().instantanea().unidades().getFirst().estado());
            assertEquals(2, motor.vista().instantanea().metricas().unidadesEntregadas());
            assertTrue(motor.vista().instantanea().metricas().particionCoherente());
        }
    }

    @Test void noPierdeComandosDuranteBusquedaYDescartaElPlanObsoleto() throws Exception {
        var iniciado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var algoritmo = new Constructivo() {
            @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p) {
                iniciado.countDown();
                try { assertTrue(liberar.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.resolver(i, p);
            }
        };
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        try (var motor = crear(reloj, algoritmo)) {
            motor.aceptar(pedido("primero", 1, 28, 14));
            motor.actualizar();
            assertTrue(iniciado.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                motor.aceptar(pedido("segundo", 1, 29, 14));
                motor.actualizar();
            });
            assertEquals(ComandoOperacion.Estado.APLICADO, motor.comando("segundo").estado());
            reloj.avanzar(1);
            motor.actualizar();
            liberar.countDown();
            esperarPlan(motor, algoritmo);
            assertTrue(motor.vista().planesDescartados() >= 1);
            assertEquals(2, motor.vista().pedidos().size());
            assertTrue(algoritmo.llamadas.get() >= 2);
            assertTrue(motor.vista().instantanea().metricas().particionCoherente());
        } finally { liberar.countDown(); }
    }

    @Test void averiasExplicitasSeRechazanSiYaEstaDeshabilitadaYSeResuelvenPorReloj() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        try (var motor = crear(reloj, new Constructivo())) {
            motor.aceptar(new SolicitudAveriaOperacion("averia1", "TA01", 1));
            motor.actualizar();
            assertEquals(EstadoUnidad.AVERIADA, motor.vista().instantanea().unidades().getFirst().estado());
            motor.aceptar(new SolicitudAveriaOperacion("averia2", "TA01", 1));
            motor.actualizar();
            assertEquals(ComandoOperacion.Estado.RECHAZADO, motor.comando("averia2").estado());
            reloj.avanzar(120);
            motor.actualizar();
            assertEquals(EstadoUnidad.DISPONIBLE, motor.vista().instantanea().unidades().getFirst().estado());
            assertTrue(motor.vista().incidentes().getFirst().resuelto());
            assertEquals(1, motor.vista().instantanea().metricas().averiasPorTipo().get(1));
        }
    }

    @Test void respetaMantenimientoVigenteAlIniciarAMediaJornada() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        var mantenimiento = new Mantenimiento(LocalDate.of(2026, 10, 28), "TA01");
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj,
                entorno(List.of(mantenimiento), List.of()), new Constructivo())) {
            assertEquals(EstadoUnidad.EN_MANTENIMIENTO, motor.vista().instantanea().unidades().getFirst().estado());
            reloj.avanzar(720);
            motor.actualizar();
            assertEquals(EstadoUnidad.DISPONIBLE, motor.vista().instantanea().unidades().getFirst().estado());
        }
    }

    @Test void extiendeEntornoYAgendaAlCruzarMesSinReiniciarPedidos() throws Exception {
        var reloj = new Reloj("2026-11-01T04:59:00Z");
        var cargas = new AtomicInteger();
        FuenteEntornoOperacion fuente = (desde, hasta, calendario) -> {
            cargas.incrementAndGet();
            return entorno(List.of(), List.of()).cargar(desde, hasta, calendario);
        };
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj, fuente, new Constructivo())) {
            motor.aceptar(pedido("pendiente", 100, 70, 50));
            motor.actualizar();
            reloj.avanzar(2);
            motor.actualizar();
            assertEquals("2026-11-01T00:01", motor.vista().fechaHoraOperativa().toString());
            assertEquals(2, cargas.get());
            assertEquals(1, motor.vista().pedidos().size());
            assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado());
        }
    }

    @Test void publicaBloqueosActivosYActualizaSuVigencia() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        var bloqueo = new Bloqueo(new int[]{Ciudad.nodo(1, 1), Ciudad.nodo(2, 1)}, 700, 730);
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj,
                entorno(List.of(), List.of(bloqueo)), new Constructivo())) {
            assertEquals(1, motor.vista().instantanea().bloqueosVigentes().size());
            reloj.avanzar(10);
            motor.actualizar();
            assertTrue(motor.vista().instantanea().bloqueosVigentes().isEmpty());
        }
    }

    @Test void parametrosSonPropiosDeCadaOperacion() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        try (var uno = crear(reloj, new Constructivo()); var dos = crear(reloj, new Constructivo())) {
            uno.aceptar(new SolicitudParametrosOperacion("parametros", Map.of(TipoUnidad.AUTO, 20.0), 100, 200));
            uno.actualizar();
            assertEquals(20, uno.vista().parametros().velocidad(TipoUnidad.AUTO));
            assertEquals(40, dos.vista().parametros().velocidad(TipoUnidad.AUTO));
            assertEquals(500, dos.vista().parametros().umbralSemaforoVerde());
        }
    }

    @Test void detenerRechazaComandosPendientesYConservaLaConsulta() throws Exception {
        var motor = crear(new Reloj("2026-10-28T17:00:00Z"), new Constructivo());
        motor.aceptar(pedido("sinaplicar", 1, 28, 14));
        motor.close();
        assertEquals(VistaOperacion.Estado.DETENIDA, motor.vista().estado());
        assertEquals(ComandoOperacion.Estado.RECHAZADO, motor.comando("sinaplicar").estado());
        assertThrows(IllegalStateException.class, () -> motor.aceptar(pedido("otro", 1, 28, 14)));
        motor.close();
    }

    @Test void falloDeFuenteTerminaYExplicaElEstado() throws Exception {
        var reloj = new Reloj("2026-10-29T04:59:00Z");
        FuenteEntornoOperacion fuente = (desde, hasta, calendario) -> {
            if (hasta.isAfter(LocalDate.of(2026, 10, 31))) throw new IOException("Datos ilegibles");
            return entorno(List.of(), List.of()).cargar(desde, hasta, calendario);
        };
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj, fuente, new Constructivo())) {
            reloj.avanzar(2);
            motor.actualizar();
            assertEquals(VistaOperacion.Estado.FALLIDA, motor.vista().estado());
            assertEquals("Datos ilegibles", motor.vista().error());
        }
    }

    @Test void entornoNoLeeVentasMalformadasNiExigePedidos(@TempDir Path raiz) throws Exception {
        Files.createDirectories(raiz.resolve("ventas"));
        Files.writeString(raiz.resolve("ventas/ventas202610"), "datos invalidos");
        var calendario = org.kindbox.core.io.CalendarioEscenario.desde(LocalDate.of(2026, 10, 28));
        var datos = new RepositorioDatos(raiz).cargarEntorno(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 31), calendario);
        assertTrue(datos.pedidos().isEmpty());
        assertEquals(37, datos.unidades().size());
    }
    @Test void entregaParcialSeCompletaSinDuplicarPaquetesEnReplanificaciones() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        var algoritmo = new Constructivo();
        try (var motor = crear(reloj, algoritmo)) {
            motor.aceptar(pedido("parcial", 30, 27, 14));
            esperarPlan(motor, algoritmo);
            motor.actualizar();
            for (int minuto = 0; minuto < 240; minuto++) {
                reloj.avanzar(1);
                motor.actualizar();
                if (motor.vista().calculando()) esperarPlan(motor, algoritmo);
                assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado(), motor.vista().error());
                assertTrue(motor.vista().instantanea().metricas().particionCoherente());
                assertTrue(motor.vista().instantanea().metricas().unidadesEntregadas() <= 30);
            }
            assertTrue(motor.vista().pedidos().getFirst().transiciones().stream()
                    .anyMatch(t -> t.estado() == EstadoPedido.ENTREGADO_PARCIALMENTE));
            assertEquals(EstadoPedido.ENTREGADO, motor.vista().pedidos().getFirst().estado());
            assertEquals(0, motor.vista().pedidos().getFirst().pendiente());
            assertEquals(30, motor.vista().instantanea().metricas().unidadesEntregadas());
            assertEquals(1, motor.vista().instantanea().metricas().pedidosEntregados());
        }
    }

    @Test void mantenimientosConsecutivosNoReincorporanLaUnidadEntreDias() throws Exception {
        var reloj = new Reloj("2026-10-29T04:59:00Z");
        var hoy = LocalDate.of(2026, 10, 28);
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj,
                entorno(List.of(new Mantenimiento(hoy, "TA01"), new Mantenimiento(hoy.plusDays(1), "TA01")),
                        List.of()), new Constructivo())) {
            reloj.avanzar(1);
            motor.actualizar();
            assertEquals(EstadoUnidad.EN_MANTENIMIENTO, motor.vista().instantanea().unidades().getFirst().estado());
            reloj.avanzar(1440);
            motor.actualizar();
            assertEquals(EstadoUnidad.DISPONIBLE, motor.vista().instantanea().unidades().getFirst().estado());
        }
    }

    @Test void conservaBloqueoDelMesAnteriorHastaSuFinReal() throws Exception {
        var reloj = new Reloj("2026-11-01T04:59:00Z");
        var bloqueo = new Bloqueo(new int[]{Ciudad.nodo(1, 1), Ciudad.nodo(2, 1)}, 700, 6000);
        FuenteEntornoOperacion fuente = (desde, hasta, calendario) -> entorno(List.of(),
                desde.getMonthValue() == 10 ? List.of(bloqueo) : List.of()).cargar(desde, hasta, calendario);
        try (var motor = new MotorOperacion("op", ConfiguracionOperacion.porDefecto(), reloj, fuente, new Constructivo())) {
            reloj.avanzar(1440 * 3 + 1);
            motor.actualizar();
            assertEquals(1, motor.vista().instantanea().bloqueosVigentes().size());
            assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado());
        }
    }

    @Test void elRelojNoRetrocedeYLasVistasNoExponenArreglosMutables() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        try (var motor = crear(reloj, new Constructivo())) {
            var vista = motor.vista();
            vista.parametros().velocidadKmH()[0] = 1;
            assertEquals(40, vista.parametros().velocidad(TipoUnidad.AUTO));
            reloj.avanzar(-60);
            motor.actualizar();
            assertEquals("2026-10-28T12:00", motor.vista().fechaHoraOperativa().toString());
            assertThrows(UnsupportedOperationException.class, () -> vista.pedidos().clear());
        }
    }

    @Test void incumplirUnPedidoNoDetieneLaOperacionNiPierdeSuRemanente() throws Exception {
        var reloj = new Reloj("2026-10-28T17:00:00Z");
        try (var motor = crear(reloj, new Constructivo())) {
            motor.aceptar(new SolicitudAveriaOperacion("mayor", "TA01", 3));
            motor.aceptar(new SolicitudPedidoOperacion("vence", "c1", "Nombre", "999", "Direccion", 28, 14,
                    3, PrioridadPedido.PRIORITARIO_1));
            motor.actualizar();
            reloj.avanzar(241);
            motor.actualizar();
            assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado(), motor.vista().error());
            assertTrue(motor.vista().pedidos().getFirst().incumplido());
            assertEquals(3, motor.vista().pedidos().getFirst().pendiente());
            assertEquals(1, motor.vista().instantanea().metricas().pedidosIncumplidos());
            assertTrue(motor.vista().instantanea().metricas().particionCoherente());
        }
    }

    @Test void modeloOperativoRechazaTransicionesInvalidasSinAlterarCantidades() {
        var calendario = org.kindbox.core.io.CalendarioEscenario.desde(LocalDate.of(2026, 10, 28));
        var pedido = new PedidoOperativo(1, pedido("estado", 2, 28, 14), 720, calendario);
        assertThrows(IllegalStateException.class, () -> pedido.cambiar(EstadoPedido.ENTREGADO, 721));
        assertThrows(IllegalStateException.class, () -> pedido.descargar(0, 721));
        assertEquals(2, pedido.vista().pendiente());
        pedido.cambiar(EstadoPedido.PLANIFICADO, 721);
        pedido.descargar(0, 722);
        assertEquals(EstadoPedido.ENTREGADO, pedido.vista().estado());
        assertThrows(IllegalStateException.class, () -> pedido.cambiar(EstadoPedido.EN_REPLANIFICACION, 723));
        assertEquals(0, pedido.vista().pendiente());
    }

    @Test void nuevasAltasNoImpidenComprometerUnPlanCompatible() throws Exception {
        var iniciado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var algoritmo = new Constructivo() {
            @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p) {
                iniciado.countDown();
                try { assertTrue(liberar.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.resolver(i, p);
            }
        };
        try (var motor = crear(new Reloj("2026-10-28T17:00:00Z"), algoritmo)) {
            motor.aceptar(pedido("primero", 1, 28, 14));
            motor.actualizar();
            assertTrue(iniciado.await(1, TimeUnit.SECONDS));
            motor.aceptar(pedido("segundo", 1, 29, 14));
            motor.actualizar();
            liberar.countDown();
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (algoritmo.llamadas.get() < 2 || motor.vista().calculando()) {
                motor.actualizar();
                assertEquals(VistaOperacion.Estado.EN_CURSO, motor.vista().estado(), motor.vista().error());
                assertTrue(System.nanoTime() < limite);
                Thread.sleep(5);
            }
            assertEquals(0, motor.vista().planesDescartados());
            assertEquals(2, motor.vista().pedidos().size());
            assertTrue(motor.vista().pedidos().stream().allMatch(p -> p.estado() != EstadoPedido.REGISTRADO));
        } finally { liberar.countDown(); }
    }

}
