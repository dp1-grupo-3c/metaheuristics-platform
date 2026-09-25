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
public final class CompararCumplimiento {
    private CompararCumplimiento() { }

    public static void main(String[] argumentos) throws Exception {
        if (argumentos.length != 8) {
            throw new IllegalArgumentException("Uso: datos algoritmo primerDia dias presupuestoMs semilla incidencias planVigente");
        }
        Path raiz = Path.of(argumentos[0]);
        String nombre = FabricaAlgoritmos.canonico(argumentos[1]);
        LocalDate inicio = LocalDate.parse(argumentos[2]);
        int dias = Integer.parseInt(argumentos[3]);
        int presupuestoMs = Integer.parseInt(argumentos[4]);
        long semilla = Long.parseLong(argumentos[5]);
        boolean incidencias = Boolean.parseBoolean(argumentos[6]);
        boolean planVigente = Boolean.parseBoolean(argumentos[7]);
        if (dias <= 0 || presupuestoMs < 50) throw new IllegalArgumentException("Dias o presupuesto invalidos");
        var cargados = new RepositorioDatos(raiz).cargar(inicio, inicio.plusDays(dias - 1));
        var datos = new RepositorioDatos.DatosEscenario(cargados.calendario(), cargados.primerDia(),
                cargados.ultimoDia(), cargados.flota(), cargados.pedidos(), cargados.bloqueos(),
                incidencias ? cargados.mantenimientos() : List.of(), cargados.avisos());
        var configuracion = new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D, inicio,
                inicio.plusDays(dias - 1), 30, 30 * 60 * 1000 * PresupuestoComputo.FRACCION_EFECTIVA / presupuestoMs,
                ModoReloj.LIBRE, nombre, semilla, 60, incidencias, 0.02, planVigente);
        var auditor = new Auditor(FabricaAlgoritmos.crear(nombre, semilla), new RegistroBloqueos(datos.bloqueos()), presupuestoMs);
        var observador = new ObservadorSimulacion() {
            private long ultimoDia = -1;
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

        Auditor(Algoritmo interno, RegistroBloqueos bloqueos, int presupuestoMs) {
            this.interno = interno;
            this.verificador = new VerificadorRestricciones(bloqueos);
            this.presupuestoMs = presupuestoMs;
        }
        @Override public String nombre() { return interno.nombre(); }
        @Override public boolean admiteArranqueDesdePlanVigente() { return interno.admiteArranqueDesdePlanVigente(); }
        @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p) {
            return comprobar(i, interno.resolver(i, p));
        }
        @Override public ResultadoPlanificacion resolver(InstanciaPlanificacion i, PresupuestoComputo p, long s) {
            return comprobar(i, interno.resolver(i, p, s));
        }
        @Override public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion i, PresupuestoComputo p, long s, Solucion v) {
            return comprobar(i, interno.resolverDesde(i, p, s, v));
        }
        private ResultadoPlanificacion comprobar(InstanciaPlanificacion instancia, ResultadoPlanificacion resultado) {
            var validez = verificador.verificar(instancia, resultado.solucion());
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
