package org.kindbox.experiments;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;
import org.kindbox.core.util.Aleatorio;

/**
 * Una corrida de la campana experimental del informe de diseno de experimentos: un
 * escenario, un nivel de instancia, una variante de algoritmo y una replica.
 *
 * <h2>Base de datos unica y niveles anidados</h2>
 * <p>Todas las corridas leen la misma base maestra congelada, generada a la capacidad diaria
 * de la flota (384 pedidos por dia, 1 536 paquetes). Cada nivel conserva una fraccion de sus
 * pedidos: un pedido entra si su numero aleatorio {@code u}, derivado de su identificador y
 * de la semilla de la replica, es menor que la fraccion del nivel. Como {@code u} no depende
 * del nivel, los niveles quedan anidados: todo pedido de N1 esta en N2 y todo pedido de N2
 * esta en N3. La replica cambia la muestra, de modo que cada replica es una instancia
 * distinta y las tres variantes resuelven exactamente la misma: ese es el emparejamiento de
 * la prueba de Wilcoxon.</p>
 * <ul>
 *   <li>N1, N2 y N3: 25, 50 y 75 % de la base, escenario de cinco dias;</li>
 *   <li>N4: rampa de 50 % el primer dia a 100 % desde el sexto, escenario de colapso, que
 *       termina en el primer pedido fuera de plazo o al agotar los 30 dias de datos.</li>
 * </ul>
 *
 * <h2>Perturbaciones</h2>
 * <p>Solo bloqueos, tal como vienen en la base. Las averias estan desactivadas y los
 * mantenimientos preventivos se descartan al cargar, para que la unica fuente de variacion
 * entre replicas sea la instancia y la unica entre variantes, el algoritmo.</p>
 *
 * <h2>Verificaciones por llamada al planificador</h2>
 * <ul>
 *   <li>V-1: el plan devuelto pasa el verificador de restricciones con los bloqueos vigentes;</li>
 *   <li>V-2: la funcion objetivo comun, reevaluada sobre el plan, da la misma H y el mismo
 *       costo que informo el algoritmo;</li>
 *   <li>V-3: la llamada, medida por fuera con reloj monotono, cabe en el presupuesto.</li>
 * </ul>
 *
 * <p>Uso: {@code CorridaCampana datos=<raiz> salida=<dir> escenario=5D|COLAPSO nivel=N1..N4
 * variante=ALNS-C|ALNS-F|HGS replica=<n> [presupuestoMs=2000] [primerDia=2026-09-01]}.
 * Escribe {@code filas/<id>.csv} con una fila y {@code series/<id>.csv} con la serie de
 * pedidos pendientes por replanificacion.</p>
 */
public final class CorridaCampana {

    /** Semilla base de las replicas: la replica r usa la semilla 20260900 + r. */
    static final long SEMILLA_BASE = 20260900L;

    private CorridaCampana() {
    }

