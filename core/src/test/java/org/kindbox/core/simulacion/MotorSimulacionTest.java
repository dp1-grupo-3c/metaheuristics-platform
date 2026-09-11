package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.io.LectorFlota;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.util.Aleatorio;

/**
 * Motor de simulacion dirigido por eventos sobre un escenario pequeno generado en un
 * directorio temporal.
 *
 * <p>El escenario se escribe con los generadores del paquete {@code datagen} y se carga con
 * {@link RepositorioDatos}, de modo que la prueba recorre el mismo camino que una corrida real
 * (archivos de ventas, de bloqueos y de mantenimiento preventivo, mas la composicion de la
 * flota) sin depender del directorio {@code data}, que no se versiona. El reloj corre en
 * {@link ModoReloj#LIBRE} con un factor de aceleracion alto a proposito: el presupuesto por
 * ejecucion del planificador queda en el minimo del apartado 2.3 y la corrida entera cuesta
 * menos de un segundo.</p>
 *
 * <p>Se comprueban cuatro comportamientos que no se ven en una prueba de componente: que la
 * corrida culmina con el reparto de pedidos coherente, que una cancelacion desde otro hilo la
 * cierra con estado {@link EstadoCorrida#CANCELADA}, que una averia registrada a mano de cada
 * uno de los tres tipos reincorpora la unidad exactamente en el minuto que dicta
 * {@link TipoAveria#minutoReincorporacion(long)}, y que un cambio de velocidad en caliente
 * rige a partir de la siguiente replanificacion y no de la que esta en curso, que es la
 * semantica de la respuesta 16 del cuestionario.</p>
 */
