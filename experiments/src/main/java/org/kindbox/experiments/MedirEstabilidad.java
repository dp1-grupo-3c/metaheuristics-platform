package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;

/**
 * Medidor de la inestabilidad del plan entre replanificaciones sucesivas, es decir del
 * termino blando del apartado 11.4 del ISA visto desde fuera del planificador.
 *
 * <p>El apartado 11.4 pide que el plan nuevo se aparte lo menos posible del vigente,
 * medida la distancia como el numero de pedidos que cambian de unidad asignada. Esta clase
 * no cambia nada: solo observa. Envuelve un {@link Algoritmo} en un {@link Espia} que, en
 * cada iteracion, contrasta la asignacion que devuelve el algoritmo contra la asignacion
 * del plan vigente que traia la {@link InstanciaPlanificacion}, y acumula:</p>
 * <ul>
 *   <li><b>reasignaciones</b>, pedidos que ya estaban asignados a una unidad y pasan a otra;</li>
 *   <li><b>abandonos</b>, pedidos que estaban asignados y quedan sin asignar;</li>
 *   <li><b>altas</b>, pedidos que no estaban asignados y pasan a estarlo, que <b>no</b> son
 *       inestabilidad sino trabajo nuevo;</li>
 *   <li><b>permanencias</b>, pedidos que conservan su unidad.</li>
 * </ul>
 *
 * <p>El indicador que se publica es la <b>tasa de reasignacion</b>: reasignaciones entre los
 * pedidos que ya venian asignados. Reasignaciones mas abandonos es exactamente la
 * desviacion que calcula {@code FuncionObjetivoJerarquica.desviacionDelPlanVigente}, de modo
 * que el medidor y la funcion objetivo hablan del mismo numero.</p>
 *
 * <p>Un pedido admite entregas parciales repartidas entre varias unidades, de modo que la
 * pregunta bien planteada no es cual es "la" unidad del pedido en el plan nuevo, sino si la
 * unidad que lo tenia asignado lo sigue atendiendo. El medidor la responde asi, igual que la
 * funcion objetivo, y por eso es independiente del orden de las rutas.</p>
 *
 * <p>Uso: {@code MedirEstabilidad <raizDatos> <algoritmo> <salto> [semilla] [pedidoSeguido]
 * [duracionMinutosReales]}. Ejecuta la simulacion 5D en modo LIBRE desde el 2026-09-01. Con
 * {@code pedidoSeguido} se imprime, replanificacion a replanificacion, la unidad asignada a
 * ese pedido, que es la traza con la que se documenta el defecto de rotacion de unidad.</p>
 */
public final class MedirEstabilidad {

    private MedirEstabilidad() {
    }

    /**
     * Envoltorio de un algoritmo que mide la distancia entre el plan que devuelve y el plan
     * vigente que traia la instancia. No altera la solucion ni el presupuesto: solo cuenta.
     *
     * <p>El costo de la medicion es proporcional al numero de entregas del plan y se paga una
     * sola vez por replanificacion, fuera del bucle de busqueda del algoritmo, de modo que no
     * interfiere con el presupuesto de reloj de pared del apartado 2.3.</p>
     */
    public static final class Espia implements Algoritmo {

        private final Algoritmo delegado;

        private int pedidoSeguido = -1;
        private boolean imprimirTraza = true;
        private final List<String> traza = new ArrayList<>();
        private String unidadPreviaDelSeguido;

        private long replanificaciones;
        private long pedidosAsignados;
        private long pedidosYaAsignados;
        private long reasignaciones;
        private long reasignacionesForzadas;
        private long abandonos;
        private long altas;
        private long permanencias;

        public Espia(Algoritmo delegado) {
            this.delegado = delegado;
        }

        /** Sigue a un pedido concreto por su identificador global. Con {@code -1} no sigue a ninguno. */
        public Espia seguirPedido(int idPedido) {
            this.pedidoSeguido = idPedido;
            return this;
        }