    public static void main(String[] argumentos) throws IOException {
        Map<String, String> a = new LinkedHashMap<>();
        for (String arg : argumentos) {
            int igual = arg.indexOf('=');
            if (igual <= 0) {
                throw new IllegalArgumentException("Argumento sin forma clave=valor: " + arg);
            }
            a.put(arg.substring(0, igual), arg.substring(igual + 1));
        }
        Path raiz = Path.of(requerido(a, "datos"));
        Path salida = Path.of(requerido(a, "salida"));
        String escenario = requerido(a, "escenario").toUpperCase(Locale.ROOT);
        String nivel = requerido(a, "nivel").toUpperCase(Locale.ROOT);
        String variante = requerido(a, "variante").toUpperCase(Locale.ROOT);
        int replica = Integer.parseInt(requerido(a, "replica"));
        long presupuestoMs = Long.parseLong(a.getOrDefault("presupuestoMs", "2000"));
        LocalDate primerDia = LocalDate.parse(a.getOrDefault("primerDia", "2026-09-01"));

        boolean colapso = switch (escenario) {
            case "5D" -> false;
            case "COLAPSO" -> true;
            default -> throw new IllegalArgumentException("Escenario no admitido: " + escenario);
        };
        if (colapso != nivel.equals("N4")) {
            throw new IllegalArgumentException("N4 va con COLAPSO y N1-N3 con 5D");
        }
        String algoritmo = switch (variante) {
            case "ALNS-C", "ALNS-F" -> "ALNS";
            case "HGS" -> "HGS";
            default -> throw new IllegalArgumentException("Variante no admitida: " + variante);
        };
        boolean arranqueCalido = variante.equals("ALNS-C");
        long semilla = SEMILLA_BASE + replica;
        LocalDate ultimoDia = primerDia.plusDays(colapso ? 29 : 4);

        RepositorioDatos.DatosEscenario base = new RepositorioDatos(raiz).cargar(primerDia, ultimoDia);
        List<Pedido> muestra = muestrear(base.pedidos(), base.calendario(), nivel, semilla);
        RepositorioDatos.DatosEscenario datos = new RepositorioDatos.DatosEscenario(base.calendario(),
                base.primerDia(), base.ultimoDia(), base.flota(), muestra, base.bloqueos(), List.of(),
                base.avisos());

        // K tal que el 60 % de SA/K sea el presupuesto pedido, igual que PresupuestoComputo.
        double factor = 30 * 60 * PresupuestoComputo.FRACCION_EFECTIVA * 1000.0 / presupuestoMs;
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(
                colapso ? TipoEscenario.COLAPSO : TipoEscenario.SIMULACION_5D, primerDia, ultimoDia,
                30, factor, ModoReloj.LIBRE, algoritmo, semilla, 1440, false, 0.0, arranqueCalido);

        Medidor medidor = new Medidor(FabricaAlgoritmos.crear(algoritmo, semilla),
                new VerificadorRestricciones(new RegistroBloqueos(datos.bloqueos())));
        // Con -DdiagnosticoPedidos=true se clasifica al cierre cada pedido incumplido.
        DiagnosticoPedidos diagnostico = DiagnosticoPedidos.activo()
                ? new DiagnosticoPedidos(medidor, muestra) : null;
        MotorSimulacion motor = new MotorSimulacion(datos, configuracion, new ParametrosOperacion(),
                diagnostico == null ? medidor : diagnostico,
                diagnostico == null ? List.of(medidor) : List.of(medidor, diagnostico));
        long inicio = System.nanoTime();
        ResultadoSimulacion r = motor.ejecutar();
        double paredS = (System.nanoTime() - inicio) / 1e9;
        if (diagnostico != null) {
            diagnostico.publicar(motor.estado(), r.minutoFinal());
        }

        String id = String.format(Locale.ROOT, "%s_%s_%s_r%02d", escenario, nivel, variante, replica);
        Map<String, Object> fila = new LinkedHashMap<>();
        MetricasSimulacion m = r.metricas();
        int cerrados = m.pedidosEntregados() + m.pedidosIncumplidos();
        fila.put("id", id);
        fila.put("escenario", escenario);
        fila.put("nivel", nivel);
        fila.put("fraccion", nivel.equals("N4") ? "rampa" : String.valueOf(fraccion(nivel, 0)));
        fila.put("variante", variante);
        fila.put("algoritmo", algoritmo);
        fila.put("arranque", arranqueCalido ? "calido" : "constructivo");
        fila.put("replica", replica);
        fila.put("semilla", semilla);
        fila.put("presupuesto_ms", presupuestoMs);
        fila.put("commit", System.getProperty("campana.commit", "?"));
        fila.put("estado", r.estado());
        fila.put("minuto_final", r.minutoFinal());
        fila.put("pedidos_base", base.pedidos().size());
        fila.put("pedidos_instancia", muestra.size());
        fila.put("pedidos_registrados", m.pedidosRegistrados());
        fila.put("pedidos_entregados", m.pedidosEntregados());
        fila.put("pedidos_incumplidos", m.pedidosIncumplidos());
        fila.put("pedidos_pendientes", m.pedidosPendientes());
        fila.put("pct_cumplimiento", cerrados == 0 ? 100.0 : 100.0 * m.pedidosEntregados() / cerrados);
        fila.put("minuto_primer_incumplido", r.minutoPrimerIncumplimiento());
        fila.put("paquetes_entregados", m.unidadesEntregadas());
        fila.put("costo_total", m.costoAcumulado());
        fila.put("km_total", m.kilometrosTotales());
        fila.put("costo_por_pedido", m.pedidosEntregados() == 0 ? 0.0 : m.costoAcumulado() / m.pedidosEntregados());
        fila.put("llamadas", medidor.tiempos.size());
        fila.put("llamadas_fuera_presupuesto", medidor.fueraDePresupuesto);
        fila.put("ms_mediana", percentil(medidor.tiempos, 0.5));
        fila.put("ms_p90", percentil(medidor.tiempos, 0.9));
        fila.put("ms_max", percentil(medidor.tiempos, 1.0));
        fila.put("v1_invalidas", medidor.invalidasV1);
        fila.put("v2_incoherentes", medidor.incoherentesV2);
        fila.put("saturacion_almacenes_pct", medidor.saturacionMaxima);
        fila.put("tiempo_pared_s", paredS);
        escribir(salida.resolve("filas").resolve(id + ".csv"), List.of(fila));
        escribirSerie(salida.resolve("series").resolve(id + ".csv"), medidor.serie);
        System.out.println(id + " " + fila);
    }

