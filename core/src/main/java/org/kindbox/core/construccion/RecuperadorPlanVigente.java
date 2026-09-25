package org.kindbox.core.construccion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.problema.*;

/** Reprograma entregas anteriores con la fotografia actual, sin confiar en sus tiempos. */
public final class RecuperadorPlanVigente {
    private RecuperadorPlanVigente() { }

    public static Solucion recuperar(InstanciaPlanificacion instancia, Solucion anterior,
                                     PresupuestoComputo presupuesto) {
        var programador = new ProgramadorRuta(instancia);
        int[] asignado = new int[instancia.cantidadPedidos()];
        var rutas = new ArrayList<Ruta>();
        var usadas = new HashSet<Integer>();
        for (Ruta ruta : anterior.rutas()) {
            if (presupuesto.agotado()) break;
            int unidad = instancia.indiceDeUnidad(ruta.codigoUnidad());
            if (unidad < 0 || !usadas.add(unidad)) continue;
            var pedidos = new ArrayList<Integer>();
            var cantidades = new ArrayList<Integer>();
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() != TipoParada.ENTREGA) continue;
                int pedido = instancia.indiceDePedido(parada.idPedido());
                if (pedido < 0) continue;
                int cantidad = Math.min(parada.cantidad(), instancia.pedidoCantidad(pedido) - asignado[pedido]);
                if (cantidad <= 0) continue;
                pedidos.add(pedido);
                cantidades.add(cantidad);
                asignado[pedido] += cantidad;
            }
            // Retirar la visita menos urgente cuando un bloqueo o un cambio de turno
            // invalida la secuencia. Las cantidades retiradas vuelven al banco.
            while (!pedidos.isEmpty()) {
                int[] indices = pedidos.stream().mapToInt(Integer::intValue).toArray();
                int[] cargas = cantidades.stream().mapToInt(Integer::intValue).toArray();
                var prueba = programador.programar(unidad, indices, cargas, indices.length, false);
                if (prueba.factible()) {
                    rutas.add(programador.programar(unidad, indices, cargas, indices.length, true).ruta());
                    break;
                }
                int retirar = 0;
                for (int k = 1; k < pedidos.size(); k++) {
                    if (instancia.pedidoMinutoLimiteEfectivo(pedidos.get(k))
                            >= instancia.pedidoMinutoLimiteEfectivo(pedidos.get(retirar))) retirar = k;
                }
                asignado[pedidos.remove(retirar)] -= cantidades.remove(retirar);
            }
        }
        var banco = new LinkedHashMap<Integer, Integer>();
        for (int p = 0; p < asignado.length; p++) {
            int falta = instancia.pedidoCantidad(p) - asignado[p];
            if (falta > 0) banco.put(instancia.pedidoId(p), falta);
        }
        var solucion = new Solucion(rutas, banco, ValorObjetivo.PEOR);
        return solucion.conValor(new FuncionObjetivoJerarquica().evaluar(instancia, solucion));
    }
}
