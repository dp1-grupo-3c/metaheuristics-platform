package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.*;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.simulacion.*;

/** Corrida reproducible con verificacion de cada plan y salida CSV, sin modificar datos. */
public final class EjecutarCampana {
    private EjecutarCampana() { }

    public static void main(String[] argumentos) throws Exception {
        if (argumentos.length != 9) {
            throw new IllegalArgumentException("Uso: datos algoritmo primerDia dias presupuestoMs semilla incidencias planVigente salida");
        }
        Path raiz = Path.of(argumentos[0]);
        String nombre = FabricaAlgoritmos.canonico(argumentos[1]);
        LocalDate inicio = LocalDate.parse(argumentos[2]);
        int dias = Integer.parseInt(argumentos[3]);
        int presupuestoMs = Integer.parseInt(argumentos[4]);
        long semilla = Long.parseLong(argumentos[5]);
        boolean incidencias = Boolean.parseBoolean(argumentos[6]);
        boolean planVigente = Boolean.parseBoolean(argumentos[7]);
        Path salida = Path.of(argumentos[8]);
        java.nio.file.Files.createDirectories(salida);
        if (dias <= 0 || presupuestoMs < 50) throw new IllegalArgumentException("Dias o presupuesto invalidos");
        var cargados = new RepositorioDatos(raiz).cargar(inicio, inicio.plusDays(dias - 1));
        if (cargados.avisos().stream().anyMatch(a -> a.startsWith("No se encontro"))) {
            throw new IllegalStateException("Entrada incompleta: " + cargados.avisos());
        }
        var datos = new RepositorioDatos.DatosEscenario(cargados.calendario(), cargados.primerDia(),
                cargados.ultimoDia(), cargados.flota(), cargados.pedidos(), cargados.bloqueos(),
                incidencias ? cargados.mantenimientos() : List.of(), cargados.avisos());
        var configuracion = new ConfiguracionEscenario(dias == 1 ? TipoEscenario.DIA_A_DIA : TipoEscenario.SIMULACION_5D, inicio,
                inicio.plusDays(dias - 1), 30, 30 * 60 * 1000 * PresupuestoComputo.FRACCION_EFECTIVA / presupuestoMs,
                ModoReloj.LIBRE, nombre, semilla, 60, incidencias, 0.02, planVigente);
        var fabrica = new FabricaInstancias(datos);
        var pequena = fabrica.fotografia(360, 20, 0, new ParametrosOperacion());
        for (int repeticion = 0; repeticion < 3; repeticion++) {
            FabricaAlgoritmos.crear(nombre, semilla).resolver(pequena,
                    PresupuestoComputo.deMilisegundos(200).conReserva(50), semilla);
        }
        System.err.println(datos);
        java.nio.file.Files.writeString(salida.resolve("entrada.txt"), datos + "\n" + String.join("\n", datos.avisos()));
        var serie = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(salida.resolve("serie.csv")));
        serie.println("minuto,registrados,entregados,pendientes,incumplidos,costo,kilometros");
        var eventos = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(salida.resolve("averias.csv")));
        eventos.println("minuto,unidad,tipo,x,y,carga");
        var auditor = new Auditor(FabricaAlgoritmos.crear(nombre, semilla), new RegistroBloqueos(datos.bloqueos()), presupuestoMs, salida);
        var observador = new ObservadorSimulacion() {
            private long ultimoDia = -1;
            @Override public void alTomarFotografia(InstantaneaSimulacion foto) {
                var m = foto.metricas();
                serie.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.2f,%d%n", foto.minutoSimulado(),
                        m.pedidosRegistrados(), m.pedidosEntregados(), m.pedidosPendientes(),
                        m.pedidosIncumplidos(), m.costoAcumulado(), m.kilometrosTotales());
                serie.flush();
            }
            @Override public void alAveriarse(long minuto, String unidad, int tipo, int x, int y, int carga) {
                eventos.printf("%d,%s,%d,%d,%d,%d%n", minuto, unidad, tipo, x, y, carga);
                eventos.flush();
            }
            @Override
            public void alReplanificar(long minuto, String algoritmo, int pendientes, int noAtendidos,
                                       double costo, long milisegundos) {
                if (minuto / 1440 != ultimoDia) {
                    ultimoDia = minuto / 1440;
                    System.err.printf("%s dia=%d semilla=%d%n", algoritmo, ultimoDia + 1, semilla);
                }
            }
        };
        var motor = new MotorSimulacion(datos, configuracion, new ParametrosOperacion(), auditor, List.of(observador));
        var resultado = motor.ejecutar();
        auditor.registro.close();
        serie.close();
        eventos.close();
        var m = resultado.metricas();
        if (!m.particionCoherente()) throw new IllegalStateException("Particion de pedidos incoherente");
        System.out.println("algoritmo,primerDia,dias,presupuestoMs,semilla,incidencias,planVigente,estado,minutoFinal,registrados,entregados,pendientes,incumplidos,paquetes,kilometros,costo,minutoPrimerIncumplimiento,tiempoMs,planificaciones,planesVerificados,sobregiros,maximoPlanMs,averias,mantenimientos");
        System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d%n",
                nombre, inicio, dias, presupuestoMs, semilla, incidencias, planVigente, resultado.estado(),
                resultado.minutoFinal(), m.pedidosRegistrados(), m.pedidosEntregados(), m.pedidosPendientes(),
                m.pedidosIncumplidos(), m.unidadesEntregadas(), m.kilometrosTotales(), m.costoAcumulado(),
                resultado.minutoPrimerIncumplimiento(), resultado.milisegundosReales(), m.ejecucionesPlanificador(),
                auditor.llamadas, auditor.sobregiros, auditor.maximoMs,
                m.averiasPorTipo().values().stream().mapToInt(Integer::intValue).sum(), datos.mantenimientos().size());
    }

    private static final class Auditor implements Algoritmo {
        private final Algoritmo interno;
        private final VerificadorRestricciones verificador;
        private final int presupuestoMs;
        private long llamadas;
        private long sobregiros;
        private long maximoMs;
        private final java.io.PrintWriter registro;

        Auditor(Algoritmo interno, RegistroBloqueos bloqueos, int presupuestoMs, Path salida) throws java.io.IOException {
            this.interno = interno;
            this.verificador = new VerificadorRestricciones(bloqueos);
            this.presupuestoMs = presupuestoMs;
            registro = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(salida.resolve("planes.csv")));
            registro.println("minuto,pedidos,h,urgencia,costo,tiempoInternoMs,tiempoExternoMs,valido,tiempoCpuMs");
        }
        @Override public String nombre() { return interno.nombre(); }
        @Override public boolean admiteArranqueDesdePlanVigente() { return interno.admiteArranqueDesdePlanVigente(); }
        @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p) {
            return medir(i, () -> interno.resolver(i, p));
        }
        @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p, long s) {
            return medir(i, () -> interno.resolver(i, p, s));
        }
        @Override public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion i, PresupuestoComputo p, long s, Solucion v) {
            return medir(i, () -> interno.resolverDesde(i, p, s, v));
        }
        private ResultadoPlanificacion medir(InstanciaPlanificacion instancia,
                java.util.function.Supplier<ResultadoPlanificacion> ejecutar) {
            var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
            boolean medible = cpu.isCurrentThreadCpuTimeSupported();
            if (medible && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
            long inicioCpu = medible ? cpu.getCurrentThreadCpuTime() : 0;
            long inicioNs = System.nanoTime();
            var resultado = ejecutar.get();
            double tiempoExternoMs = (System.nanoTime() - inicioNs) / 1e6;
            double tiempoCpuMs = medible ? (cpu.getCurrentThreadCpuTime() - inicioCpu) / 1e6 : -1;
            var validez = verificador.verificar(instancia, resultado.solucion());
            var valor = resultado.solucion().valor();
            registro.printf(Locale.ROOT, "%d,%d,%d,%.12f,%.2f,%d,%.6f,%s,%.6f%n",
                    instancia.minutoActual(), instancia.cantidadPedidos(), valor.h(), valor.urgencia(),
                    valor.s(), resultado.milisegundos(), tiempoExternoMs, validez.factible(), tiempoCpuMs);
            registro.flush();
            if (!validez.factible()) throw new IllegalStateException("Plan invalido: " + validez.detalles());
            var calculado = new FuncionObjetivoJerarquica().evaluar(instancia, resultado.solucion());
            if (calculado.compareTo(resultado.solucion().valor()) != 0) throw new IllegalStateException("Objetivo inconsistente");
            llamadas++;
            maximoMs = Math.max(maximoMs, resultado.milisegundos());
            if (resultado.milisegundos() > presupuestoMs) sobregiros++;
            return resultado;
        }
    }
}
