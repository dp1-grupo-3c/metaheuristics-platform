package org.kindbox.core.construccion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.evaluacion.ResultadoVerificacion;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Heuristica de ahorros de Clarke y Wright del apartado 10.1 del ISA: factibilidad del plan
 * que construye y comportamiento ante las instancias triviales.
 *
 * <p>La heuristica es el arranque de los dos algoritmos, de modo que un plan suyo infactible
 * envenena las dos busquedas a la vez. Por eso la prueba central es la misma verificacion de
 * validez del apartado 12.4 que se aplica a los algoritmos: el plan pasa
 * {@link VerificadorRestricciones} sin una sola infraccion y el valor objetivo que declara es
 * el que recalcula {@link FuncionObjetivoJerarquica}. Se comprueban ademas las tres variantes
 * de granularidad, el determinismo con la misma semilla y las instancias que un juego de
 * datos tipico no produce: sin pedidos, sin unidades, un solo pedido y un pedido mayor que la
 * capacidad de la unidad mas grande, que solo se resuelve con entregas parciales.</p>
 */
class AhorrosClarkeWrightTest {

    private static final long SEMILLA = 20260911L;
    private static final long ARRANQUE = InstanciasDePrueba.INICIO_MANANA;
    private static final long CIERRE = InstanciasDePrueba.FIN_MANANA;

    private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();

    @Test
    @DisplayName("El plan construido pasa el verificador de restricciones sobre las tres fotografias")
    void elPlanConstruidoEsFactible() {
        for (Map.Entry<String, InstanciaPlanificacion> caso : fotografias().entrySet()) {
            InstanciaPlanificacion instancia = caso.getValue();
            Solucion plan = construir(new AhorrosClarkeWright(), instancia);
            ResultadoVerificacion verificacion = new VerificadorRestricciones().verificar(instancia, plan);
            assertTrue(verificacion.factible(), caso.getKey() + ": " + verificacion);
            assertValorCoherente(instancia, plan, caso.getKey());
        }
    }

