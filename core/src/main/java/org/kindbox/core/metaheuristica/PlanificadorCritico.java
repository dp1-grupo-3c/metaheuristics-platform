package org.kindbox.core.metaheuristica;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Protege pedidos de plazo corto despues de que el algoritmo principal construye su plan.
 *
 * <p>La metaheuristica conserva su comportamiento para el resto de pedidos. Si un pedido de
 * doce horas o menos no queda atendido puntualmente, se buscan unidades exclusivas y se
 * sustituyen las rutas conflictivas por rutas validadas por {@link ProgramadorRuta}. Los
 * pedidos grandes se reparten entre varias unidades, igual que en el resto del planificador.</p>
 */
public final class PlanificadorCritico implements Algoritmo {

    private static final int PLAZO_CRITICO_HORAS = 12;

    private final Algoritmo delegado;
    private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();

    public PlanificadorCritico(Algoritmo delegado) {
        if (delegado == null) {
            throw new IllegalArgumentException("El planificador critico necesita un delegado");
        }
        this.delegado = delegado;
    }

    @Override
    public String nombre() {
        return delegado.nombre() + "+CRITICA";
    }

    @Override
    public boolean admiteArranqueDesdePlanVigente() {
        return delegado.admiteArranqueDesdePlanVigente();
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia,
                                           PresupuestoComputo presupuesto) {
        return proteger(instancia, presupuesto, delegado.resolver(instancia, presupuesto));
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia,
                                           PresupuestoComputo presupuesto, long semilla) {
        return proteger(instancia, presupuesto, delegado.resolver(instancia, presupuesto, semilla));
    }

    @Override
    public ResultadoPlanificacion resolverDesde(InstanciaPlanificacion instancia,
                                                PresupuestoComputo presupuesto, long semilla,
                                                Solucion planVigente) {
        return proteger(instancia, presupuesto,
                delegado.resolverDesde(instancia, presupuesto, semilla, planVigente));
    }

    /**
     * Aplica la proteccion sin cambiar el tipo ni el nombre del algoritmo principal.
     * El motor la usa para que la politica critica sea transversal a cualquier algoritmo.
     */
    public static ResultadoPlanificacion protegerResultado(InstanciaPlanificacion instancia,
                                                            PresupuestoComputo presupuesto,
                                                            ResultadoPlanificacion resultado) {
        return new PlanificadorCritico(new AlgoritmoDelegado(resultado))
                .proteger(instancia, presupuesto, resultado);
    }

    private static final class AlgoritmoDelegado implements Algoritmo {
        private final ResultadoPlanificacion resultado;

        private AlgoritmoDelegado(ResultadoPlanificacion resultado) {
            this.resultado = resultado;
        }

        @Override
        public String nombre() {
            return resultado.algoritmo();
        }

