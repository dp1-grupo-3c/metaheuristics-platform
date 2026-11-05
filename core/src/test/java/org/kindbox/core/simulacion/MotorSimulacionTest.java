package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
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
    void mantenimientoIncluyeUltimoMinutoDelDia(@TempDir Path raiz) throws IOException {
        var originales = escenario(raiz, PRIMER_DIA.plusDays(1));
        var datos = new RepositorioDatos.DatosEscenario(originales.calendario(), originales.primerDia(),
                originales.ultimoDia(), originales.flota(), List.of(), List.of(),
                List.of(new org.kindbox.core.modelo.Mantenimiento(PRIMER_DIA, "TA01")), List.of());
        Map<Long, org.kindbox.core.modelo.EstadoUnidad> estados = new HashMap<>();
        var observador = new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                if (foto.minutoSimulado() == 1439 || foto.minutoSimulado() == 1440) {
                    estados.put(foto.minutoSimulado(), foto.unidades().stream()
                            .filter(u -> u.codigo().equals("TA01")).findFirst().orElseThrow().estado());
                }
            }
        };
        new MotorSimulacion(datos, configuracionSinAverias(PRIMER_DIA.plusDays(1)),
                new ParametrosOperacion(), new PlanificadorConstructivo(), List.of(observador)).ejecutar();
        assertEquals(org.kindbox.core.modelo.EstadoUnidad.EN_MANTENIMIENTO, estados.get(1439L));
        assertEquals(org.kindbox.core.modelo.EstadoUnidad.DISPONIBLE, estados.get(1440L));
    }

    @Test
    void conservaLaPausaIniciadaAlReplanificarYLaReiniciaEnOtraJornada(@TempDir Path raiz) throws IOException {
        var originales = escenario(raiz, PRIMER_DIA);
        var datos = new RepositorioDatos.DatosEscenario(originales.calendario(), originales.primerDia(),
                originales.ultimoDia(), originales.flota(), originales.pedidos(), originales.bloqueos(),
                List.of(), originales.avisos());
        Map<Long, Boolean> alimentacion = new HashMap<>();
        Algoritmo planificador = new Algoritmo() {
            public String nombre() { return "PRUEBA_PAUSA"; }
            public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
                int unidad = instancia.indiceDeUnidad("TA01");
                alimentacion.put(instancia.minutoActual(), instancia.unidadAlimentada(unidad));
                Solucion solucion = Solucion.vacia(instancia);
                if (instancia.minutoActual() == 0) {
                    var pausa = new org.kindbox.core.problema.Parada(
                            org.kindbox.core.problema.TipoParada.ALIMENTACION, instancia.unidadNodo(unidad),
                            -1, -1, 0, 0, 60, 0);
                    var ruta = new org.kindbox.core.problema.Ruta("TA01", TipoUnidad.AUTO,
                            instancia.unidadNodo(unidad), 0, List.of(pausa));
                    solucion = new Solucion(List.of(ruta), solucion.cantidadNoAtendida(), solucion.valor());
                }
                return new ResultadoPlanificacion(nombre(), solucion, null, 0, 0, 0, Map.of());
            }
        };
        var configuracion = new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D, PRIMER_DIA,
                PRIMER_DIA, 30, FACTOR_LIBRE, ModoReloj.LIBRE, "PRUEBA_PAUSA", SEMILLA, 60, false, 0, false);
        var resultado = new MotorSimulacion(datos, configuracion, new ParametrosOperacion(),
                planificador, List.of()).ejecutar();
        assertEquals(EstadoCorrida.CULMINADA, resultado.estado());
        assertEquals(false, alimentacion.get(0L));
        assertEquals(true, alimentacion.get(30L), "la pausa en curso se hereda sin repetirla");
        assertEquals(true, alimentacion.get(90L), "la pausa terminada sigue registrada");
        assertEquals(false, alimentacion.get(420L), "la jornada de manana exige su propia pausa");
    }

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
            long finEnElLugar = tipo.trasladaAlmacenCentral() ? tipo.minutosEnElLugar() : reincorporacion;
            assertEquals(List.of(0L, finEnElLugar, reincorporacion), registro.tiemposVistos(codigo),
                    codigo + " no muestra en la fotografia los minutos de su averia");
            assertEquals(List.of(-1L, -1L, -1L), registro.tiemposAlReincorporarse(codigo),
                    codigo + " conserva los minutos de la averia ya resuelta");
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

    @Test
    @DisplayName("Cada fotografia trae los indicadores globales y el color de cada unidad")
    void laFotografiaTraeLosIndicadoresGlobales(@TempDir Path raiz) throws IOException {
        ParametrosOperacion parametros = new ParametrosOperacion();
        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, PRIMER_DIA),
                configuracionSinAverias(PRIMER_DIA), parametros, new PlanificadorConstructivo(), List.of());
        ParametrosOperacion.Instantanea vigentes = parametros.instantanea();
        List<String> errores = new ArrayList<>();
        int[] fotos = new int[2];
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                fotos[0]++;
                if (!foto.ocupacionFlota().equals(IndicadorOcupacion.deFlota(foto.unidades(), vigentes))
                        || !foto.ocupacionAlmacenes().equals(IndicadorOcupacion.deAlmacenes(foto.almacenes(), vigentes))) {
                    errores.add("indicadores del minuto " + foto.minutoSimulado());
                }
                for (VistaUnidad u : foto.unidades()) {
                    if (u.color() != ColorSemaforo.deOcupacion(u.cargaABordo(), u.capacidad(), vigentes)) {
                        errores.add(u.codigo() + " en el minuto " + foto.minutoSimulado());
                    }
                }
                if (foto.ocupacionFlota().ocupado() > 0) {
                    fotos[1]++;
                }
            }
        });

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(fotos[0] > 0, "la corrida no tomo fotografias");
        assertTrue(fotos[1] > 0, "alguna fotografia debe tener carga a bordo");
        assertTrue(errores.isEmpty(), "fotografias incoherentes: " + errores.subList(0, Math.min(5, errores.size())));
    }

    @Test
    @DisplayName("Los tiempos del tramo y el almacen de origen de cada unidad son coherentes en toda la corrida")
    void losTiemposDelTramoSonCoherentes(@TempDir Path raiz) throws IOException {
        // Salto corto y mas pedidos para que haya replanificaciones con unidades en marcha.
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA,
                PRIMER_DIA, 30, FACTOR_LIBRE, ModoReloj.LIBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                1, false, 0.0, false);
        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, PRIMER_DIA, 40.0),
                configuracion, new ParametrosOperacion(), new PlanificadorConstructivo(), List.of());
        List<String> errores = new ArrayList<>();
        Map<String, VistaUnidad> anteriores = new HashMap<>();
        Map<VistaUnidad.Fase, Integer> fases = new HashMap<>();
        Set<Long> replanificaciones = new HashSet<>();
        int[] enMarchaAlReplanificar = new int[1];
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alReplanificar(long minutoSimulado, String algoritmo, int pedidosPendientes,
                                       int pedidosNoAtendidos, double costo, long milisegundos) {
                replanificaciones.add(minutoSimulado);
            }

            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                long ahora = foto.minutoSimulado();
                for (VistaUnidad u : foto.unidades()) {
                    if (replanificaciones.contains(ahora) && u.fase() == VistaUnidad.Fase.LLEGANDO) {
                        enMarchaAlReplanificar[0]++;
                    }
                    fases.merge(u.fase(), 1, Integer::sum);
                    long salida = u.minutoSalidaTramo();
                    long llegada = u.minutoLlegadaTramo();
                    boolean bien = switch (u.fase()) {
                        case SIN_RUTA -> salida == -1L && llegada == -1L;
                        case LLEGANDO -> salida >= 0 && salida <= ahora && llegada >= ahora;
                        case SALIENDO -> salida >= ahora && (llegada == -1L || llegada >= salida);
                    };
                    if (!bien) {
                        errores.add(u.codigo() + " " + u.fase() + " " + salida + "-" + llegada + " en " + ahora);
                    }
                    if (u.cargaABordo() > 0 && (u.idAlmacenOrigen() < 0 || u.minutoSalidaAlmacen() < 0)) {
                        errores.add(u.codigo() + " lleva carga sin almacen de origen en " + ahora);
                    }
                    VistaUnidad previa = anteriores.put(u.codigo(), u);
                    // Si sigue en marcha, la salida solo cambia al partir de la parada a la que iba.
                    if (previa != null && previa.fase() == VistaUnidad.Fase.LLEGANDO
                            && u.fase() == VistaUnidad.Fase.LLEGANDO && previa.minutoSalidaTramo() != salida
                            && (u.x() != previa.destinoX() || u.y() != previa.destinoY())) {
                        errores.add(u.codigo() + " cambio su salida en marcha en " + ahora);
                    }
                }
            }
        });

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(fases.getOrDefault(VistaUnidad.Fase.LLEGANDO, 0) > 0, "ninguna unidad en camino: " + fases);
        assertTrue(fases.getOrDefault(VistaUnidad.Fase.SALIENDO, 0) > 0, "ninguna unidad por salir: " + fases);
        assertTrue(enMarchaAlReplanificar[0] > 0, "ninguna replanificacion encontro unidades en marcha");
        assertTrue(errores.isEmpty(), errores.size() + " incoherencias: " + errores.subList(0, Math.min(5, errores.size())));
    }

    @Test
    @DisplayName("Los envios de cada pedido cuadran con sus paquetes pendientes en toda la corrida")
    void losEnviosDeCadaPedidoSonCoherentes(@TempDir Path raiz) throws IOException {
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA,
                PRIMER_DIA, 30, FACTOR_LIBRE, ModoReloj.LIBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE, SEMILLA,
                1, false, 0.0, false);
        RepositorioDatos.DatosEscenario datos = escenario(raiz, PRIMER_DIA, 40.0);
        Map<Integer, Pedido> pedidos = new HashMap<>();
        datos.pedidos().forEach(p -> pedidos.put(p.id(), p));
        MotorSimulacion motor = new MotorSimulacion(datos,
                configuracion, new ParametrosOperacion(), new PlanificadorConstructivo(), List.of());
        List<String> errores = new ArrayList<>();
        int[] conEnvios = new int[1];
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                long ahora = foto.minutoSimulado();
                for (Map.Entry<Integer, List<EnvioPedido>> entrada : EnvioPedido.porPedido(foto).entrySet()) {
                    conEnvios[0]++;
                    List<EnvioPedido> envios = entrada.getValue();
                    int siguiente = envios.get(0).primerPaquete();
                    for (EnvioPedido e : envios) {
                        boolean bien = e.primerPaquete() == siguiente && e.cantidad() > 0
                                && e.ultimoPaquete() == e.primerPaquete() + e.cantidad() - 1
                                && e.minutoLlegada() >= ahora
                                && (e.minutoSalida() < 0 || e.minutoSalida() <= e.minutoLlegada())
                                && (!e.cargado() || e.idAlmacenCarga() >= 0);
                        if (!bien) {
                            errores.add("pedido " + entrada.getKey() + " " + e + " en " + ahora);
                        }
                        siguiente = e.ultimoPaquete() + 1;
                    }
                    if (envios.get(0).primerPaquete() < 1) {
                        errores.add("pedido " + entrada.getKey() + " numera desde " + envios.get(0).primerPaquete());
                    }
                    // Cada ruta acaba en el destino del pedido.
                    Pedido pedido = pedidos.get(entrada.getKey());
                    for (RutaEnvio ruta : EnvioPedido.rutas(foto, entrada.getKey())) {
                        int[] c = ruta.camino();
                        if (c.length < 2 || c[c.length - 2] != pedido.x() || c[c.length - 1] != pedido.y()) {
                            errores.add("ruta del pedido " + entrada.getKey() + " no acaba en su destino en " + ahora);
                        }
                    }
                }
            }
        });

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        assertTrue(conEnvios[0] > 0, "ninguna fotografia tuvo envios");
        // La corrida dura un dia, asi que el registro conserva todas sus entregas.
        List<EntregaRegistrada> entregas = motor.entregasRecientes();
        assertEquals(resultado.metricas().unidadesEntregadas(), entregas.stream().mapToInt(EntregaRegistrada::cantidad).sum(),
                "el registro cuadra con los paquetes entregados");
        assertTrue(entregas.stream().allMatch(e -> e.idAlmacenCarga() >= 0), "cada entrega sabe de que almacen salio");
        assertTrue(errores.isEmpty(), errores.size() + " incoherencias: " + errores.subList(0, Math.min(3, errores.size())));
    }

    @Test
    @DisplayName("Una corrida que arranca a media jornada empieza en ese minuto y cubre un dia desde ahi")
    void arrancaEnElMinutoElegido(@TempDir Path raiz) throws IOException {
        long inicio = 630L;
        RepositorioDatos.DatosEscenario cargados = escenario(raiz, PRIMER_DIA.plusDays(1));
        assertTrue(cargados.pedidos().stream().anyMatch(p -> p.minutoRegistro() < inicio),
                "el escenario debe traer pedidos antes del arranque");
        // TA02 tiene mantenimiento todo el primer dia, asi que ya esta retirada al arrancar.
        RepositorioDatos.DatosEscenario datos = new RepositorioDatos.DatosEscenario(cargados.calendario(),
                cargados.primerDia(), cargados.ultimoDia(), cargados.flota(), cargados.pedidos(), cargados.bloqueos(),
                List.of(new Mantenimiento(PRIMER_DIA, "TA02")), cargados.avisos());
        MotorSimulacion motor = new MotorSimulacion(datos, configuracionSinAverias(PRIMER_DIA).conMinutoInicial(630),
                new ParametrosOperacion(), new PlanificadorConstructivo(), List.of());
        List<InstantaneaSimulacion> primeras = new ArrayList<>();
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                if (primeras.isEmpty()) {
                    primeras.add(foto);
                }
            }
        });

        ResultadoSimulacion resultado = motor.ejecutar();

        assertEquals(EstadoCorrida.CULMINADA, resultado.estado(), resultado.mensaje());
        InstantaneaSimulacion primera = primeras.get(0);
        assertEquals(inicio, primera.minutoSimulado(), "la primera fotografia es la del arranque");
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 30), primera.fechaHoraSimulada());
        assertEquals(EstadoUnidad.EN_MANTENIMIENTO, primera.unidades().stream()
                .filter(u -> u.codigo().equals("TA02")).findFirst().orElseThrow().estado());
        assertEquals(inicio + MINUTOS_POR_DIA, resultado.minutoFinal(), "el horizonte se corre con el arranque");
        assertEquals(1.0, resultado.diasSimulados(), 1e-9);
        long esperados = datos.pedidos().stream()
                .filter(p -> p.minutoRegistro() >= inicio && p.minutoRegistro() <= inicio + MINUTOS_POR_DIA).count();
        assertEquals(esperados, resultado.metricas().pedidosRegistrados(), "solo cuentan los pedidos desde el arranque");
        assertTrue(motor.pedidosConocidos().stream().allMatch(p -> p.minutoRegistro() >= inicio),
                "los pedidos anteriores al arranque no entran en la corrida");
    }

    @Test
    @DisplayName("El reloj acompasado cuenta desde el arranque y no desde las 00:00")
    void elRelojAcompasadoCuentaDesdeElArranque(@TempDir Path raiz) throws Exception {
        // 10 ms por minuto simulado: contar desde las 00:00 haria esperar 6,3 s antes de empezar.
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, PRIMER_DIA,
                PRIMER_DIA, SALTO_MINUTOS, 6000.0, ModoReloj.ACOMPASADO, BusquedaAdaptativaVecindadAmplia.NOMBRE,
                SEMILLA, 1, false, 0.0, false, 630);
        MotorSimulacion motor = new MotorSimulacion(escenario(raiz, PRIMER_DIA.plusDays(1)), configuracion,
                new ParametrosOperacion(), new PlanificadorConstructivo(), List.of());
        CountDownLatch avanzo = new CountDownLatch(1);
        motor.agregarObservador(new ObservadorSimulacion() {
            @Override
            public void alTomarFotografia(InstantaneaSimulacion foto) {
                if (foto.minutoSimulado() >= 640L) {
                    avanzo.countDown();
                }
            }
        });
        Thread hilo = new Thread(motor::ejecutar, "simulacion-acompasada");
        hilo.start();
        try {
            assertTrue(avanzo.await(3, TimeUnit.SECONDS), "el reloj no avanzo desde el arranque");
        } finally {
            motor.cancelar();
            hilo.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(hilo.isAlive());
    }

    // -------------------------------------------------------------- escenario

    /**
     * Escribe el escenario en el directorio temporal con los generadores del paquete
     * {@code datagen} y lo carga con el repositorio, igual que una corrida real.
     */
    private static RepositorioDatos.DatosEscenario escenario(Path raiz, LocalDate ultimoDia) throws IOException {
        return escenario(raiz, ultimoDia, PEDIDOS_POR_DIA);
    }

    private static RepositorioDatos.DatosEscenario escenario(Path raiz, LocalDate ultimoDia, double pedidosPorDia)
            throws IOException {
        YearMonth mes = YearMonth.from(PRIMER_DIA);
        new GeneradorVentas(PerfilDemanda.LIGERO.conPedidosPorDiaInicial(pedidosPorDia), mes)
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
        private final Map<String, List<Long>> tiempos = new HashMap<>();
        private final Map<String, List<Long>> tiemposAlReincorporarse = new HashMap<>();

        RegistroDeAverias(Set<String> vigiladas) {
            this.vigiladas = Set.copyOf(vigiladas);
        }

        @Override
        public void alTomarFotografia(InstantaneaSimulacion instantanea) {
            for (VistaUnidad vista : instantanea.unidades()) {
                if (!vigiladas.contains(vista.codigo())) {
                    continue;
                }
                List<Long> marcas = List.of(vista.minutoInicioAveria(), vista.minutoFinAveriaEnElLugar(),
                        vista.minutoReincorporacion());
                if (vista.averiada()) {
                    tipos.put(vista.codigo(), vista.tipoAveria());
                    ultimoAveriada.put(vista.codigo(), instantanea.minutoSimulado());
                    tiempos.putIfAbsent(vista.codigo(), marcas);
                } else if (ultimoAveriada.containsKey(vista.codigo())) {
                    primeroDisponible.putIfAbsent(vista.codigo(), instantanea.minutoSimulado());
                    tiemposAlReincorporarse.putIfAbsent(vista.codigo(), marcas);
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

        List<Long> tiemposVistos(String codigo) {
            return tiempos.getOrDefault(codigo, List.of());
        }

        List<Long> tiemposAlReincorporarse(String codigo) {
            return tiemposAlReincorporarse.getOrDefault(codigo, List.of());
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