        /** Indica si la traza del pedido seguido se vuelca a la salida estandar segun ocurre. */
        public Espia imprimirTraza(boolean imprimir) {
            this.imprimirTraza = imprimir;
            return this;
        }

        @Override
        public String nombre() {
            return delegado.nombre();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia,
                                               PresupuestoComputo presupuesto) {
            ResultadoPlanificacion resultado = delegado.resolver(instancia, presupuesto);
            registrar(instancia, resultado.solucion());
            return resultado;
        }

        /** Contrasta una solucion contra el plan vigente de su instancia y acumula los contadores. */
        public void registrar(InstanciaPlanificacion instancia, Solucion solucion) {
            Map<Integer, List<String>> nuevo = unidadesPorPedido(solucion);
            Map<Integer, String> vigente = instancia.asignacionVigente();

            replanificaciones++;
            pedidosAsignados += nuevo.size();
            pedidosYaAsignados += vigente.size();

            for (Map.Entry<Integer, String> asignacion : vigente.entrySet()) {
                List<String> ahora = nuevo.get(asignacion.getKey());
                if (ahora == null) {
                    abandonos++;
                } else if (ahora.contains(asignacion.getValue())) {
                    permanencias++;
                } else {
                    reasignaciones++;
                    // Una unidad ausente de la fotografia (averiada, en mantenimiento o en
                    // trasvase) no puede honrar su asignacion: ese cambio era inevitable.
                    if (instancia.indiceDeUnidad(asignacion.getValue()) < 0) {
                        reasignacionesForzadas++;
                    }
                }
            }
            for (Integer idPedido : nuevo.keySet()) {
                if (!vigente.containsKey(idPedido)) {
                    altas++;
                }
            }

            if (pedidoSeguido >= 0) {
                anotarSeguimiento(instancia, nuevo);
            }
        }

        /** Unidades que atienden cada pedido en el plan, en el orden de las rutas. */
        private static Map<Integer, List<String>> unidadesPorPedido(Solucion solucion) {
            Map<Integer, List<String>> mapa = new HashMap<>();
            for (Ruta ruta : solucion.rutas()) {
                for (Parada parada : ruta.paradas()) {
                    if (parada.tipo() == TipoParada.ENTREGA) {
                        List<String> unidades =
                                mapa.computeIfAbsent(parada.idPedido(), k -> new ArrayList<>(1));
                        if (!unidades.contains(ruta.codigoUnidad())) {
                            unidades.add(ruta.codigoUnidad());
                        }
                    }
                }
            }
            return mapa;
        }

        /** Anota una linea de la traza del pedido seguido, al estilo del informe del defecto. */
        private void anotarSeguimiento(InstanciaPlanificacion instancia, Map<Integer, List<String>> nuevo) {
            int indicePedido = instancia.indiceDePedido(pedidoSeguido);
            if (indicePedido < 0) {
                // El pedido ya no esta pendiente: fue entregado o quedo fuera de la fotografia.
                return;
            }
            List<String> unidades = nuevo.get(pedidoSeguido);
            String codigo = unidades == null || unidades.isEmpty() ? null : String.join("+", unidades);
            StringBuilder linea = new StringBuilder();
            linea.append(String.format(Locale.ROOT, "  min %d -> ", instancia.minutoActual()));
            if (codigo == null) {
                linea.append("sin asignar");
            } else {
                linea.append(codigo);
                int indiceUnidad = unidades.size() == 1 ? instancia.indiceDeUnidad(unidades.get(0)) : -1;
                if (indiceUnidad >= 0) {
                    linea.append(String.format(Locale.ROOT, " (%s, disponible desde %d)",
                            ubicacion(instancia, instancia.unidadNodo(indiceUnidad)),
                            instancia.unidadMinutoDisponible(indiceUnidad)));
                }
            }
            if (unidadPreviaDelSeguido != null && !unidadPreviaDelSeguido.equals(String.valueOf(codigo))) {
                linea.append("   CAMBIO desde ").append(unidadPreviaDelSeguido);
            }
            linea.append(String.format(Locale.ROOT, "   holgura %d min",
                    instancia.pedidoHolgura(indicePedido)));
            unidadPreviaDelSeguido = String.valueOf(codigo);

            String texto = linea.toString();
            traza.add(texto);
            if (imprimirTraza) {
                System.out.println(texto);
            }
        }

