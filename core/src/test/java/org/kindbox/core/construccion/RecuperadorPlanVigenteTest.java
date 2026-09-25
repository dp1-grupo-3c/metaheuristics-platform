package org.kindbox.core.construccion;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecuperadorPlanVigenteTest {
    @Test
    void descartaUnaUnidadNoDisponibleYAcotaLaEntregaAlRemanente() {
        var pedido = InstanciasDePrueba.pedido(7, 27, 20, 3, 0, 36);
        var instancia = InstanciasDePrueba.fotografia(420, List.of(pedido),
                List.of(InstanciasDePrueba.unidad("TA01", 420)), 900, InstanciasDePrueba.parametros());
        var entrega = new Parada(TipoParada.ENTREGA, pedido.nodoDestino(), 7, -1, 12, 421, 481, 1);
        var rutaAveriada = new Ruta("TA99", TipoUnidad.AUTO, instancia.unidadNodo(0), 0, List.of(entrega));
        var rutaDisponible = new Ruta("TA01", TipoUnidad.AUTO, instancia.unidadNodo(0), 0, List.of(entrega));
        var anterior = new Solucion(List.of(rutaAveriada, rutaDisponible), Map.of(), ValorObjetivo.PEOR);
        var recuperada = RecuperadorPlanVigente.recuperar(instancia, anterior,
                PresupuestoComputo.deIteraciones(1).arrancar());
        assertEquals(1, recuperada.rutas().size());
        assertEquals("TA01", recuperada.rutas().getFirst().codigoUnidad());
        assertEquals(3, recuperada.rutas().getFirst().unidadesEntregadas());
        assertEquals(0, recuperada.h());
        assertTrue(new VerificadorRestricciones().verificar(instancia, recuperada).factible());
        assertTrue(recuperada.rutas().getFirst().paradas().stream()
                .filter(p -> p.tipo() == TipoParada.ENTREGA).allMatch(p -> p.minutoLlegada() > 421),
                "los tiempos anteriores se recalculan con la distancia real");
    }
}