    @Test
    @DisplayName("Las tres granularidades producen planes factibles y coherentes con su banco")
    void lasTresGranularidadesProducenPlanesFactibles() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(16);
        List<AhorrosClarkeWright> variantes = List.of(
                AhorrosClarkeWright.completo(),
                AhorrosClarkeWright.granular(),
                AhorrosClarkeWright.conGranularidad(8));
        assertEquals(AhorrosClarkeWright.VECINDARIO_COMPLETO, variantes.get(0).granularidad());
        assertEquals(AhorrosClarkeWright.GRANULARIDAD_POR_DEFECTO, variantes.get(1).granularidad());
        for (AhorrosClarkeWright heuristica : variantes) {
            Solucion plan = construir(heuristica, instancia);
            String donde = "granularidad " + heuristica.granularidad();
            assertTrue(new VerificadorRestricciones().verificar(instancia, plan).factible(), donde);
            assertValorCoherente(instancia, plan, donde);
        }
    }

    @Test
    @DisplayName("La misma instancia y la misma semilla construyen exactamente el mismo plan")
    void laMismaSemillaConstruyeElMismoPlan() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaVariada(14);
        Solucion primera = construir(new AhorrosClarkeWright(), instancia);
        Solucion segunda = construir(new AhorrosClarkeWright(), instancia);

        assertEquals(primera.h(), segunda.h());
        assertEquals(Double.doubleToLongBits(primera.costo()), Double.doubleToLongBits(segunda.costo()));
        assertEquals(primera.rutas().size(), segunda.rutas().size());
        for (int r = 0; r < primera.rutas().size(); r++) {
            Ruta a = primera.rutas().get(r);
            Ruta b = segunda.rutas().get(r);
            assertEquals(a.codigoUnidad(), b.codigoUnidad());
            assertEquals(a.paradas().size(), b.paradas().size(), "ruta " + a.codigoUnidad());
            for (int p = 0; p < a.paradas().size(); p++) {
                assertEquals(a.paradas().get(p), b.paradas().get(p), "ruta " + a.codigoUnidad() + " parada " + p);
            }
        }
    }

    @Test
    @DisplayName("Instancia trivial sin pedidos: el plan queda vacio y no se recorre ningun kilometro")
    void instanciaSinPedidos() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE, InstanciasDePrueba.parametros());
        Solucion plan = construir(new AhorrosClarkeWright(), instancia);

        assertEquals(0, plan.h());
        assertEquals(0, plan.kilometros());
        assertTrue(plan.rutasConEntregas().isEmpty());
        assertTrue(plan.cantidadNoAtendida().isEmpty());
    }

    @Test
    @DisplayName("Instancia trivial sin unidades: todo el pedido queda en el banco con su cantidad completa")
    void instanciaSinUnidades() {
        List<Pedido> pedidos = List.of(InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos, List.of(),
                CIERRE, InstanciasDePrueba.parametros());
        Solucion plan = construir(new AhorrosClarkeWright(), instancia);

        assertEquals(1, plan.h());
        assertEquals(4, plan.cantidadNoAtendida().get(0));
        assertTrue(plan.rutas().isEmpty());
        assertValorCoherente(instancia, plan, "sinUnidades");
    }

    @Test
    @DisplayName("Instancia trivial de un solo pedido: una ruta con una entrega y el banco vacio")
    void instanciaDeUnSoloPedido() {
        List<Pedido> pedidos = List.of(InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE, InstanciasDePrueba.parametros());
        Solucion plan = construir(new AhorrosClarkeWright(), instancia);

        assertEquals(0, plan.h());
        assertEquals(1, plan.rutasConEntregas().size());
        assertEquals(4, plan.unidadesEntregadas());
        assertTrue(plan.kilometros() > 0, "la unidad tiene que desplazarse hasta el destinatario");
        assertTrue(new VerificadorRestricciones().verificar(instancia, plan).factible());
        assertValorCoherente(instancia, plan, "unSoloPedido");
    }

    @Test
    @DisplayName("Un pedido mayor que la capacidad de la unidad se parte en varias entregas")
    void pedidoMayorQueLaCapacidad() {
        int capacidad = TipoUnidad.AUTO.capacidad();
        List<Pedido> pedidos = List.of(InstanciasDePrueba.pedido(0, 30, 20, capacidad + 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE, InstanciasDePrueba.parametros());
        Solucion plan = construir(new AhorrosClarkeWright(), instancia);

        assertEquals(0, plan.h(), "el pedido cabe si se parte en varias visitas");
        assertEquals(capacidad + 6, plan.unidadesEntregadas());
        int visitas = 0;
        for (Ruta ruta : plan.rutas()) {
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() == TipoParada.ENTREGA) {
                    visitas++;
                    assertTrue(parada.cantidad() <= capacidad, "ninguna visita mueve mas que la capacidad");
                }
            }
        }
        assertTrue(visitas >= 2, "un pedido de " + (capacidad + 6) + " unidades exige al menos dos visitas");
        assertTrue(new VerificadorRestricciones().verificar(instancia, plan).factible());
    }

    // ------------------------------------------------------------------ apoyo

    /** Fotografias de trabajo con su nombre, para que el mensaje de fallo diga cual fallo. */
    private static Map<String, InstanciaPlanificacion> fotografias() {
        Map<String, InstanciaPlanificacion> casos = new LinkedHashMap<>();
        casos.put("variada(14)", InstanciasDePrueba.instanciaVariada(14));
        casos.put("flotaMixta(16)", InstanciasDePrueba.instanciaFlotaMixta(16));
        casos.put("plazosAjustados(18)", InstanciasDePrueba.instanciaPlazosAjustados(18));
        return casos;
    }

    private static Solucion construir(AhorrosClarkeWright heuristica, InstanciaPlanificacion instancia) {
        return heuristica.construir(instancia, new ProgramadorRuta(instancia), new Aleatorio(SEMILLA));
    }

    /**
     * Comprueba que el valor declarado sea el que recalcula la funcion objetivo comun y que el
     * banco cuadre con lo entregado: ningun pedido puede recibir mas de lo pendiente ni
     * quedarse fuera del banco con un remanente sin asignar.
     */
    private void assertValorCoherente(InstanciaPlanificacion instancia, Solucion plan, String donde) {
        ValorObjetivo recalculado = objetivo.evaluar(instancia, plan);
        assertEquals(recalculado.h(), plan.valor().h(), donde + ": H declarada distinta de la recalculada");
        assertEquals(Double.doubleToLongBits(recalculado.costo()),
                Double.doubleToLongBits(plan.valor().costo()), donde + ": costo declarado distinto");
        assertEquals(recalculado.h(), plan.h(), donde + ": el banco no coincide con H");

        List<Integer> entregado = new ArrayList<>(instancia.cantidadPedidos());
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            entregado.add(0);
        }
        for (Ruta ruta : plan.rutas()) {
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() == TipoParada.ENTREGA) {
                    int i = instancia.indiceDePedido(parada.idPedido());
                    entregado.set(i, entregado.get(i) + parada.cantidad());
                }
            }
        }
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            int pendiente = instancia.pedidoCantidad(p) - entregado.get(p);
            assertTrue(pendiente >= 0, donde + ": el pedido " + instancia.pedidoId(p) + " recibe de mas");
            Integer enBanco = plan.cantidadNoAtendida().get(instancia.pedidoId(p));
            if (pendiente > 0) {
                assertEquals(pendiente, enBanco, donde + ": remanente del pedido " + instancia.pedidoId(p));
            } else {
                assertNull(enBanco, donde + ": pedido completo anotado en el banco");
            }
        }
    }
}
