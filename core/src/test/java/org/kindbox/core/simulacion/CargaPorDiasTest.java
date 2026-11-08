package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.io.CalendarioEscenario;
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

// La 5D recibe los pedidos de a un dia y tiene que terminar igual que con el rango cargado entero.
class CargaPorDiasTest {

    // El rango cruza de mes para probar la numeracion de los ids entre archivos.
    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 9, 28);
    private static final LocalDate ULTIMO_DIA = PRIMER_DIA.plusDays(4);
    private static final YearMonth SEPTIEMBRE = YearMonth.of(2026, 9);
    private static final YearMonth OCTUBRE = YearMonth.of(2026, 10);
    private static final long SEMILLA = 20261108L;

    @Test
    @DisplayName("Los dias encadenados dan los mismos pedidos, con los mismos ids, que la carga completa")
    void losDiasRepitenLaCargaCompleta(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz);
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        List<Pedido> completos = repositorio.cargar(PRIMER_DIA, ULTIMO_DIA).pedidos();

        RepositorioDatos.CargaPorDias carga = repositorio.abrirPorDias(PRIMER_DIA, ULTIMO_DIA);
        List<Pedido> porDias = new ArrayList<>(carga.inicio().pedidos());
        for (LocalDate dia = PRIMER_DIA.plusDays(1); !dia.isAfter(ULTIMO_DIA); dia = dia.plusDays(1)) {
            porDias.addAll(carga.siguientes().cargar(dia).pedidos());
        }

        assertFalse(completos.isEmpty());
        assertTrue(carga.inicio().pedidos().size() < completos.size(), "el primer bloque solo trae su dia");
        assertEquals(completos, porDias);
        assertEquals(PRIMER_DIA, carga.inicio().primerDia());
        assertEquals(ULTIMO_DIA, carga.inicio().ultimoDia());
        assertThrows(IllegalArgumentException.class, () -> carga.siguientes().cargar(ULTIMO_DIA.plusDays(1)));
    }

    @Test
    @DisplayName("Un mes sin archivo de ventas deja sus dias vacios y avisa una sola vez")
    void mesSinVentas(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz);
        Files.delete(raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS).resolve(LectorVentas.nombreDeArchivo(OCTUBRE)));
        RepositorioDatos.CargaPorDias carga = new RepositorioDatos(raiz).abrirPorDias(PRIMER_DIA, ULTIMO_DIA);

        RepositorioDatos.TramoDiario primero = carga.siguientes().cargar(OCTUBRE.atDay(1));
        RepositorioDatos.TramoDiario segundo = carga.siguientes().cargar(OCTUBRE.atDay(2));

        assertTrue(primero.pedidos().isEmpty());
        assertEquals(1, primero.avisos().size(), primero.avisos().toString());
        assertTrue(segundo.avisos().isEmpty(), "el aviso del mes no se repite cada dia");
    }

    @Test
    @DisplayName("La 5D por dias termina igual que la carga completa y nunca tiene mas de dos dias de pedidos")
    void terminaIgualConDosDiasEnMemoria(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz);
        compararCorridas(raiz, 0);
    }

    @Test
    @DisplayName("Con arranque a media jornada, la carga por dias sigue igual a la completa")
    void arranqueAMediaJornada(@TempDir Path raiz) throws IOException {
        escribirMeses(raiz);
        compararCorridas(raiz, 630);
    }

    private static void compararCorridas(Path raiz, int minutoInicial) throws IOException {
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        LocalDate hasta = minutoInicial > 0 ? ULTIMO_DIA.plusDays(1) : ULTIMO_DIA;
        ConfiguracionEscenario configuracion = configuracion().conMinutoInicial(minutoInicial);
        RepositorioDatos.DatosEscenario todos = repositorio.cargar(PRIMER_DIA, hasta);

        ResultadoSimulacion completo = new MotorSimulacion(todos, configuracion, new ParametrosOperacion(),
                new Constructivo(), List.of()).ejecutar();

        RepositorioDatos.CargaPorDias carga = repositorio.abrirPorDias(PRIMER_DIA, hasta);
        AtomicReference<MotorSimulacion> referencia = new AtomicReference<>();
        List<String> excesos = new ArrayList<>();
        CalendarioEscenario calendario = carga.inicio().calendario();
        ObservadorSimulacion vigia = new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion instantanea) {
                // Solo puede conocer los pedidos de su dia y del siguiente.
                long finDelSiguiente = (instantanea.minutoSimulado() / CalendarioEscenario.MINUTOS_POR_DIA + 2)
                        * CalendarioEscenario.MINUTOS_POR_DIA;
                long permitidos = todos.pedidos().stream().filter(p -> p.minutoRegistro() < finDelSiguiente).count();
                int conocidos = referencia.get().estado().cantidadPedidos();
                if (conocidos > permitidos) {
                    excesos.add(calendario.textoCorto(instantanea.minutoSimulado()) + ": " + conocidos
                            + " > " + permitidos);
                }
            }
        };
        MotorSimulacion porDias = new MotorSimulacion(carga.inicio(), configuracion, new ParametrosOperacion(),
                new Constructivo(), List.of(vigia), carga.siguientes());
        referencia.set(porDias);
        assertTrue(porDias.cargaPorDias());
        assertTrue(porDias.estado().cantidadPedidos() < todos.pedidos().size(), "arranca solo con el primer dia");
        ResultadoSimulacion incremental = porDias.ejecutar();

        assertTrue(excesos.isEmpty(), "pedidos cargados antes de tiempo: " + excesos);
        assertEquals(completo.estado(), incremental.estado(), incremental.mensaje());
        assertEquals(completo.minutoFinal(), incremental.minutoFinal());
        MetricasSimulacion esperadas = completo.metricas();
        MetricasSimulacion obtenidas = incremental.metricas();
        assertTrue(esperadas.pedidosRegistrados() > 0);
        assertEquals(esperadas.pedidosRegistrados(), obtenidas.pedidosRegistrados());
        assertEquals(esperadas.pedidosEntregados(), obtenidas.pedidosEntregados());
        assertEquals(esperadas.unidadesEntregadas(), obtenidas.unidadesEntregadas());
        assertEquals(esperadas.kilometrosTotales(), obtenidas.kilometrosTotales());
        assertEquals(esperadas.pedidosIncumplidos(), obtenidas.pedidosIncumplidos());
        assertEquals(esperadas.ejecucionesPlanificador(), obtenidas.ejecucionesPlanificador());
    }

    private static void escribirMeses(Path raiz) throws IOException {
        GeneradorVentas ventas = new GeneradorVentas(PerfilDemanda.LIGERO.conPedidosPorDiaInicial(8.0), SEPTIEMBRE);
        GeneradorBloqueos bloqueos = new GeneradorBloqueos(SEMILLA, 1.0);
        for (YearMonth mes : List.of(SEPTIEMBRE, OCTUBRE)) {
            ventas.escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS));
            bloqueos.escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_BLOQUEOS));
            GeneradorMantenimiento.escribirPar(GeneradorMantenimiento.primerMesDelPar(mes),
                    raiz.resolve(RepositorioDatos.DIRECTORIO_MANTENIMIENTO));
        }
    }

    // 5D en reloj libre, sin averias por reglas, para que las dos corridas sean deterministas.
    private static ConfiguracionEscenario configuracion() {
        return new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D, PRIMER_DIA, ULTIMO_DIA, 60, 144_000.0,
                ModoReloj.LIBRE, "PRUEBA", SEMILLA, 30, false, 0.0, false);
    }

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