        @Override
        public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia,
                                               PresupuestoComputo presupuesto) {
            return resultado;
        }
    }

    private ResultadoPlanificacion proteger(InstanciaPlanificacion instancia,
                                            PresupuestoComputo presupuesto,
                                            ResultadoPlanificacion resultado) {
        Solucion base = resultado.solucion();
        List<Integer> criticos = pedidosCriticos(instancia);
        if (criticos.isEmpty()) {
            return resultado;
        }

        List<Ruta> rutas = new ArrayList<>(base.rutas());
        Set<String> unidadesReservadas = new HashSet<>();
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        for (int pedido : criticos) {
            if (entregadoPuntualmente(rutas, instancia.pedidoId(pedido),
                    instancia.pedidoCantidad(pedido), instancia.pedidoMinutoLimite(pedido))) {
                continue;
            }
            int restante = instancia.pedidoCantidad(pedido);
            List<Candidato> candidatos = new ArrayList<>();
            while (restante > 0) {
                Candidato candidato = buscarRutaCritica(instancia, programador, pedido, restante,
                        unidadesReservadas);
                if (candidato == null) {
                    break;
                }
                candidatos.add(candidato);
                unidadesReservadas.add(candidato.codigoUnidad);
                restante -= candidato.cantidad;
            }
            if (restante > 0) {
                continue;
            }
            int idPedido = instancia.pedidoId(pedido);
            rutas.removeIf(ruta -> contienePedido(ruta, idPedido)
                    || candidatos.stream().anyMatch(c -> ruta.codigoUnidad().equals(c.codigoUnidad)));
            for (Candidato candidato : candidatos) {
                rutas.add(candidato.ruta);
            }
        }

        if (rutas.equals(base.rutas())) {
            return resultado;
        }
        Map<Integer, Integer> banco = banco(instancia, rutas);
        ValorObjetivo valor = objetivo.evaluar(instancia,
                new Solucion(rutas, banco, new ValorObjetivo(banco.size(), 0.0, 0.0)));
        Solucion protegida = new Solucion(rutas, banco, valor);
        return new ResultadoPlanificacion(nombre(), protegida, resultado.perfil(),
                resultado.milisegundos(), resultado.iteraciones(), resultado.semilla(),
                resultado.pesosOperadores());
    }

    private Candidato buscarRutaCritica(InstanciaPlanificacion instancia, ProgramadorRuta programador,
                                        int pedido, int restante, Set<String> ocupadas) {
        Candidato mejor = null;
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            String codigo = instancia.unidadCodigo(u);
            if (ocupadas.contains(codigo)) {
                continue;
            }
            int[] pedidos = {pedido};
            int cantidad = Math.min(restante, instancia.unidadCapacidad(u));
            int[] cantidades = {cantidad};
            Programacion programacion = programador.programar(u, pedidos, cantidades, 1, false);
            if (!programacion.factible() || programacion.ruta() == null) {
                continue;
            }
            Parada entrega = programacion.ruta().paradas().stream()
                    .filter(p -> p.tipo() == TipoParada.ENTREGA
                            && p.idPedido() == instancia.pedidoId(pedido))
                    .findFirst().orElse(null);
            if (entrega == null || entrega.minutoLlegada() > instancia.pedidoMinutoLimite(pedido)) {
                continue;
            }
            Candidato candidato = new Candidato(codigo, programacion.ruta(),
                    cantidad, entrega.minutoLlegada(), programacion.costo());
            if (mejor == null || candidato.compareTo(mejor) < 0) {
                mejor = candidato;
            }
        }
        return mejor;
    }

    private static List<Integer> pedidosCriticos(InstanciaPlanificacion instancia) {
        List<Integer> pedidos = new ArrayList<>();
        for (int i = 0; i < instancia.cantidadPedidos(); i++) {
            if (instancia.pedidoPlazoHoras(i) <= PLAZO_CRITICO_HORAS) {
                pedidos.add(i);
            }
        }
        pedidos.sort(Comparator.comparingLong(instancia::pedidoMinutoLimite)
                .thenComparingInt(instancia::pedidoId));
        return pedidos;
    }

    private static boolean entregadoPuntualmente(List<Ruta> rutas, int idPedido, int cantidad,
                                                 long limite) {
        int entregado = 0;
        for (Ruta ruta : rutas) {
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() == TipoParada.ENTREGA && parada.idPedido() == idPedido
                        && parada.minutoLlegada() <= limite) {
                    entregado += parada.cantidad();
                }
            }
        }
        return entregado >= cantidad;
    }

    private static boolean contienePedido(Ruta ruta, int idPedido) {
        return ruta.paradas().stream()
                .anyMatch(p -> p.tipo() == TipoParada.ENTREGA && p.idPedido() == idPedido);
    }

    private static Map<Integer, Integer> banco(InstanciaPlanificacion instancia, List<Ruta> rutas) {
        int[] entregado = new int[instancia.cantidadPedidos()];
        for (Ruta ruta : rutas) {
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() != TipoParada.ENTREGA) {
                    continue;
                }
                int i = instancia.indiceDePedido(parada.idPedido());
                if (i >= 0) {
                    entregado[i] += parada.cantidad();
                }
            }
        }
        Map<Integer, Integer> banco = new LinkedHashMap<>();
        for (int i = 0; i < entregado.length; i++) {
            int faltante = instancia.pedidoCantidad(i) - entregado[i];
            if (faltante > 0) {
                banco.put(instancia.pedidoId(i), faltante);
            }
        }
        return banco;
    }

    private record Candidato(String codigoUnidad, Ruta ruta, int cantidad, long llegada, double costo)
            implements Comparable<Candidato> {
        @Override
        public int compareTo(Candidato otro) {
            int porLlegada = Long.compare(llegada, otro.llegada);
            return porLlegada != 0 ? porLlegada : Double.compare(costo, otro.costo);
        }
    }
}