    /** Fraccion de la base que conserva el nivel para un pedido registrado en el dia dado. */
    static double fraccion(String nivel, long dia) {
        return switch (nivel) {
            case "N1" -> 0.25;
            case "N2" -> 0.50;
            case "N3" -> 0.75;
            case "N4" -> Math.min(1.0, 0.50 + 0.10 * Math.max(0L, dia));
            default -> throw new IllegalArgumentException("Nivel no admitido: " + nivel);
        };
    }

    /**
     * Submuestra anidada: el numero {@code u} de cada pedido depende solo de su identificador
     * y de la semilla de la replica, de modo que subir de nivel solo agrega pedidos.
     */
    static List<Pedido> muestrear(List<Pedido> pedidos, CalendarioEscenario calendario, String nivel,
                                  long semilla) {
        List<Pedido> muestra = new ArrayList<>();
        for (Pedido p : pedidos) {
            double u = new Aleatorio(Aleatorio.derivarSemilla(semilla, p.id())).siguienteDouble();
            long dia = Math.floorDiv(p.minutoRegistro(), CalendarioEscenario.MINUTOS_POR_DIA);
            if (u < fraccion(nivel, dia)) {
                muestra.add(p);
            }
        }
        return muestra;
    }

    /** Decorador que mide cada llamada por fuera y verifica el plan que devuelve. */
    private static final class Medidor implements Algoritmo, ObservadorSimulacion {
        private final Algoritmo interno;
        private final VerificadorRestricciones verificador;
        private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();
        final List<Double> tiempos = new ArrayList<>();
        final List<long[]> serie = new ArrayList<>();
        int fueraDePresupuesto;
        int invalidasV1;
        int incoherentesV2;
        double saturacionMaxima;

        Medidor(Algoritmo interno, VerificadorRestricciones verificador) {
            this.interno = interno;
            this.verificador = verificador;
        }

        @Override
        public String nombre() {
            return interno.nombre();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p) {
            long t = System.nanoTime();
            return anotar(i, p, interno.resolver(i, p), t);
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p, long semilla) {
            long t = System.nanoTime();
            return anotar(i, p, interno.resolver(i, p, semilla), t);
        }

        @Override
        public boolean admiteArranqueDesdePlanVigente() {
            return interno.admiteArranqueDesdePlanVigente();
        }

        @Override
        public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion i, PresupuestoComputo p, long semilla,
                                                    Solucion plan) {
            long t = System.nanoTime();
            return anotar(i, p, interno.resolverDesde(i, p, semilla, plan), t);
        }

        private ResultadoPlanificacion anotar(InstanciaPlanificacion i, PresupuestoComputo p,
                                              ResultadoPlanificacion r, long inicio) {
            double ms = (System.nanoTime() - inicio) / 1e6;
            tiempos.add(ms);
            if (ms > p.milisegundosTotales()) {
                fueraDePresupuesto++;
            }
            Solucion s = r.solucion();
            if (!verificador.verificar(i, s).factible()) {
                invalidasV1++;
            }
            ValorObjetivo v = objetivo.evaluar(i, s);
            if (v.h() != s.h() || Math.abs(v.costo() - s.costo()) > 1e-6) {
                incoherentesV2++;
            }
            for (int a = 0; a < i.cantidadAlmacenes(); a++) {
                if (!i.almacenEsCentral(a)) {
                    saturacionMaxima = Math.max(saturacionMaxima, 100.0 * (1.0 - i.almacenInventario(a) / 1000.0));
                }
            }
            serie.add(new long[] {i.minutoActual(), i.cantidadPedidos(), s.h()});
            return r;
        }
    }

    private static double percentil(List<Double> valores, double q) {
        if (valores.isEmpty()) {
            return 0.0;
        }
        List<Double> orden = new ArrayList<>(valores);
        Collections.sort(orden);
        int k = (int) Math.ceil(q * orden.size()) - 1;
        return orden.get(Math.max(0, Math.min(orden.size() - 1, k)));
    }

    private static void escribir(Path archivo, List<Map<String, Object>> filas) throws IOException {
        Files.createDirectories(archivo.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(archivo, StandardCharsets.UTF_8))) {
            w.println(String.join(",", filas.get(0).keySet()));
            for (Map<String, Object> f : filas) {
                List<String> celdas = new ArrayList<>();
                for (Object v : f.values()) {
                    celdas.add(v instanceof Double d ? String.format(Locale.ROOT, "%.3f", d) : String.valueOf(v));
                }
                w.println(String.join(",", celdas));
            }
        }
    }

    private static void escribirSerie(Path archivo, List<long[]> serie) throws IOException {
        Files.createDirectories(archivo.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(archivo, StandardCharsets.UTF_8))) {
            w.println("minuto,pedidos_en_fotografia,h");
            for (long[] f : serie) {
                w.println(f[0] + "," + f[1] + "," + f[2]);
            }
        }
    }

    private static String requerido(Map<String, String> a, String clave) {
        String v = a.get(clave);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Falta el argumento " + clave + "=...");
        }
        return v;
    }
}
