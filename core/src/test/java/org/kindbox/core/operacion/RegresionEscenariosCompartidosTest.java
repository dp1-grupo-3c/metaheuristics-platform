package org.kindbox.core.operacion;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.LectorFlota;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;

class RegresionEscenariosCompartidosTest {
    @Test void cincoDiasConservaHorizonteEntregasYMetricas() {
        var dia = LocalDate.of(2026, 10, 28);
        var configuracion = new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D, dia, dia.plusDays(4),
                30, 144000, ModoReloj.LIBRE, "PRUEBA", 7, 30, false, 0, false);
        var resultado = new MotorSimulacion(datos(dia), configuracion, new ParametrosOperacion(),
                new MotorOperacionTest.Constructivo(), List.of()).ejecutar();
        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertEquals(7200, resultado.minutoFinal());
        assertEquals(1, resultado.metricas().pedidosEntregados());
        assertEquals(1, resultado.metricas().unidadesEntregadas());
        assertTrue(resultado.metricas().particionCoherente());
    }

    @Test void colapsoConservaCierreEnElPrimerIncumplimiento() {
        var dia = LocalDate.of(2026, 10, 28);
        var configuracion = new ConfiguracionEscenario(TipoEscenario.COLAPSO, dia, dia.plusDays(4),
                30, 144000, ModoReloj.LIBRE, "PRUEBA", 7, 30, false, 0, false);
        var sinAsignar = new MotorOperacionTest.Constructivo() {
            @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo p) {
                return new ResultadoPlanificacion(nombre(), Solucion.vacia(instancia), null, 1, 1, 7, Map.of());
            }
        };
        var resultado = new MotorSimulacion(datos(dia), configuracion, new ParametrosOperacion(),
                sinAsignar, List.of()).ejecutar();
        assertEquals(EstadoCorrida.COLAPSADA, resultado.estado(), resultado.mensaje());
        assertEquals(240, resultado.minutoFinal());
        assertEquals(1, resultado.metricas().pedidosIncumplidos());
        assertTrue(resultado.metricas().particionCoherente());
    }

    private RepositorioDatos.DatosEscenario datos(LocalDate dia) {
        int central = Almacen.crearCentral().nodo();
        var flota = new LectorFlota.FlotaLeida(List.of(new UnidadTransporte("TA01", TipoUnidad.AUTO, central)),
                Map.of(), Map.of(), Map.of());
        return new RepositorioDatos.DatosEscenario(CalendarioEscenario.desde(dia), dia, dia.plusDays(4), flota,
                List.of(new Pedido(1, "c1", central, 1, 0, 4)), List.of(), List.of(), List.of());
    }
}
