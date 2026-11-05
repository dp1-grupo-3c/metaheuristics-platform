package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.io.LectorVentas;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.util.Aleatorio;

/**
 * Escenario abierto del colapso, que carga los meses a medida que avanza el reloj.
 *
 * <p>La prueba central compara la corrida abierta con la misma corrida sobre los tres meses
 * cargados de antemano: con un planificador determinista y sin averias por reglas, ambas
 * tienen que colapsar en el mismo minuto, por el mismo pedido y con las mismas metricas. El
 * colapso se fuerza en el tercer mes con un pedido imposible de servir, de modo que la
 * corrida abierta tiene que cruzar dos cambios de mes para llegar a el.</p>
 */
class EscenarioAbiertoTest {

    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 9, 1);
    private static final YearMonth SEPTIEMBRE = YearMonth.from(PRIMER_DIA);
    private static final YearMonth OCTUBRE = SEPTIEMBRE.plusMonths(1);
    private static final YearMonth NOVIEMBRE = SEPTIEMBRE.plusMonths(2);
    private static final long SEMILLA = 20261103L;
    private static final double PEDIDOS_POR_DIA = 8.0;
    /** Quinientos paquetes con una hora de plazo: ninguna flota llega a tiempo. */
    private static final String PEDIDO_IMPOSIBLE = "15d10h00m:70,50,c9999,500,1";

    @Test
    @DisplayName("La corrida abierta colapsa igual que la que carga todo el rango de antemano")
    void colapsaIgualQueLaCargaCompleta(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz, SEPTIEMBRE, OCTUBRE, NOVIEMBRE);
        Files.writeString(archivoVentas(raiz, NOVIEMBRE), PEDIDO_IMPOSIBLE + "\n", StandardOpenOption.APPEND);
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        LocalDate ultimoDia = NOVIEMBRE.atEndOfMonth();

        ResultadoSimulacion completo = new MotorSimulacion(repositorio.cargar(PRIMER_DIA, ultimoDia),
                configuracion(ultimoDia), new ParametrosOperacion(), new Constructivo(), List.of()).ejecutar();

        RepositorioDatos.CargaAbierta carga = repositorio.abrir(PRIMER_DIA);
        MotorSimulacion abierto = new MotorSimulacion(carga.inicio(), configuracion(ultimoDia),
                new ParametrosOperacion(), new Constructivo(), List.of(), carga.siguientes());
        ResultadoSimulacion incremental = abierto.ejecutar();

        assertEquals(EstadoCorrida.COLAPSADA, completo.estado(), completo.mensaje());
        assertEquals(EstadoCorrida.COLAPSADA, incremental.estado(), incremental.mensaje());
        long inicioNoviembre = carga.inicio().calendario().aMinutos(NOVIEMBRE.atDay(1));
        assertTrue(incremental.minutoColapso() >= inicioNoviembre,
                "el colapso tiene que llegar despues de cruzar dos meses: " + incremental.mensaje());

        assertEquals(completo.minutoColapso(), incremental.minutoColapso());
        assertEquals(completo.pedidoDelColapso(), incremental.pedidoDelColapso());
        MetricasSimulacion esperadas = completo.metricas();
        MetricasSimulacion obtenidas = incremental.metricas();
        assertEquals(esperadas.pedidosRegistrados(), obtenidas.pedidosRegistrados());
        assertEquals(esperadas.pedidosEntregados(), obtenidas.pedidosEntregados());
        assertEquals(esperadas.unidadesEntregadas(), obtenidas.unidadesEntregadas());
        assertEquals(esperadas.kilometrosTotales(), obtenidas.kilometrosTotales());
        assertEquals(esperadas.ejecucionesPlanificador(), obtenidas.ejecucionesPlanificador());

        assertTrue(abierto.horizonteCargado() > inicioNoviembre, "noviembre tiene que estar cargado");
        // Al empezar noviembre el motor ya pidio diciembre, que no existe: lleva un mes de ventaja.
        assertTrue(abierto.datosAgotados(), "diciembre se pide al empezar noviembre y no tiene archivo");
    }

    @Test
    @DisplayName("Sin archivo del mes siguiente, la corrida abierta termina con los datos agotados")
    void terminaAlAgotarseLosDatos(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz, SEPTIEMBRE, OCTUBRE);
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        int pedidosDisponibles = repositorio.cargar(PRIMER_DIA, OCTUBRE.atEndOfMonth()).pedidos().size();

        RepositorioDatos.CargaAbierta carga = repositorio.abrir(PRIMER_DIA);
        MotorSimulacion motor = new MotorSimulacion(carga.inicio(), configuracion(PRIMER_DIA),
                new ParametrosOperacion(), new Constructivo(), List.of(), carga.siguientes());
        ResultadoSimulacion resultado = motor.ejecutar();

        assertTrue(resultado.estado() == EstadoCorrida.DATOS_AGOTADOS
                || resultado.estado() == EstadoCorrida.COLAPSADA, resultado.mensaje());
        assertTrue(motor.datosAgotados(), "tuvo que pedir noviembre y no encontrarlo");
        assertTrue(motor.avisosDeCarga().stream().anyMatch(a -> a.contains("ventas de " + NOVIEMBRE)),
                "falta el aviso del mes sin ventas: " + motor.avisosDeCarga());
        if (resultado.estado() == EstadoCorrida.DATOS_AGOTADOS) {
            assertTrue(resultado.mensaje().contains(NOVIEMBRE.toString()), resultado.mensaje());
            assertEquals(pedidosDisponibles, resultado.metricas().pedidosRegistrados(),
                    "sin colapso, tienen que llegar todos los pedidos de los dos meses");
        }
    }

    @Test
    @DisplayName("Los meses encadenados no repiten identificadores de pedido")
    void losMesesNoRepitenIdentificadores(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz, SEPTIEMBRE, OCTUBRE);
        RepositorioDatos.CargaAbierta carga = new RepositorioDatos(raiz).abrir(PRIMER_DIA);

        RepositorioDatos.TramoMensual octubre = carga.siguientes().cargar(OCTUBRE);
        RepositorioDatos.TramoMensual noviembre = carga.siguientes().cargar(NOVIEMBRE);

        assertTrue(octubre.conVentas());
        assertFalse(octubre.pedidos().isEmpty());
        Set<Integer> ids = new HashSet<>();
        for (Pedido p : carga.inicio().pedidos()) {
            assertTrue(ids.add(p.id()));
        }
        for (Pedido p : octubre.pedidos()) {
            assertTrue(ids.add(p.id()), "identificador repetido en octubre: " + p.id());
        }
        assertFalse(noviembre.conVentas(), "noviembre no tiene archivo de ventas");
        assertTrue(noviembre.pedidos().isEmpty());
    }

    // ------------------------------------------------------------ escenario

    private static void escribirMeses(Path raiz, YearMonth... meses) throws IOException {
        GeneradorVentas ventas = new GeneradorVentas(PerfilDemanda.LIGERO.conPedidosPorDiaInicial(PEDIDOS_POR_DIA),
                SEPTIEMBRE);
        GeneradorBloqueos bloqueos = new GeneradorBloqueos(SEMILLA, 1.0);
        for (YearMonth mes : meses) {
            ventas.escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS));
            bloqueos.escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_BLOQUEOS));
            GeneradorMantenimiento.escribirPar(GeneradorMantenimiento.primerMesDelPar(mes),
                    raiz.resolve(RepositorioDatos.DIRECTORIO_MANTENIMIENTO));
        }
    }

    private static Path archivoVentas(Path raiz, YearMonth mes) {
        return raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS).resolve(LectorVentas.nombreDeArchivo(mes));
    }

    /** Colapso en reloj libre, sin averias por reglas y con presupuesto minimo. */
    private static ConfiguracionEscenario configuracion(LocalDate ultimoDia) {
        return new ConfiguracionEscenario(TipoEscenario.COLAPSO, PRIMER_DIA, ultimoDia, 240, 144_000.0,
                ModoReloj.LIBRE, "PRUEBA", SEMILLA, 60, false, 0.0, false);
    }

    /** Heuristica constructiva sin presupuesto: determinista, que es lo que exige la comparacion. */
    private static final class Constructivo implements Algoritmo {

        @Override
        public String nombre() {
            return "PRUEBA";
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            Solucion solucion = new AhorrosClarkeWright().construir(instancia, new ProgramadorRuta(instancia),
                    new Aleatorio(7));
            return new ResultadoPlanificacion(nombre(), solucion, null, 1, 1, 7, Map.of());
        }
    }
}
