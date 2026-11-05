package org.kindbox.core.simulacion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.kindbox.core.modelo.TipoUnidad;

// Parte de un pedido que lleva una unidad: viaje, almacen de carga, horarios y paquetes que traslada.
public record EnvioPedido(
        String codigoUnidad,
        TipoUnidad tipoUnidad,
        int cantidad,
        int viaje,
        boolean cargado,
        int idAlmacenCarga,
        long minutoSalida,
        long minutoLlegada,
        LocalDateTime fechaHoraSalida,
        LocalDateTime fechaHoraLlegada,
        // Los paquetes no tienen codigo propio: se numeran de 1 a la cantidad del pedido.
        int primerPaquete,
        int ultimoPaquete) {

    private record Parte(VistaUnidad unidad, VistaUnidad.PedidoABordo pedido) {
    }

    // Envios de cada pedido segun la fotografia, por hora de llegada, numerados tras los ya entregados.
    public static Map<Integer, List<EnvioPedido>> porPedido(InstantaneaSimulacion foto) {
        Map<Integer, List<Parte>> partes = new TreeMap<>();
        for (VistaUnidad u : foto.unidades()) {
            for (VistaUnidad.PedidoABordo p : u.pedidosABordo()) {
                partes.computeIfAbsent(p.idPedido(), k -> new ArrayList<>()).add(new Parte(u, p));
            }
        }
        Map<Integer, List<EnvioPedido>> envios = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Parte>> entrada : partes.entrySet()) {
            List<Parte> lista = entrada.getValue();
            lista.sort(Comparator.comparingLong((Parte x) -> x.pedido().minutoLlegada())
                    .thenComparing(x -> x.unidad().codigo()));
            VistaUnidad.PedidoABordo primero = lista.get(0).pedido();
            int siguiente = primero.totalDelPedido() - primero.pendienteDelPedido() + 1;
            List<EnvioPedido> delPedido = new ArrayList<>(lista.size());
            for (Parte parte : lista) {
                VistaUnidad.PedidoABordo p = parte.pedido();
                delPedido.add(new EnvioPedido(parte.unidad().codigo(), parte.unidad().tipo(), p.enLaUnidad(),
                        p.viaje(), p.cargado(), p.idAlmacenCarga(), p.minutoSalida(), p.minutoLlegada(),
                        fecha(foto, p.minutoSalida()), fecha(foto, p.minutoLlegada()),
                        siguiente, siguiente + p.enLaUnidad() - 1));
                siguiente += p.enLaUnidad();
            }
            envios.put(entrada.getKey(), List.copyOf(delPedido));
        }
        return envios;
    }

    // El calendario avanza minuto a minuto, asi que basta desplazar la fecha de la fotografia.
    private static LocalDateTime fecha(InstantaneaSimulacion foto, long minuto) {
        return minuto < 0 ? null : foto.fechaHoraSimulada().plusMinutes(minuto - foto.minutoSimulado());
    }
}
