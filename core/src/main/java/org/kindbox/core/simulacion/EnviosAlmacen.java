package org.kindbox.core.simulacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// Envios de un almacen: los que salieron y van en camino, los que saldran pronto y los ya entregados.
public record EnviosAlmacen(
        VistaAlmacen almacen,
        int horas,
        long minuto,
        List<EnvioDelAlmacen> enTransito,
        List<EnvioDelAlmacen> porSalir,
        List<EntregaRegistrada> entregados,
        int paquetesEnTransito,
        int paquetesPorSalir,
        int paquetesEntregados) {

    public record EnvioDelAlmacen(int idPedido, EnvioPedido envio) {
    }

    public EnviosAlmacen {
        enTransito = List.copyOf(enTransito);
        porSalir = List.copyOf(porSalir);
        entregados = List.copyOf(entregados);
    }

    // La ventana de horas mira hacia adelante para las salidas y hacia atras para las entregas.
    public static EnviosAlmacen de(InstantaneaSimulacion foto, List<EntregaRegistrada> entregas,
                                   VistaAlmacen almacen, int horas) {
        long ahora = foto.minutoSimulado();
        long ventana = horas * 60L;
        List<EnvioDelAlmacen> enTransito = new ArrayList<>();
        List<EnvioDelAlmacen> porSalir = new ArrayList<>();
        for (Map.Entry<Integer, List<EnvioPedido>> entrada : EnvioPedido.porPedido(foto).entrySet()) {
            for (EnvioPedido envio : entrada.getValue()) {
                if (envio.idAlmacenCarga() != almacen.id() || envio.minutoSalida() < 0) {
                    continue;
                }
                if (envio.cargado() && envio.minutoSalida() <= ahora) {
                    enTransito.add(new EnvioDelAlmacen(entrada.getKey(), envio));
                } else if (envio.minutoSalida() > ahora && envio.minutoSalida() <= ahora + ventana) {
                    porSalir.add(new EnvioDelAlmacen(entrada.getKey(), envio));
                }
            }
        }
        enTransito.sort(Comparator.comparingLong(e -> e.envio().minutoLlegada()));
        porSalir.sort(Comparator.comparingLong((EnvioDelAlmacen e) -> e.envio().minutoSalida())
                .thenComparingLong(e -> e.envio().minutoLlegada()));
        List<EntregaRegistrada> entregados = entregas.stream()
                .filter(e -> e.idAlmacenCarga() == almacen.id() && e.minuto() <= ahora && e.minuto() >= ahora - ventana)
                .sorted(Comparator.comparingLong(EntregaRegistrada::minuto))
                .toList();
        return new EnviosAlmacen(almacen, horas, ahora, enTransito, porSalir, entregados,
                enTransito.stream().mapToInt(e -> e.envio().cantidad()).sum(),
                porSalir.stream().mapToInt(e -> e.envio().cantidad()).sum(),
                entregados.stream().mapToInt(EntregaRegistrada::cantidad).sum());
    }
}
