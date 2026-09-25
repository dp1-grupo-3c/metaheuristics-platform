package org.kindbox.core.metaheuristica.alns;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.problema.InstanciaPlanificacion;
import static org.junit.jupiter.api.Assertions.*;

class CompromisosUrgentesTest {
    @Test
    void noIntercambiaIdentidadesAunqueLaCantidadEnBancoSeaLaMisma() {
        var pedidos = List.of(InstanciasDePrueba.pedido(0, 21, 20, 1, 360, 4),
                InstanciasDePrueba.pedido(1, 21, 20, 1, 360, 4));
        var unidad = InstanciasDePrueba.unidad("TA01", 540);
        var base = InstanciasDePrueba.fotografia(540, pedidos, List.of(unidad), 900,
                InstanciasDePrueba.parametros());
        var constructor = InstanciaPlanificacion.constructor().minutoActual(540)
                .parametros(base.parametros()).matriz(base.matriz()).unidad(unidad);
        for (var almacen : Almacen.todos()) constructor.almacen(almacen, almacen.capacidad());
        for (var pedido : pedidos) constructor.pedido(pedido, pedido.cantidad()).asignacionVigente(pedido.id(), "TA01");
        var instancia = constructor.construir();
        var referencia = new EstadoAlns(instancia, new ProgramadorRuta(instancia));
        var candidata = new EstadoAlns(instancia, new ProgramadorRuta(instancia));
        referencia.configurarEstabilidad(new PlanVigenteAlns(instancia), 0);
        candidata.configurarEstabilidad(new PlanVigenteAlns(instancia), 0);
        assertTrue(referencia.insertar(0, 0, 0));
        assertTrue(candidata.insertar(0, 0, 1));
        assertEquals(referencia.h(), candidata.h());
        assertTrue(candidata.pierdeCompromisosDe(referencia));
        assertFalse(referencia.pierdeCompromisosDe(referencia));
    }
}
