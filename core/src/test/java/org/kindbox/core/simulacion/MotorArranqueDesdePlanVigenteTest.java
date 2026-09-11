package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.LectorFlota;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;

/**
 * Cableado del arranque desde el plan vigente en el motor de simulacion, apartados 7.3.5 y
 * 11.4 del ISA.
 *
 * <p>El escenario se arma en memoria, sin tocar {@code data}, con un dia de pedidos, una flota
 * mixta y averias por reglas, que es lo que hace que unidades y pedidos entren y salgan de la
 * fotografia entre una replanificacion y la siguiente. Un algoritmo vigilante envuelve a la
 * busqueda adaptativa y revisa, en cada iteracion, el plan de partida que le entrega el motor:
 * que solo traiga entregas, que toda unidad siga estando en la fotografia, que todo pedido
 * siga pendiente, que ninguna cantidad heredada supere lo que del pedido falta y que cada
 * pedido heredado figure en la asignacion vigente de la instancia. Se comprueba ademas que la
 * corrida termina con la particion de pedidos coherente y que sin el indicador el motor no
 * construye ningun plan de partida.</p>
 */
class MotorArranqueDesdePlanVigenteTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);
    private static final long SEMILLA = 20260911L;
    /** Factor de aceleracion alto a proposito: deja el presupuesto por llamada en el minimo. */
    private static final double FACTOR_ACELERACION = 144_000.0;
    private static final int SALTO_MINUTOS = 120;

    @Test
    @DisplayName("Con el indicador activo cada replanificacion hereda un plan coherente con la fotografia")
    void heredaUnPlanCoherenteConLaFotografia() {
        Vigilante vigilante = new Vigilante(new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA));
        ResultadoSimulacion resultado = correr(configuracion(true), vigilante);

        assertEquals(List.of(), vigilante.problemas(), "planes de partida incoherentes");
        assertTrue(vigilante.llamadasResolverDesde() > 0,
                "ninguna replanificacion heredo el plan vigente");
        assertTrue(vigilante.llamadasResolver() >= 1,
                "la primera replanificacion no tiene plan que heredar y debe usar el arranque ordinario");
        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(resultado.metricas().particionCoherente(),
                "particion de pedidos incoherente: " + resultado.metricas());
        assertTrue(resultado.metricas().pedidosEntregados() > 0, "la corrida no entrego nada");
    }

    @Test
    @DisplayName("Sin el indicador el motor no construye ningun plan de partida")
    void sinIndicadorNoHayPlanDePartida() {
        Vigilante vigilante = new Vigilante(new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA));
        ResultadoSimulacion resultado = correr(configuracion(false), vigilante);

        assertEquals(0, vigilante.llamadasResolverDesde(), "el arranque desde el plan vigente no se pidio");
        assertTrue(vigilante.llamadasResolver() > 1, "el motor debe haber replanificado varias veces");
        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(resultado.metricas().particionCoherente(),
                "particion de pedidos incoherente: " + resultado.metricas());
    }

    // ------------------------------------------------------------- escenario

    private static ResultadoSimulacion correr(ConfiguracionEscenario configuracion, Algoritmo algoritmo) {
        return new MotorSimulacion(datos(), configuracion, new ParametrosOperacion(), algoritmo, List.of())
                .ejecutar();
    }

    private static ConfiguracionEscenario configuracion(boolean arranqueDesdePlanVigente) {
        return new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, DIA, DIA, SALTO_MINUTOS,
                FACTOR_ACELERACION, ModoReloj.LIBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                240, true, 0.30, arranqueDesdePlanVigente);
    }

    /** Escenario de un dia armado en memoria: sin bloqueos ni mantenimiento, con flota mixta. */
    private static RepositorioDatos.DatosEscenario datos() {
        List<UnidadTransporte> unidades = new ArrayList<>();
        for (String codigo : List.of("TA01", "TA02", "TM01", "TM02", "TM03", "TB01", "TB02")) {
            unidades.add(UnidadTransporte.de(codigo, Almacen.crearCentral().nodo()));
        }
        int[] equis = {6, 64, 30, 18, 52, 36, 10, 46, 24, 66, 4, 40};
        int[] yes = {40, 8, 30, 14, 44, 20, 6, 46, 16, 28, 12, 34};
        int[] plazos = {4, 12, 8, 6, 24, 8};
        List<Pedido> pedidos = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int j = i % equis.length;
            pedidos.add(new Pedido(i, "c" + (1000 + i), Ciudad.nodo(equis[j], yes[j]),
                    1 + i % 5, 25L * i, plazos[i % plazos.length]));
        }
        return new RepositorioDatos.DatosEscenario(CalendarioEscenario.desde(DIA), DIA, DIA,
                new LectorFlota.FlotaLeida(unidades, Map.of(), Map.of(), Map.of()),
                pedidos, List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------- vigilante

    /**
     * Algoritmo que delega en otro y anota que planes de partida recibio, con los defectos que
     * les encuentre. No cambia ninguna decision de la busqueda.
     */
    private static final class Vigilante implements Algoritmo {

        private final Algoritmo delegado;
        private final List<String> problemas = new ArrayList<>();
        private int llamadasResolver;
        private int llamadasResolverDesde;

        Vigilante(Algoritmo delegado) {
            this.delegado = delegado;
        }

        @Override
        public String nombre() {
            return delegado.nombre();
        }

        @Override
        public boolean admiteArranqueDesdePlanVigente() {
            return delegado.admiteArranqueDesdePlanVigente();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            llamadasResolver++;
            return delegado.resolver(instancia, presupuesto);
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                               long semilla) {
            llamadasResolver++;
            return delegado.resolver(instancia, presupuesto, semilla);
        }

        @Override
        public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia,
                                                    PresupuestoComputo presupuesto, long semilla,
                                                    Solucion planVigente) {
            llamadasResolverDesde++;
            revisar(instancia, planVigente);
            return delegado.resolverDesde(instancia, presupuesto, semilla, planVigente);
        }

        int llamadasResolver() {
            return llamadasResolver;
        }

        int llamadasResolverDesde() {
            return llamadasResolverDesde;
        }

        List<String> problemas() {
            return problemas;
        }

        private void revisar(InstanciaPlanificacion instancia, Solucion plan) {
            if (plan == null || plan.rutas().isEmpty()) {
                problemas.add("plan de partida vacio en el minuto " + instancia.minutoActual());
                return;
            }
            Map<Integer, Integer> heredado = new HashMap<>();
            for (Ruta ruta : plan.rutas()) {
                if (instancia.indiceDeUnidad(ruta.codigoUnidad()) < 0) {
                    problemas.add("unidad ausente de la fotografia: " + ruta.codigoUnidad());
                }
                for (Parada parada : ruta.paradas()) {
                    if (parada.tipo() != TipoParada.ENTREGA) {
                        problemas.add("parada heredada que no es entrega: " + parada.tipo());
                        continue;
                    }
                    if (instancia.indiceDePedido(parada.idPedido()) < 0) {
                        problemas.add("pedido que ya no esta pendiente: " + parada.idPedido());
                        continue;
                    }
                    if (!ruta.codigoUnidad().equals(instancia.asignacionVigente().get(parada.idPedido()))
                            && !instancia.asignacionVigente().containsKey(parada.idPedido())) {
                        problemas.add("pedido heredado sin asignacion vigente: " + parada.idPedido());
                    }
                    heredado.merge(parada.idPedido(), parada.cantidad(), Integer::sum);
                }
            }
            for (Map.Entry<Integer, Integer> entrada : heredado.entrySet()) {
                int pendiente = instancia.pedidoCantidad(instancia.indiceDePedido(entrada.getKey()));
                if (entrada.getValue() > pendiente) {
                    problemas.add("cantidad heredada " + entrada.getValue() + " mayor que la pendiente "
                            + pendiente + " del pedido " + entrada.getKey());
                }
            }
        }
    }
}
