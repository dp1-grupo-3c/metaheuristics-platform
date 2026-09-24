package org.kindbox.experiments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.simulacion.EstadoSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;

/**
 * Diagnostico por pedido de una corrida completa, activado con {@code -DdiagnosticoPedidos=true}.
 *
 * <p>Envuelve al algoritmo como decorador, de modo que ve cada fotografia y cada plan sin
 * tocar el motor ni el nucleo compartido, y escucha las entregas como observador. Al cerrar
 * clasifica cada pedido incumplido, y cada entregado con menos de una hora de holgura, en una
 * de estas causas, por orden de precedencia:</p>
 * <ul>
 *   <li>{@code IMPOSIBLE_FISICO}: ni un auto libre en el almacen mas cercano al destino,
 *       disponible en la primera replanificacion que vio el pedido, llega antes del limite;</li>
 *   <li>{@code PLAN_ROTO}: el pedido estuvo asignado con llegada prevista dentro de plazo y
 *       luego lo perdio (averia, bloqueo o reasignacion);</li>
 *   <li>{@code NO_ENCONTRADO}: en alguna fotografia una unidad <b>sin ruta</b> en el plan
 *       devuelto podia atenderlo en visita directa respetando plazo y turno;</li>
 *   <li>{@code COMPETENCIA}: solo unidades ya ocupadas podian atenderlo en visita directa, es
 *       decir habia que desplazar otro trabajo;</li>
 *   <li>{@code VENTANA_O_TURNO}: alguna unidad llegaba a tiempo, pero ninguna cerraba el
 *       acondicionamiento antes de su cierre de turno;</li>
 *   <li>{@code CAPACIDAD}: ninguna unidad llega a tiempo por falta de carga o de inventario.</li>
 * </ul>
 */
final class DiagnosticoPedidos implements Algoritmo, ObservadorSimulacion {

    static final String PROPIEDAD = "diagnosticoPedidos";

    private final Algoritmo interno;
    private final Map<Integer, Pedido> pedidos = new HashMap<>();
    private final Map<Integer, Historia> historias = new HashMap<>();
    private final Map<Integer, Long> entregas = new HashMap<>();
    private long sobregiros;
    private long peorSobregiroMs;
    private long llamadas;
    /** Pedido cuyo contexto se vuelca en cada fotografia, con -Ddiagnostico.pedido=id. */
    private final int pedidoTrazado = Integer.getInteger("diagnostico.pedido", -1);
    private Map<String, Ruta> planAnterior = new HashMap<>();

    /** Lo observado de un pedido a lo largo de las replanificaciones que lo vieron. */
    private static final class Historia {
        long primeraFoto = -1;
        long cotaIdeal = Long.MAX_VALUE;
        boolean asignadoATiempo;
        boolean directoLibre;
        boolean directoOcupado;
        boolean soloTurno;
        int vecesEnBanco;
        int cambiosDeUnidad;
        String ultimaUnidad;
        final List<String> linea = new ArrayList<>();
    }

    DiagnosticoPedidos(Algoritmo interno, List<Pedido> todos) {
        this.interno = interno;
        for (Pedido p : todos) {
            pedidos.put(p.id(), p);
        }
    }

    static boolean activo() {
        return Boolean.getBoolean(PROPIEDAD);
    }

    // ---------------------------------------------------------------- decorador

    @Override
    public String nombre() {
        return interno.nombre();
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
        return anotar(instancia, presupuesto, interno.resolver(instancia, presupuesto));
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                           long semilla) {
        return anotar(instancia, presupuesto, interno.resolver(instancia, presupuesto, semilla));
    }

    @Override
    public boolean admiteArranqueDesdePlanVigente() {
        return interno.admiteArranqueDesdePlanVigente();
    }

