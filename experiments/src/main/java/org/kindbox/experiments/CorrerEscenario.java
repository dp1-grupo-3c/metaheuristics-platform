package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;

/**
 * Ejecuta uno de los tres escenarios del enunciado desde la linea de comandos y publica el
 * resumen.
 *
 * <p>Es la via por la que la experimentacion numerica del apartado 12 del ISA obtiene el
 * instante de colapso y los indicadores de una corrida completa, sin pasar por el modulo de
 * servicio ni por el visualizador.</p>
 *
 * <p>Uso: {@code CorrerEscenario <raizDatos> <5D|COLAPSO|DIA> <primerDia> <algoritmo> <salto>
 * [semilla] [duracion]}. La semilla llega al algoritmo, que se crea con
 * {@link FabricaAlgoritmos} y admite ajustar sus parametros con propiedades de sistema
 * {@code -Dhgs.*} y {@code -Dalns.*}. Sin el ultimo argumento la corrida va en modo libre, es
 * decir tan rapido como se pueda, con el factor K de la corrida de 30 minutos que fija el
 * apartado 2.3 del ISA; {@code RAPIDO} pide K igual a 7 200 para pruebas de humo,
 * {@code LIBRE:<minutos>} otro K en modo libre, y un numero de minutos a secas acompasa la
 * simulacion 5D al reloj de pared durante esos minutos, que es el modo de las
 * presentaciones. Los detalles estan en {@link OpcionReloj}.</p>
 *
 * <p>Con {@code -DarranqueDesdePlanVigente=true} cada replanificacion arranca desde el plan
 * vigente en lugar de desde la heuristica constructiva, que es la hipotesis experimental de
 * los apartados 7.3.5 y 11.4 del ISA. Los detalles estan en {@link OpcionArranque}.</p>
 */
public final class CorrerEscenario {

    private CorrerEscenario() {
    }

    public static void main(String[] argumentos) throws Exception {
        Path raiz = Path.of(argumentos.length > 0 ? argumentos[0] : "data");
        String escenario = argumentos.length > 1 ? argumentos[1].toUpperCase(Locale.ROOT) : "5D";
        LocalDate primerDia = LocalDate.parse(argumentos.length > 2 ? argumentos[2] : "2026-09-01");
        String nombreAlgoritmo = FabricaAlgoritmos.canonico(argumentos.length > 3 ? argumentos[3] : "ALNS");
        int salto = argumentos.length > 4 ? Integer.parseInt(argumentos[4]) : 30;
        long semilla = argumentos.length > 5 ? Long.parseLong(argumentos[5]) : 20260901L;
        OpcionReloj reloj = OpcionReloj.interpretar(argumentos.length > 6 ? argumentos[6] : null,
                ModoReloj.ACOMPASADO);

        ConfiguracionEscenario base = switch (escenario) {
            case "COLAPSO" -> ConfiguracionEscenario.colapso(primerDia, salto, nombreAlgoritmo, semilla);
            case "DIA" -> ConfiguracionEscenario.diaADia(primerDia, salto, nombreAlgoritmo, semilla);
            case "5D", "SIMULACION_5D" -> ConfiguracionEscenario.simulacion5D(
                    primerDia, reloj.duracionMinutos(), salto, nombreAlgoritmo, semilla);
            default -> throw new IllegalArgumentException("Escenario desconocido: '" + escenario
                    + "'. Los admitidos son DIA, 5D y COLAPSO");
        };
        ConfiguracionEscenario configuracion = OpcionArranque.aplicar(reloj.aplicar(base));
        Algoritmo algoritmo = FabricaAlgoritmos.crear(nombreAlgoritmo, semilla);

        LocalDate ultimoDia = configuracion.tipo() == TipoEscenario.COLAPSO
                ? primerDia.plusDays(30) : configuracion.ultimoDia();
        var datos = new RepositorioDatos(raiz).cargar(primerDia, ultimoDia);

        System.out.printf(Locale.ROOT,
                "Escenario %s, %s, salto %d min, K=%.1f, modo %s, semilla %d, %d pedidos cargados%n",
                configuracion.tipo(), nombreAlgoritmo, salto, configuracion.factorAceleracion(),
                configuracion.modoReloj(), semilla, datos.pedidos().size());
        System.out.println(reloj.describir(configuracion));
        System.out.println(OpcionArranque.describir(configuracion, algoritmo));
        System.out.println("Algoritmo: " + algoritmo);

        var motor = new MotorSimulacion(datos, configuracion, new ParametrosOperacion(), algoritmo, List.of());
        ResultadoSimulacion resultado = motor.ejecutar();
        publicar(resultado);
    }

