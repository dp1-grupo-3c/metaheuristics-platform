package org.kindbox.core.problema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan producido por una iteracion del planificador.
 *
 * <p>Es la representacion comun que devuelven los dos algoritmos. Se compone del conjunto
 * de rutas asignadas y del banco de pedidos que no recibieron asignacion factible dentro
 * de plazo, cuyo cardinal es exactamente la H del nivel 1 del objetivo (apartado 7.3.1
 * del ISA).</p>
 *
 * <p>Un pedido admite entregas parciales (respuesta 13 del cuestionario), de modo que
 * puede aparecer repartido entre varias rutas. Un pedido cuenta para H cuando queda
 * cualquier remanente sin asignar.</p>
 */
public final class Solucion {

    private final List<Ruta> rutas;
    private final Map<Integer, Integer> cantidadNoAtendida;
    private final ValorObjetivo valor;

    public Solucion(List<Ruta> rutas, Map<Integer, Integer> cantidadNoAtendida, ValorObjetivo valor) {
        this.rutas = List.copyOf(rutas);
        this.cantidadNoAtendida = Collections.unmodifiableMap(new LinkedHashMap<>(cantidadNoAtendida));
        this.valor = valor;
    }

    /** Solucion sin rutas, con todos los pedidos de la instancia en el banco. */
    public static Solucion vacia(InstanciaPlanificacion instancia) {
        Map<Integer, Integer> banco = new LinkedHashMap<>();
        for (int i = 0; i < instancia.cantidadPedidos(); i++) {
            banco.put(instancia.pedidoId(i), instancia.pedidoCantidad(i));
        }
        return new Solucion(List.of(), banco, new ValorObjetivo(banco.size(), 0.0, 0.0));
    }

    /** Rutas del plan, una por unidad activa. */
    public List<Ruta> rutas() {
        return rutas;
    }

    /** Rutas que contienen al menos una entrega. */
    public List<Ruta> rutasConEntregas() {
        List<Ruta> activas = new ArrayList<>(rutas.size());
        for (Ruta r : rutas) {
            if (!r.sinEntregas()) {
                activas.add(r);
            }
        }
        return activas;
    }

    /** Banco de pedidos no atendidos: identificador del pedido a unidades sin asignar. */
    public Map<Integer, Integer> cantidadNoAtendida() {
        return cantidadNoAtendida;
    }

    /** Valor de la funcion objetivo jerarquica. */
    public ValorObjetivo valor() {
        return valor;
    }

    /** Numero de pedidos con remanente sin asignar. Es la H del nivel 1 del objetivo. */
    public int h() {
        return cantidadNoAtendida.size();
    }

    /** Costo de operacion del plan, en soles. */
    public double costo() {
        return valor.costo();
    }

    /** Kilometros totales recorridos por el plan. */
    public int kilometros() {
        int km = 0;
        for (Ruta r : rutas) {
            km += r.kilometros();
        }
        return km;
    }

    /** Unidades del producto P entregadas por el plan. */
    public int unidadesEntregadas() {
        int q = 0;
        for (Ruta r : rutas) {
            q += r.unidadesEntregadas();
        }
        return q;
    }

    /** Asignacion resultante de identificador de pedido a codigo de unidad, para el termino de estabilidad. */
    public Map<Integer, String> asignacion() {
        Map<Integer, String> mapa = new LinkedHashMap<>();
        for (Ruta r : rutas) {
            for (Parada p : r.paradas()) {
                if (p.tipo() == TipoParada.ENTREGA) {
                    mapa.putIfAbsent(p.idPedido(), r.codigoUnidad());
                }
            }
        }
        return mapa;
    }

    /** Copia de esta solucion con otro valor objetivo, para reevaluaciones. */
    public Solucion conValor(ValorObjetivo nuevoValor) {
        return new Solucion(rutas, cantidadNoAtendida, nuevoValor);
    }

    @Override
    public String toString() {
        return "Solucion[" + rutasConEntregas().size() + " rutas, " + valor + "]";
    }
}