class MotorSimulacionTest {

    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 9, 1);
    private static final long SEMILLA = 20260911L;
    /** Factor de aceleracion alto a proposito: deja el presupuesto por llamada en el minimo. */
    private static final double FACTOR_LIBRE = 144_000.0;
    /** Factor lento para la prueba de cancelacion: sin cancelar, la corrida no terminaria. */
    private static final double FACTOR_ACOMPASADO = 60.0;
    private static final int SALTO_MINUTOS = 240;
    private static final int MINUTOS_POR_DIA = 1440;
    /** Demanda diaria del escenario de prueba, suficiente para que la flota entregue y quede trabajo. */
    private static final double PEDIDOS_POR_DIA = 8.0;
    /** Flota reducida: dos autos, dos motos y una bicicleta. */
    private static final List<String> FLOTA = List.of("TA01", "TA02", "TM01", "TM02", "TB01");
    /** Velocidad a la que se baja el auto en caliente, muy por debajo de los 40 Km/h del enunciado. */
    private static final double VELOCIDAD_NUEVA = 10.0;

    @Test
    @DisplayName("La corrida de dos dias culmina el horizonte con el reparto de pedidos coherente")
    void laCorridaCulminaConLaParticionCoherente(@TempDir Path raiz) throws IOException {
        LocalDate ultimoDia = PRIMER_DIA.plusDays(1);
        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, ultimoDia),
                configuracionLibre(ultimoDia, 60), new ParametrosOperacion(),
                new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA), List.of());

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertEquals(2L * MINUTOS_POR_DIA, resultado.minutoFinal());
        MetricasSimulacion metricas = resultado.metricas();
        assertTrue(metricas.particionCoherente(), "reparto de pedidos incoherente: " + metricas);
        assertTrue(metricas.pedidosRegistrados() > 0, "el escenario no trajo pedidos");
        assertTrue(metricas.pedidosEntregados() > 0, "la corrida no entrego nada");
        assertTrue(metricas.kilometrosTotales() > 0, "ninguna unidad se movio");
        assertTrue(metricas.ejecucionesPlanificador() >= 2L * MINUTOS_POR_DIA / SALTO_MINUTOS,
                "faltan replanificaciones periodicas: " + metricas.ejecucionesPlanificador());
        assertTrue(metricas.pedidosConEntregaParcial()
                        <= metricas.pedidosPendientes() + metricas.pedidosIncumplidos(),
                "los parciales son un subconjunto de pendientes e incumplidos");
        assertEquals(EstadoCorrida.CULMINADA, motor.estadoCorrida());
    }

    @Test
    @DisplayName("Una cancelacion desde otro hilo cierra la corrida con estado CANCELADA")
    void cancelarDesdeOtroHiloTerminaLaCorrida(@TempDir Path raiz) throws Exception {
        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, PRIMER_DIA),
                configuracionAcompasada(PRIMER_DIA), new ParametrosOperacion(),
                new PlanificadorConstructivo(), List.of());
        CountDownLatch arranco = new CountDownLatch(1);
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion instantanea) {
                arranco.countDown();
            }
        });

        Thread hilo = new Thread(motor::ejecutar, "simulacion-de-prueba");
        hilo.start();
        assertTrue(arranco.await(10, TimeUnit.SECONDS), "la corrida no llego a arrancar");
        motor.cancelar();
        hilo.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(hilo.isAlive(), "la corrida no atendio la cancelacion");
        assertTrue(motor.cancelado());
        assertEquals(EstadoCorrida.CANCELADA, motor.estadoCorrida());
        ResultadoSimulacion resultado = motor.resultado();
        assertNotNull(resultado, "una corrida cancelada tambien publica su resumen");
        assertEquals(EstadoCorrida.CANCELADA, resultado.estado());
        assertTrue(resultado.minutoFinal() < (long) MINUTOS_POR_DIA,
                "la cancelacion tiene que llegar antes del cierre del horizonte");
    }

    @Test
    @DisplayName("Una averia manual de cada tipo reincorpora la unidad en el minuto que dicta TipoAveria")
    void laAveriaManualReincorporaEnElMinutoDeSuTipo(@TempDir Path raiz) throws IOException {
        LocalDate ultimoDia = PRIMER_DIA.plusDays(2);
        Map<String, TipoAveria> averias = new LinkedHashMap<>();
        averias.put("TA02", TipoAveria.TIPO_1);
        averias.put("TM01", TipoAveria.TIPO_2);
        averias.put("TM02", TipoAveria.TIPO_3);

        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, ultimoDia),
                configuracionSinAverias(ultimoDia), new ParametrosOperacion(),
                new PlanificadorConstructivo(), List.of());
        RegistroDeAverias registro = new RegistroDeAverias(averias.keySet());
        motor.agregarObservador(registro);
        averias.forEach(motor::registrarAveria);

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        averias.forEach((codigo, tipo) -> {
            // Las averias se encolan antes de arrancar, de modo que se aplican en el minuto cero.
            long reincorporacion = tipo.minutoReincorporacion(0L);
            assertEquals(tipo.codigo(), registro.tipoVisto(codigo),
                    codigo + " no aparece averiada con su tipo en la fotografia");
            assertEquals(reincorporacion - 1L, registro.ultimoMinutoAveriada(codigo),
                    codigo + " deja de estar averiada antes de tiempo");
            assertEquals(reincorporacion, registro.primerMinutoDisponible(codigo),
                    codigo + " no se reincorpora en el minuto que dicta " + tipo);
        });
        Map<Integer, Integer> porTipo = resultado.metricas().averiasPorTipo();
        for (TipoAveria tipo : TipoAveria.values()) {
            assertEquals(1, porTipo.get(tipo.codigo()), "averias contabilizadas del " + tipo);
        }
    }

    @Test
    @DisplayName("Un cambio de velocidad en caliente rige a partir de la siguiente replanificacion")
    void elCambioDeVelocidadRigeDesdeLaSiguienteReplanificacion(@TempDir Path raiz) throws IOException {
        ParametrosOperacion parametros = new ParametrosOperacion();
        PlanificadorConstructivo planificador = new PlanificadorConstructivo();
        CambioDeVelocidadEnCaliente cambio = new CambioDeVelocidadEnCaliente(parametros, 2);

        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, PRIMER_DIA),
                configuracionLibre(PRIMER_DIA, MINUTOS_POR_DIA), parametros, planificador, List.of(cambio));
        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(cambio.aplicado(), "el cambio de velocidad no llego a hacerse");
        assertEquals(VELOCIDAD_NUEVA, parametros.velocidad(TipoUnidad.AUTO), 0.0);

        List<Double> vistas = planificador.velocidadesVistas();
        assertTrue(vistas.size() >= 4, "hacen falta varias replanificaciones: " + vistas);
        for (int i = 0; i < vistas.size(); i++) {
            double esperada = i < 2 ? TipoUnidad.AUTO.velocidadPorDefecto() : VELOCIDAD_NUEVA;
            assertEquals(esperada, vistas.get(i), 0.0, "velocidad de la replanificacion numero " + (i + 1));
        }
    }

    // -------------------------------------------------------------- escenario

    /**
     * Escribe el escenario en el directorio temporal con los generadores del paquete
     * {@code datagen} y lo carga con el repositorio, igual que una corrida real.
     */
    private static RepositorioDatos.DatosEscenario escenario(Path raiz, LocalDate ultimoDia) throws IOException {
        YearMonth mes = YearMonth.from(PRIMER_DIA);
        new GeneradorVentas(PerfilDemanda.LIGERO.conPedidosPorDiaInicial(PEDIDOS_POR_DIA), mes)
                .escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS));
        new GeneradorBloqueos(SEMILLA, 1.0)
                .escribirMes(mes, raiz.resolve(RepositorioDatos.DIRECTORIO_BLOQUEOS));
        GeneradorMantenimiento.escribirPar(GeneradorMantenimiento.primerMesDelPar(mes),
                raiz.resolve(RepositorioDatos.DIRECTORIO_MANTENIMIENTO));
        Files.writeString(raiz.resolve(LectorFlota.NOMBRE_ARCHIVO), String.join("\n", FLOTA) + "\n");

        RepositorioDatos.DatosEscenario datos = new RepositorioDatos(raiz).cargar(PRIMER_DIA, ultimoDia);
        assertEquals(FLOTA.size(), datos.unidades().size(), "la flota del escenario de prueba");
        assertFalse(datos.pedidos().isEmpty(), "el escenario de prueba tiene que traer pedidos");
        return datos;
    }

    /** Configuracion en modo libre con presupuesto minimo por replanificacion. */
    private static ConfiguracionEscenario configuracionLibre(LocalDate ultimoDia, int minutosEntreFotografias) {
        return new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA, ultimoDia, SALTO_MINUTOS,
                FACTOR_LIBRE, ModoReloj.LIBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                minutosEntreFotografias, true, 0.02, false);
    }

    /** La misma configuracion sin averias por reglas, para que solo haya las registradas a mano. */
    private static ConfiguracionEscenario configuracionSinAverias(LocalDate ultimoDia) {
        return new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA, ultimoDia, SALTO_MINUTOS,
                FACTOR_LIBRE, ModoReloj.LIBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                1, false, 0.0, false);
    }

    /** Configuracion acompasada al reloj de pared, en la que la corrida no termina sin cancelarla. */
    private static ConfiguracionEscenario configuracionAcompasada(LocalDate ultimoDia) {
        return new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA, ultimoDia, SALTO_MINUTOS,
                FACTOR_ACOMPASADO, ModoReloj.ACOMPASADO, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                1, false, 0.0, false);
    }

    // ------------------------------------------------------------ colaboradores

    /**
     * Planificador minimo que solo corre la heuristica constructiva comun. El motor es lo que
     * se prueba aqui, de modo que una busqueda completa solo anadiria segundos de reloj; de
     * paso deja anotada la velocidad del auto que traia cada fotografia.
     */
    private static final class PlanificadorConstructivo implements Algoritmo {

        private final AhorrosClarkeWright heuristica = new AhorrosClarkeWright();
        private final List<Double> velocidades = new ArrayList<>();

        @Override
        public String nombre() {
            return heuristica.nombre();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
            return resolver(instancia, presupuesto, SEMILLA);
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                               long semilla) {
            velocidades.add(instancia.parametros().velocidad(TipoUnidad.AUTO));
            Solucion plan = heuristica.construir(instancia, new ProgramadorRuta(instancia),
                    new Aleatorio(semilla), presupuesto);
            presupuesto.cerrarPerfil(plan.valor());
            return new ResultadoPlanificacion(nombre(), plan, presupuesto.perfil(),
                    presupuesto.milisegundosTranscurridos(), presupuesto.iteraciones(), semilla, Map.of());
        }

        /** Velocidad del auto que traia la fotografia de cada replanificacion, en orden. */
        List<Double> velocidadesVistas() {
            return List.copyOf(velocidades);
        }
    }

    /**
     * Anota, para cada unidad vigilada, el ultimo minuto en que la fotografia la muestra
     * averiada y el primero en que ya no lo esta. Como {@code FIN_AVERIA} se procesa antes que
     * {@code FOTOGRAFIA} dentro del mismo minuto, la primera fotografia sin averia es la del
     * minuto exacto de reincorporacion.
     */
    private static final class RegistroDeAverias implements ObservadorSimulacion {

        private final Set<String> vigiladas;
        private final Map<String, Long> ultimoAveriada = new HashMap<>();
        private final Map<String, Long> primeroDisponible = new HashMap<>();
        private final Map<String, Integer> tipos = new HashMap<>();

        RegistroDeAverias(Set<String> vigiladas) {
            this.vigiladas = Set.copyOf(vigiladas);
        }

        @Override
        public void alTomarFotografia(InstantaneaSimulacion instantanea) {
            for (VistaUnidad vista : instantanea.unidades()) {
                if (!vigiladas.contains(vista.codigo())) {
                    continue;
                }
                if (vista.averiada()) {
                    tipos.put(vista.codigo(), vista.tipoAveria());
                    ultimoAveriada.put(vista.codigo(), instantanea.minutoSimulado());
                } else if (ultimoAveriada.containsKey(vista.codigo())) {
                    primeroDisponible.putIfAbsent(vista.codigo(), instantanea.minutoSimulado());
                }
            }
        }

        int tipoVisto(String codigo) {
            return tipos.getOrDefault(codigo, 0);
        }

        long ultimoMinutoAveriada(String codigo) {
            return ultimoAveriada.getOrDefault(codigo, -1L);
        }

        long primerMinutoDisponible(String codigo) {
            return primeroDisponible.getOrDefault(codigo, -1L);
        }
    }

    /**
     * Cambia la velocidad del auto desde otro hilo en cuanto se cierra la replanificacion
     * indicada. El hilo se espera dentro del aviso, de modo que el cambio queda hecho antes de
     * que el motor arranque la iteracion siguiente y la prueba no depende de una carrera.
     */
    private static final class CambioDeVelocidadEnCaliente implements ObservadorSimulacion {

        private final ParametrosOperacion parametros;
        private final int replanificacionQueLoDispara;
        private int replanificaciones;
        private volatile boolean aplicado;

        CambioDeVelocidadEnCaliente(ParametrosOperacion parametros, int replanificacionQueLoDispara) {
            this.parametros = parametros;
            this.replanificacionQueLoDispara = replanificacionQueLoDispara;
        }

        @Override
        public void alReplanificar(long minutoSimulado, String algoritmo, int pedidosPendientes,
                                   int pedidosNoAtendidos, double costo, long milisegundos) {
            replanificaciones++;
            if (replanificaciones != replanificacionQueLoDispara || aplicado) {
                return;
            }
            Thread otro = new Thread(() -> parametros.velocidad(TipoUnidad.AUTO, VELOCIDAD_NUEVA),
                    "cambio-de-velocidad");
            otro.start();
            try {
                otro.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("el cambio de velocidad quedo a medias", e);
            }
            aplicado = true;
        }

        boolean aplicado() {
            return aplicado;
        }
    }
}