        /** Describe el punto en que se encuentra una unidad, con nombre si es un almacen. */
        private static String ubicacion(InstanciaPlanificacion instancia, int nodo) {
            for (int i = 0; i < instancia.cantidadAlmacenes(); i++) {
                if (instancia.almacenNodo(i) == nodo) {
                    return instancia.almacenEsCentral(i)
                            ? "en el almacen central"
                            : "en el almacen " + instancia.almacenNombre(i);
                }
            }
            return "en " + Ciudad.texto(nodo);
        }

        // ------------------------------------------------------------ indicadores

        /** Replanificaciones observadas. */
        public long replanificaciones() {
            return replanificaciones;
        }

        /** Pedidos asignados en total, sumados sobre todas las replanificaciones. */
        public long pedidosAsignados() {
            return pedidosAsignados;
        }

        /** Pedidos que ya venian asignados por el plan vigente, sumados sobre las replanificaciones. */
        public long pedidosYaAsignados() {
            return pedidosYaAsignados;
        }

        /** Pedidos que estaban asignados a una unidad y pasan a otra distinta. */
        public long reasignaciones() {
            return reasignaciones;
        }

        /** Reasignaciones inevitables porque la unidad vigente ya no esta en la fotografia. */
        public long reasignacionesForzadas() {
            return reasignacionesForzadas;
        }

        /** Pedidos que estaban asignados y quedan sin asignar. */
        public long abandonos() {
            return abandonos;
        }

        /** Pedidos que no estaban asignados y pasan a estarlo. No es inestabilidad. */
        public long altas() {
            return altas;
        }

        /** Pedidos que conservan la unidad que ya tenian. */
        public long permanencias() {
            return permanencias;
        }

        /** Reasignaciones entre pedidos que ya venian asignados. Es el indicador principal. */
        public double tasaReasignacion() {
            return pedidosYaAsignados == 0 ? 0.0 : (double) reasignaciones / pedidosYaAsignados;
        }

        /**
         * Desviacion total respecto del plan vigente, es decir asignaciones vigentes que el
         * plan nuevo no respeta. Coincide con la suma de reasignaciones y abandonos, que es
         * lo que mide {@code FuncionObjetivoJerarquica.desviacionDelPlanVigente}.
         */
        public long desviacionTotal() {
            return reasignaciones + abandonos;
        }

        /** Traza del pedido seguido, una linea por replanificacion en que seguia pendiente. */
        public List<String> traza() {
            return List.copyOf(traza);
        }

        /** Resumen de una sola linea, apto para una tabla de la experimentacion. */
        public String resumen() {
            return String.format(Locale.ROOT,
                    "tasa=%.4f reasignaciones=%d abandonos=%d altas=%d permanencias=%d "
                            + "asignados=%d yaAsignados=%d replanificaciones=%d",
                    tasaReasignacion(), reasignaciones, abandonos, altas, permanencias,
                    pedidosAsignados, pedidosYaAsignados, replanificaciones);
        }
    }

    // ---------------------------------------------------------------------- main

