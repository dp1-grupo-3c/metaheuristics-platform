package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;

/**
 * Reproduccion determinista del colapso de la simulacion de 5 dias que se lanzo desde la web
 * el 12/11/2026 a las 11:25 con ALNS y los datos de data_dp1.
 *
 * <p>En la web el presupuesto de cada replanificacion es de reloj (K=240, unos 4,5 s), de
 * modo que cada corrida da un plan distinto y el colapso no se repite. Aqui ALNS recibe un
 * numero fijo de iteraciones y el resto de la configuracion es la de la web: semilla 20262,
 * salto de 30 minutos, averias por reglas con probabilidad 0,02, arranque desde el plan
 * vigente y colapso con el primer pedido vencido.</p>
 *
 * <p>Antes del nivel de margen de la funcion objetivo esta corrida colapsaba siempre en el
 * pedido 3180 (15/11 13:33, 701 entregados), con el mismo origen que los pedidos 2816 y 3323
 * de los reportes: la busqueda lo mantenia asignado con 1 a 8 minutos de margen durante
 * horas, porque era lo mas barato, y en cuanto un contratiempo se comia ese margen ya ninguna
 * unidad podia atenderlo. Con el nivel de margen debe culminar sin incumplir ningun plazo.</p>
 */
class ReproduccionColapso5DTest {

    private static final Path DATOS = Path.of("..", "data_dp1");
    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 11, 12);
    private static final int MINUTO_INICIAL = 11 * 60 + 25;
    private static final long SEMILLA_WEB = 20262L;
    private static final long ITERACIONES = 200L;

    @Test
    @DisplayName("La 5D del 12/11/2026 11:25 con ALNS por iteraciones culmina sin colapso")
    void culminaSinColapso() throws IOException {
        assumeTrue(Files.isDirectory(DATOS), "sin " + DATOS.toAbsolutePath());
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D,
                PRIMER_DIA, PRIMER_DIA.plusDays(4), 30, 240.0, ModoReloj.LIBRE, "ALNS", SEMILLA_WEB, 5,
                true, 0.02, true, MINUTO_INICIAL);
        // Con arranque a media jornada el horizonte toca el dia siguiente, como en el servicio.
        RepositorioDatos.CargaPorDias carga = new RepositorioDatos(DATOS)
                .abrirPorDias(PRIMER_DIA, PRIMER_DIA.plusDays(5));
        MotorSimulacion motor = new MotorSimulacion(carga.inicio().sinPedidosAntesDe(MINUTO_INICIAL),
                configuracion, new ParametrosOperacion(),
                new PorIteraciones(FabricaAlgoritmos.crear("ALNS", SEMILLA_WEB), ITERACIONES),
                List.of(), carga.siguientes());
        motor.umbralIncumplimientos(1);

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertEquals(0, resultado.metricas().pedidosIncumplidos());
    }

    /** ALNS con un presupuesto fijo de iteraciones en lugar del de reloj que fija el motor. */
    private record PorIteraciones(Algoritmo algoritmo, long iteraciones) implements Algoritmo {

        @Override
        public String nombre() {
            return algoritmo.nombre();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            return algoritmo.resolver(instancia, fijo());
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                               long semilla) {
            return algoritmo.resolver(instancia, fijo(), semilla);
        }

        @Override
        public boolean admiteArranqueDesdePlanVigente() {
            return algoritmo.admiteArranqueDesdePlanVigente();
        }

        @Override
        public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                                    long semilla, Solucion planVigente) {
            return algoritmo.resolverDesde(instancia, fijo(), semilla, planVigente);
        }

        private PresupuestoComputo fijo() {
            return PresupuestoComputo.deIteraciones(iteraciones).arrancar();
        }
    }
}