    /** Publica el resumen y comprueba las invariantes que el visualizador dara por buenas. */
    private static void publicar(ResultadoSimulacion r) {
        MetricasSimulacion m = r.metricas();
        System.out.printf(Locale.ROOT,
                "%n%s en el minuto %d (%s), %.2f dias simulados, %d ms reales%n  %s%n",
                r.estado(), r.minutoFinal(), r.fechaHoraFinal(), r.diasSimulados(),
                r.milisegundosReales(), r.mensaje());
        System.out.printf(Locale.ROOT,
                "  pedidos: registrados=%d entregados=%d pendientes=%d incumplidos=%d (con entrega parcial=%d)%n",
                m.pedidosRegistrados(), m.pedidosEntregados(), m.pedidosPendientes(),
                m.pedidosIncumplidos(), m.pedidosConEntregaParcial());
        System.out.println("  particion coherente: "
                + (m.particionCoherente() ? "SI" : "NO -- INVARIANTE ROTA"));
        System.out.printf(Locale.ROOT, "  paquetes=%d  costo=S/ %.2f  km=%d %s%n",
                m.unidadesEntregadas(), m.costoAcumulado(), m.kilometrosTotales(), m.kilometrosPorTipo());
        System.out.println("  tiempo medio de entrega por plazo (min): " + formatear(m));
        System.out.printf(Locale.ROOT, "  planificador: %d ejecuciones, %.0f ms de media%n",
                m.ejecucionesPlanificador(), m.milisegundosPorEjecucion());
        System.out.println("  averias por tipo: " + m.averiasPorTipo()
                + "   activaciones de semaforo: " + m.activacionesSemaforo().size());
        System.out.printf(Locale.ROOT, "  colapso=%d (pedido %d)   primer incumplimiento=%d (pedido %d)%n",
                r.minutoColapso(), r.pedidoDelColapso(),
                r.minutoPrimerIncumplimiento(), r.pedidoDelPrimerIncumplimiento());
        // Coherencia del costo: debe salir de los kilometros por tipo y del costo por Km.
        double costoRecalculado = 0.0;
        for (org.kindbox.core.modelo.TipoUnidad t : org.kindbox.core.modelo.TipoUnidad.values()) {
            costoRecalculado += m.kilometrosPorTipo().getOrDefault(t.name(), 0) * t.costoPorKm();
        }
        System.out.printf(Locale.ROOT, "  costo recalculado desde los km = S/ %.2f  %s%n",
                costoRecalculado,
                Math.abs(costoRecalculado - m.costoAcumulado()) < 0.005 ? "coincide" : "DISCREPA");
        System.out.println();
        System.out.println("CONCLUSION DEL ESCENARIO");
        boolean sinPedidosNoAtendidos = m.pedidosPendientes() == 0 && m.pedidosIncumplidos() == 0;
        if (sinPedidosNoAtendidos && m.particionCoherente()) {
            System.out.println("  Resultado correcto: todos los pedidos fueron entregados y "
                    + "la particion de metricas es coherente.");
        } else {
            System.out.printf(Locale.ROOT,
                    "  Resultado incompleto: quedaron %d pendientes y %d incumplidos.%n",
                    m.pedidosPendientes(), m.pedidosIncumplidos());
        }
        double porcentajeEntregado = m.pedidosRegistrados() == 0 ? 100.0
                : 100.0 * m.pedidosEntregados() / m.pedidosRegistrados();
        System.out.printf(Locale.ROOT,
                "  Rendimiento: %.2f %% de pedidos entregados, %.0f ms promedio por planificacion, "
                        + "%d replanificaciones y %d km recorridos.%n",
                porcentajeEntregado, m.milisegundosPorEjecucion(),
                m.ejecucionesPlanificador(), m.kilometrosTotales());
        System.out.println("  Lectura: " + (r.estado() == org.kindbox.core.simulacion.EstadoCorrida.CULMINADA
                ? "el horizonte configurado se completo." : "la corrida termino antes del horizonte configurado."));
    }

    private static String formatear(MetricasSimulacion m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Double> e : m.minutosEntregaPorPlazo().entrySet()) {
            if (m.entregasPorPlazo().getOrDefault(e.getKey(), 0) == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%dh=%.1f(n=%d) ",
                    e.getKey(), e.getValue(), m.entregasPorPlazo().get(e.getKey())));
        }
        return sb.toString().trim();
    }
}