    public static void main(String[] argumentos) throws Exception {
        Path raiz = Path.of(argumentos.length > 0 ? argumentos[0] : "data");
        String nombreAlgoritmo = argumentos.length > 1 ? argumentos[1].toUpperCase(Locale.ROOT) : "ALNS";
        int salto = argumentos.length > 2 ? Integer.parseInt(argumentos[2]) : 30;
        long semilla = argumentos.length > 3 ? Long.parseLong(argumentos[3]) : 20260901L;
        int pedidoSeguido = argumentos.length > 4 ? Integer.parseInt(argumentos[4]) : -1;
        int duracionReal = argumentos.length > 5 ? Integer.parseInt(argumentos[5]) : 1;

        LocalDate primerDia = LocalDate.parse("2026-09-01");
        ConfiguracionEscenario base =
                ConfiguracionEscenario.simulacion5D(primerDia, duracionReal, salto, nombreAlgoritmo, semilla);
        // Modo LIBRE: la corrida no se acompasa al reloj de pared, pero el presupuesto por
        // ejecucion sigue saliendo del factor K, igual que en CorrerEscenario.
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(base.tipo(), base.primerDia(),
                base.ultimoDia(), base.saltoMinutos(), base.factorAceleracion(), ModoReloj.LIBRE,
                base.algoritmo(), base.semilla(), base.minutosEntreFotografias(), base.generarAverias(),
                base.averiasPorUnidadPorTurno());

        var datos = new RepositorioDatos(raiz).cargar(primerDia, configuracion.ultimoDia());
        Algoritmo interno = "HGS".equals(nombreAlgoritmo)
                ? new BusquedaGeneticaHibrida(new AhorrosClarkeWright())
                : new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright());
        Espia espia = new Espia(interno).seguirPedido(pedidoSeguido);

        System.out.printf(Locale.ROOT,
                "Estabilidad, escenario %s, %s, salto %d min, K=%.1f, modo %s, semilla %d, %d pedidos cargados%n",
                configuracion.tipo(), nombreAlgoritmo, salto, configuracion.factorAceleracion(),
                configuracion.modoReloj(), semilla, datos.pedidos().size());
        if (pedidoSeguido >= 0) {
            System.out.println("Traza del pedido " + pedidoSeguido + ":");
        }

        var motor = new MotorSimulacion(datos, configuracion, new ParametrosOperacion(), espia, List.of());
        ResultadoSimulacion resultado = motor.ejecutar();
        publicar(espia, resultado, nombreAlgoritmo, salto, semilla);
    }

    /** Publica la inestabilidad medida y los indicadores de la corrida. */
    private static void publicar(Espia espia, ResultadoSimulacion r,
                                 String nombreAlgoritmo, int salto, long semilla) {
        MetricasSimulacion m = r.metricas();
        System.out.printf(Locale.ROOT,
                "%nESTABILIDAD %s salto=%d semilla=%d%n", nombreAlgoritmo, salto, semilla);
        System.out.printf(Locale.ROOT, "  tasa de reasignacion = %.2f %%  (%d de %d ya asignados)%n",
                100.0 * espia.tasaReasignacion(), espia.reasignaciones(), espia.pedidosYaAsignados());
        System.out.printf(Locale.ROOT,
                "  reasignaciones=%d (forzadas=%d)  abandonos=%d  altas=%d  permanencias=%d%n",
                espia.reasignaciones(), espia.reasignacionesForzadas(), espia.abandonos(),
                espia.altas(), espia.permanencias());
        System.out.printf(Locale.ROOT,
                "  desviacion total del plan vigente=%d  pedidos asignados=%d  replanificaciones=%d%n",
                espia.desviacionTotal(), espia.pedidosAsignados(), espia.replanificaciones());
        System.out.printf(Locale.ROOT,
                "  corrida: %s en el minuto %d, entregados=%d incumplidos=%d pendientes=%d costo=S/ %.2f km=%d%n",
                r.estado(), r.minutoFinal(), m.pedidosEntregados(), m.pedidosIncumplidos(),
                m.pedidosPendientes(), m.costoAcumulado(), m.kilometrosTotales());
        System.out.printf(Locale.ROOT,
                "  planificador: %d ejecuciones, %.0f ms de media, %d ms reales de corrida%n",
                m.ejecucionesPlanificador(), m.milisegundosPorEjecucion(), r.milisegundosReales());
        System.out.println("  " + espia.resumen());
    }
}
