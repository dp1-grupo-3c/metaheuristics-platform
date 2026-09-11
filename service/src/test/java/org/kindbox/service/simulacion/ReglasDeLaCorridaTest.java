package org.kindbox.service.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.simulacion.EstadoSimulacion;
import org.kindbox.core.simulacion.UnidadEnCurso;

/**
 * Dos reglas del servicio que se consultan contra el estado del mundo simulado.
 *
 * <p>La primera es el faltante de un pedido, que la tabla del panel lateral muestra en su
 * columna de pendientes: valia la cantidad entera mientras el pedido no estuviese cerrado, con
 * lo que un pedido repartido entre varias unidades y ya servido a medias seguia figurando como
 * si no se hubiese entregado nada.</p>
 *
 * <p>La segunda es la averia sobre una unidad que ya esta parada. El motor la descarta en
 * silencio, de modo que el visualizador recibia un 200 y un mensaje que anunciaba una
 * inmovilizacion que nunca ocurrio; ahora se consulta el estado de la unidad antes de
 * registrarla y la peticion responde 409.</p>
 */
class ReglasDeLaCorridaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    private static Pedido pedido(int id, int cantidad) {
        return new Pedido(id, "c" + (1000 + id), Ciudad.nodo(20, 30), cantidad, 0L, 36);
    }

    private static EstadoSimulacion mundoCon(List<Pedido> pedidos, List<UnidadTransporte> flota) {
        return new EstadoSimulacion(CalendarioEscenario.desde(DIA), new RegistroBloqueos(List.of()),
                Almacen.todos(), pedidos, flota);
    }

    private static List<UnidadTransporte> flota(String... codigos) {
        List<UnidadTransporte> unidades = new ArrayList<>(codigos.length);
        int central = Almacen.crearCentral().nodo();
        for (String codigo : codigos) {
            unidades.add(UnidadTransporte.de(codigo, central));
        }
        return unidades;
    }

    // ------------------------------------------------------------ pendientes

    @Test
    @DisplayName("El pendiente de un pedido a medias descuenta lo ya entregado")
    void elPendienteDescuentaLoEntregado() {
        Pedido p = pedido(7, 10);
        EstadoSimulacion mundo = mundoCon(List.of(p), flota("TA01"));
        int i = mundo.indiceDePedido(7);
        mundo.registrarPedido(i);
        mundo.entregar(i, 4, 60L);

        assertEquals(6, ServicioSimulacion.pendientesDe(mundo, p, false),
                "de diez paquetes con cuatro entregados faltan seis");
    }

    @Test
    @DisplayName("Un pedido entregado no tiene pendientes")
    void elPedidoEntregadoNoTienePendientes() {
        Pedido p = pedido(7, 10);
        EstadoSimulacion mundo = mundoCon(List.of(p), flota("TA01"));
        int i = mundo.indiceDePedido(7);
        mundo.registrarPedido(i);
        mundo.entregar(i, 10, 60L);

        assertEquals(0, ServicioSimulacion.pendientesDe(mundo, p, true), "marcado como entregado");
        assertEquals(0, ServicioSimulacion.pendientesDe(mundo, p, false),
                "el mundo simulado tampoco le deja nada por entregar");
    }

    @Test
    @DisplayName("Sin motor todavia, el pendiente es la cantidad pedida")
    void sinMotorElPendienteEsLaCantidad() {
        Pedido p = pedido(7, 10);

        assertEquals(10, ServicioSimulacion.pendientesDe(null, p, false), "corrida sin motor");
        assertEquals(0, ServicioSimulacion.pendientesDe(null, p, true), "pedido ya entregado");
    }

    @Test
    @DisplayName("Un pedido que el mundo no conoce cuenta entero")
    void elPedidoDesconocidoCuentaEntero() {
        EstadoSimulacion mundo = mundoCon(List.of(pedido(1, 3)), flota("TA01"));

        assertEquals(5, ServicioSimulacion.pendientesDe(mundo, pedido(99, 5), false),
                "el pedido no esta en el escenario cargado");
    }

    // -------------------------------------------------------------- averias

    @Test
    @DisplayName("Una unidad en operacion admite una averia")
    void laUnidadEnOperacionAdmiteAveria() {
        UnidadEnCurso unidad = new UnidadEnCurso(0, UnidadTransporte.de("TA01", Almacen.crearCentral().nodo()));
        unidad.unidad().estado(EstadoUnidad.EN_RUTA);

        assertNull(ServicioSimulacion.conflictoDeUnidad(unidad), "no hay conflicto que informar");
    }

    @Test
    @DisplayName("Una unidad ya averiada no admite otra averia")
    void laUnidadAveriadaNoAdmiteOtra() {
        UnidadEnCurso unidad = new UnidadEnCurso(0, UnidadTransporte.de("TA01", Almacen.crearCentral().nodo()));
        unidad.unidad().estado(EstadoUnidad.AVERIADA);
        unidad.averia(TipoAveria.porCodigo(2));

        String conflicto = ServicioSimulacion.conflictoDeUnidad(unidad);

        assertNotNull(conflicto, "el motor descartaria la averia, hay que decirlo");
        assertTrue(conflicto.contains("TA01"), "el mensaje nombra la unidad: " + conflicto);
        assertTrue(conflicto.contains("tipo 2"), "el mensaje dice que averia la tiene parada: " + conflicto);
    }

    @Test
    @DisplayName("Una unidad en mantenimiento no admite averias")
    void laUnidadEnMantenimientoNoAdmiteAverias() {
        UnidadEnCurso unidad = new UnidadEnCurso(0, UnidadTransporte.de("TM01", Almacen.crearCentral().nodo()));
        unidad.unidad().estado(EstadoUnidad.EN_MANTENIMIENTO);

        String conflicto = ServicioSimulacion.conflictoDeUnidad(unidad);

        assertNotNull(conflicto, "una unidad en mantenimiento no circula");
        assertTrue(conflicto.contains("TM01"), "el mensaje nombra la unidad: " + conflicto);
        assertTrue(conflicto.contains("mantenimiento"), "el mensaje da el motivo: " + conflicto);
    }

    @Test
    @DisplayName("La consulta por codigo encuentra la unidad y calla si no la conoce")
    void laConsultaPorCodigo() {
        EstadoSimulacion mundo = mundoCon(List.of(pedido(1, 3)), flota("TA01", "TM01"));
        mundo.unidad(mundo.indiceDeUnidad("TM01")).unidad().estado(EstadoUnidad.EN_MANTENIMIENTO);

        assertNull(ServicioSimulacion.conflictoDeUnidad(mundo, "TA01"), "unidad disponible");
        assertNotNull(ServicioSimulacion.conflictoDeUnidad(mundo, "TM01"), "unidad en mantenimiento");
        assertNull(ServicioSimulacion.conflictoDeUnidad(mundo, "TB99"),
                "una unidad que no es de la flota la rechaza antes el propio servicio");
        assertNull(ServicioSimulacion.conflictoDeUnidad(null, "TA01"), "sin mundo no hay conflicto que ver");
    }
}