    @Override
    public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                                long semilla, Solucion planVigente) {
        return anotar(instancia, presupuesto, interno.resolverDesde(instancia, presupuesto, semilla, planVigente));
    }

    @Override
    public String toString() {
        return interno.toString();
    }

    @Override
    public void alEntregarPedido(long minuto, int idPedido, String codigoUnidad, int cantidad,
                                 long minutosDesdeRegistro, int plazoHoras) {
        entregas.put(idPedido, minuto);
    }

    // ------------------------------------------------------------- analisis

    private ResultadoPlanificacion anotar(InstanciaPlanificacion inst, PresupuestoComputo presupuesto,
                                          ResultadoPlanificacion resultado) {
        llamadas++;
        long exceso = resultado.milisegundos() - presupuesto.milisegundosTotales();
        if (exceso > 0) {
            sobregiros++;
            peorSobregiroMs = Math.max(peorSobregiroMs, exceso);
        }
        Solucion sol = resultado.solucion();
        long ahora = inst.minutoActual();
        Map<Integer, Long> llegadaPlan = new HashMap<>();
        Map<Integer, String> unidadPlan = new HashMap<>();
        boolean[] ocupada = new boolean[inst.cantidadUnidades()];
        for (Ruta r : sol.rutas()) {
            int u = inst.indiceDeUnidad(r.codigoUnidad());
            for (Parada p : r.paradas()) {
                if (p.tipo() == TipoParada.ENTREGA) {
                    llegadaPlan.merge(p.idPedido(), p.minutoLlegada(), Math::max);
                    unidadPlan.put(p.idPedido(), r.codigoUnidad());
                    if (u >= 0) {
                        ocupada[u] = true;
                    }
                }
            }
        }
        Map<Integer, Integer> banco = sol.cantidadNoAtendida();
        trazar(inst, sol);
        ParametrosOperacion.Instantanea par = inst.parametros();
        for (int i = 0; i < inst.cantidadPedidos(); i++) {
            int id = inst.pedidoId(i);
            long limite = inst.pedidoMinutoLimite(i);
            Historia h = historias.computeIfAbsent(id, k -> new Historia());
            if (h.primeraFoto < 0) {
                h.primeraFoto = ahora;
                h.cotaIdeal = cotaIdeal(inst, i);
            }
            boolean enBanco = banco.containsKey(id);
            if (!enBanco) {
                Long llegada = llegadaPlan.get(id);
                if (llegada != null && llegada <= limite) {
                    h.asignadoATiempo = true;
                }
                String unidad = unidadPlan.get(id);
                if (h.ultimaUnidad != null && !h.ultimaUnidad.equals(unidad)) {
                    h.cambiosDeUnidad++;
                }
                h.ultimaUnidad = unidad;
                h.linea.add(String.format(Locale.ROOT, "t=%d asignado %s llega=%s", ahora,
                        unidadPlan.get(id), llegada));
                continue;
            }
            h.vecesEnBanco++;
            // En el banco: se mide si alguna unidad podia atenderlo en visita directa.
            int cantidad = inst.pedidoCantidad(i);
            long mejorLlegada = Long.MAX_VALUE;
            String mejorUnidad = "-";
            boolean libre = false;
            boolean ocupadaOk = false;
            boolean turno = false;
            for (int u = 0; u < inst.cantidadUnidades(); u++) {
                int cap = inst.unidadCapacidad(u);
                int visita = Math.min(cantidad, cap);
                long llegada = llegadaDirecta(inst, u, i, visita);
                if (llegada == Long.MAX_VALUE) {
                    continue;
                }
                if (llegada < mejorLlegada) {
                    mejorLlegada = llegada;
                    mejorUnidad = inst.unidadCodigo(u);
                }
                if (llegada > limite) {
                    continue;
                }
                if (llegada + par.minutosAcondicionamiento() > inst.unidadMinutoFinTurno(u)) {
                    turno = true;
                    continue;
                }
                if (ocupada[u]) {
                    ocupadaOk = true;
                } else {
                    libre = true;
                }
            }
            h.directoLibre |= libre;
            h.directoOcupado |= ocupadaOk;
            h.soloTurno |= turno && !libre && !ocupadaOk;
            h.linea.add(String.format(Locale.ROOT,
                    "t=%d BANCO holgura=%d mejorDirecta=%s@%s libre=%b ocupada=%b soloTurno=%b",
                    ahora, limite - ahora, mejorUnidad,
                    mejorLlegada == Long.MAX_VALUE ? "-" : String.valueOf(mejorLlegada),
                    libre, ocupadaOk, turno && !libre && !ocupadaOk));
        }
        return resultado;
    }

    /** Vuelca el contexto del pedido trazado: su unidad en el plan anterior y en el nuevo. */
    private void trazar(InstanciaPlanificacion inst, Solucion sol) {
        Map<String, Ruta> nuevo = new HashMap<>();
        for (Ruta r : sol.rutas()) {
            nuevo.put(r.codigoUnidad(), r);
        }
        int i = pedidoTrazado < 0 ? -1 : inst.indiceDePedido(pedidoTrazado);
        if (i >= 0) {
            System.out.printf(Locale.ROOT, "TRAZA t=%d pedido=%d limite=%d cant=%d banco=%s%n", inst.minutoActual(),
                    pedidoTrazado, inst.pedidoMinutoLimite(i), inst.pedidoCantidad(i),
                    sol.cantidadNoAtendida().get(pedidoTrazado));
            for (Map.Entry<String, Ruta> e : planAnterior.entrySet()) {
                boolean loTenia = e.getValue().paradas().stream()
                        .anyMatch(p -> p.tipo() == TipoParada.ENTREGA && p.idPedido() == pedidoTrazado);
                if (!loTenia) {
                    continue;
                }
                int u = inst.indiceDeUnidad(e.getKey());
                System.out.printf(Locale.ROOT, "  unidad %s disp=%d nodo=%d carga=%d finTurno=%d%n", e.getKey(),
                        u < 0 ? -1 : inst.unidadMinutoDisponible(u), u < 0 ? -1 : inst.unidadNodo(u),
                        u < 0 ? -1 : inst.unidadCarga(u), u < 0 ? -1 : inst.unidadMinutoFinTurno(u));
                System.out.println("    antes: " + describir(inst, e.getValue()));
                System.out.println("    ahora: " + describir(inst, nuevo.get(e.getKey())));
            }
        }
        planAnterior = nuevo;
    }

    private static String describir(InstanciaPlanificacion inst, Ruta r) {
        if (r == null) {
            return "(sin ruta)";
        }
        StringBuilder sb = new StringBuilder();
        for (Parada p : r.paradas()) {
            switch (p.tipo()) {
                case ENTREGA -> {
                    int i = inst.indiceDePedido(p.idPedido());
                    sb.append(String.format(Locale.ROOT, "E%d[%d<=%s] ", p.idPedido(), p.minutoLlegada(),
                            i < 0 ? "?" : String.valueOf(inst.pedidoMinutoLimite(i))));
                }
                case ABASTECIMIENTO -> sb.append(String.format(Locale.ROOT, "A%d@%d ", p.idAlmacen(), p.minutoLlegada()));
                case ALIMENTACION -> sb.append(String.format(Locale.ROOT, "P@%d-%d ", p.minutoLlegada(), p.minutoSalida()));
            }
        }
        return sb.toString();
    }

    /** Llegada de la unidad al pedido en visita directa, pasando por un almacen si le falta carga. */
    private static long llegadaDirecta(InstanciaPlanificacion inst, int u, int pedido, int cantidad) {
        MatrizDistancias m = inst.matriz();
        TipoUnidad tipo = inst.unidadTipo(u);
        int origen = inst.puntoUnidad(u);
        int destino = inst.puntoPedido(pedido);
        long t = inst.unidadMinutoDisponible(u);
        if (inst.unidadCarga(u) >= cantidad) {
            int km = m.km(origen, destino);
            return km >= MatrizDistancias.INALCANZABLE ? Long.MAX_VALUE
                    : t + inst.parametros().minutosDeViaje(tipo, km);
        }
        long mejor = Long.MAX_VALUE;
        for (int a = 0; a < inst.cantidadAlmacenes(); a++) {
            if (!inst.almacenEsCentral(a) && inst.almacenInventario(a) < cantidad - inst.unidadCarga(u)) {
                continue;
            }
            int ida = m.km(origen, inst.puntoAlmacen(a));
            int vuelta = m.km(inst.puntoAlmacen(a), destino);
            if (ida >= MatrizDistancias.INALCANZABLE || vuelta >= MatrizDistancias.INALCANZABLE) {
                continue;
            }
            mejor = Math.min(mejor, t + inst.parametros().minutosDeViaje(tipo, ida + vuelta));
        }
        return mejor;
    }

    /**
     * Cota inferior fisica del instante de llegada: un auto, el tipo mas rapido, libre en el
     * almacen con inventario mas cercano al destino, en el instante de la fotografia.
     */
    private static long cotaIdeal(InstanciaPlanificacion inst, int pedido) {
        MatrizDistancias m = inst.matriz();
        int destino = inst.puntoPedido(pedido);
        int mejorKm = Integer.MAX_VALUE;
        for (int a = 0; a < inst.cantidadAlmacenes(); a++) {
            int km = m.km(inst.puntoAlmacen(a), destino);
            if (km < mejorKm && (inst.almacenEsCentral(a) || inst.almacenInventario(a) > 0)) {
                mejorKm = km;
            }
        }
        if (mejorKm >= MatrizDistancias.INALCANZABLE) {
            return Long.MAX_VALUE;
        }
        return inst.minutoActual() + inst.parametros().minutosDeViaje(TipoUnidad.AUTO, mejorKm);
    }

    private String causa(Pedido p, Historia h) {
        if (h == null || h.primeraFoto < 0) {
            return "SIN_FOTOGRAFIA";
        }
        if (h.cotaIdeal > p.minutoLimite()) {
            return "IMPOSIBLE_FISICO";
        }
        if (h.asignadoATiempo) {
            return "PLAN_ROTO";
        }
        if (h.directoLibre) {
            return "NO_ENCONTRADO";
        }
        if (h.directoOcupado) {
            return "COMPETENCIA";
        }
        if (h.soloTurno) {
            return "VENTANA_O_TURNO";
        }
        return "CAPACIDAD";
    }

    /** Imprime la clasificacion de incumplidos y de entregas en riesgo. */
    void publicar(EstadoSimulacion estado, long minutoFinal) {
        System.out.println();
        System.out.println("DIAGNOSTICO DE PEDIDOS");
        System.out.printf(Locale.ROOT, "  llamadas al planificador=%d, con sobregiro de presupuesto=%d (peor %d ms)%n",
                llamadas, sobregiros, peorSobregiroMs);
        Map<String, Integer> resumenIncumplidos = new TreeMap<>();
        Map<String, Integer> resumenRiesgo = new TreeMap<>();
        List<String> detalle = new ArrayList<>();
        for (int i = 0; i < estado.pedidos().size(); i++) {
            Pedido p = estado.pedido(i);
            Historia h = historias.get(p.id());
            Long entrega = entregas.get(p.id());
            // Registrado, sin completar y fuera de los pendientes: es un incumplido.
            boolean incumplido = p.minutoRegistro() <= minutoFinal && entrega == null
                    && estado.pendienteDe(i) == 0 && estado.noEntregadoDe(i) > 0;
            if (incumplido) {
                String c = causa(p, h);
                resumenIncumplidos.merge(c, 1, Integer::sum);
                detalle.add(String.format(Locale.ROOT,
                        "  INCUMPLIDO pedido=%d plazo=%dh registro=%d limite=%d cant=%d nodo=(%d,%d) turnoLimite=%s cotaIdeal=%d -> %s",
                        p.id(), p.plazoHoras(), p.minutoRegistro(), p.minutoLimite(), p.cantidad(),
                                p.x(), p.y(), Turno.enMinuto(p.minutoLimite()), h == null ? -1 : h.cotaIdeal, c));
                for (String l : h == null ? List.<String>of() : h.linea) {
                    detalle.add("      " + l);
                }
            } else if (entrega != null && p.minutoLimite() - entrega < 60) {
                // Entregado a tiempo: la causa no aplica, se describe como llego al limite.
                String c = h == null ? "SIN_FOTOGRAFIA"
                        : h.vecesEnBanco > 0 ? "PASO_POR_BANCO" : h.cambiosDeUnidad > 0 ? "REASIGNADO" : "ESTABLE";
                resumenRiesgo.merge(c, 1, Integer::sum);
                detalle.add(String.format(Locale.ROOT,
                        "  RIESGO pedido=%d plazo=%dh registro=%d entrega=%d holgura=%d banco=%d cambiosUnidad=%d -> %s",
                        p.id(), p.plazoHoras(), p.minutoRegistro(), entrega, p.minutoLimite() - entrega,
                        h == null ? 0 : h.vecesEnBanco, h == null ? 0 : h.cambiosDeUnidad, c));
            }
        }
        System.out.println("  incumplidos por causa: " + resumenIncumplidos);
        System.out.println("  entregados con holgura < 60 min por causa: " + resumenRiesgo);
        detalle.forEach(System.out::println);
    }
}
