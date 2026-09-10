package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PerfilConvergencia;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Banco de pruebas de una sola fotografia. Ejecuta la heuristica constructiva y los dos
 * algoritmos sobre la misma instancia y contrasta sus salidas.
 *
 * <p>Comprueba en el mismo paso tres de las cuatro verificaciones de validez del apartado
 * 12.4 del ISA: factibilidad de toda solucion devuelta, equivalencia del modelo entre
 * ambos algoritmos y respeto del presupuesto de reloj de pared. La cuarta, la monotonia,
 * se lee del perfil de convergencia que devuelve cada ejecucion.</p>
 *
 * <p>Uso: {@code BancoDePruebas <raizDatos> <primerDia> <ultimoDia> <minuto> [msPresupuesto...]}</p>
 */
public final class BancoDePruebas {

    private BancoDePruebas() {
    }

    public static void main(String[] argumentos) throws Exception {
        Path raiz = Path.of(argumentos.length > 0 ? argumentos[0] : "data");
        LocalDate primerDia = LocalDate.parse(argumentos.length > 1 ? argumentos[1] : "2026-09-01");
        LocalDate ultimoDia = LocalDate.parse(argumentos.length > 2 ? argumentos[2] : "2026-09-07");
        long minuto = argumentos.length > 3 ? Long.parseLong(argumentos[3]) : 5700L;
        long[] presupuestos = argumentos.length > 4
                ? java.util.Arrays.stream(argumentos, 4, argumentos.length).mapToLong(Long::parseLong).toArray()
                : new long[]{2000L, 15000L};

        var datos = new RepositorioDatos(raiz).cargar(primerDia, ultimoDia);
        var fabrica = new FabricaInstancias(datos);
        var parametros = new ParametrosOperacion();
        InstanciaPlanificacion instancia = fabrica.fotografia(minuto, 0, 0, parametros);

        System.out.printf(Locale.ROOT,
                "Fotografia en el minuto %d: %d pedidos pendientes, demanda %d, %d unidades, capacidad de flota %d%n",
                minuto, instancia.cantidadPedidos(), instancia.demandaTotal(),
                instancia.cantidadUnidades(), instancia.capacidadFlota());

        var objetivo = new FuncionObjetivoJerarquica();
        var verificador = new VerificadorRestricciones(fabrica.bloqueos());

        // Heuristica constructiva, que es la linea base del criterio de cierre de la etapa 3.
        long t0 = System.nanoTime();
        Solucion base = new AhorrosClarkeWright()
                .construir(instancia, new ProgramadorRuta(instancia), new Aleatorio(1L));
        long msBase = (System.nanoTime() - t0) / 1_000_000L;
        informar("Clarke-Wright", base, msBase, 0, null, instancia, objetivo, verificador);
        double referencia = base.valor().costo();

        for (long ms : presupuestos) {
            for (Algoritmo algoritmo : List.<Algoritmo>of(
                    new BusquedaGeneticaHibrida(new AhorrosClarkeWright()),
                    new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright()))) {
                var presupuesto = PresupuestoComputo.deMilisegundosConPerfil(ms).arrancar();
                long inicio = System.nanoTime();
                ResultadoPlanificacion r = algoritmo.resolver(instancia, presupuesto);
                long real = (System.nanoTime() - inicio) / 1_000_000L;
                String etiqueta = String.format(Locale.ROOT, "%s %d ms", r.algoritmo(), ms);
                informar(etiqueta, r.solucion(), real, r.iteraciones(), r.perfil(),
                        instancia, objetivo, verificador);
                if (real > ms + 200) {
                    System.out.printf(Locale.ROOT,
                            "   AVISO presupuesto excedido: pedidos %d ms, consumidos %d ms%n", ms, real);
                }
                if (r.perfil() != null) {
                    System.out.printf(Locale.ROOT,
                            "   integral primal: costo=%.4f (ref %.2f)  H=%.4f (ref %d)   monotonia: %s%n",
                            r.perfil().integralPrimal(referencia), referencia,
                            r.perfil().integralPrimalPedidosNoAtendidos(base.h()), base.h(),
                            monotonia(r.perfil()));
                }
            }
        }
    }

    /** Imprime una linea de resultado y contrasta el valor declarado con el recalculado. */
    private static void informar(String etiqueta, Solucion solucion, long ms, long iteraciones,
                                 PerfilConvergencia perfil, InstanciaPlanificacion instancia,
                                 FuncionObjetivoJerarquica objetivo, VerificadorRestricciones verificador) {
        ValorObjetivo declarado = solucion.valor();
        ValorObjetivo recalculado = objetivo.evaluar(instancia, solucion);
        var verificacion = verificador.verificar(instancia, solucion);
        boolean equivalente = declarado.h() == recalculado.h()
                && Double.doubleToLongBits(declarado.costo()) == Double.doubleToLongBits(recalculado.costo());

        System.out.printf(Locale.ROOT,
                "%-28s H=%-4d S=%9.2f km=%-6d rutas=%-3d iter=%-8d %6d ms  %s  modelo=%s%n",
                etiqueta, declarado.h(), declarado.costo(), solucion.kilometros(),
                solucion.rutasConEntregas().size(), iteraciones, ms,
                verificacion.factible() ? "FACTIBLE" : "INFACTIBLE " + verificacion.tipos(),
                equivalente ? "coincide" : "DISCREPA declarado=" + declarado + " recalculado=" + recalculado);
    }

    /** Comprueba que la sucesion de hitos del perfil no empeora en el orden lexicografico. */
    private static String monotonia(PerfilConvergencia perfil) {
        ValorObjetivo previo = null;
        for (PerfilConvergencia.Medicion m : perfil.mediciones()) {
            if (previo != null && m.valor() != null && previo.mejorQue(m.valor())) {
                return "VIOLADA en la fraccion " + m.fraccionPresupuesto()
                        + ": " + previo + " -> " + m.valor();
            }
            if (m.valor() != null) {
                previo = m.valor();
            }
        }
        return "respetada";
    }
}
