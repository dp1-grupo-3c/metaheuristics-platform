package org.kindbox.core.operacion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.simulacion.InstantaneaSimulacion;

public record VistaOperacion(String contextoId, long revision, Estado estado, String zonaHoraria,
        Instant horaServidor, LocalDateTime fechaHoraOperativa, ConfiguracionOperacion configuracion,
        InstantaneaSimulacion instantanea, ParametrosOperacion.Instantanea parametros,
        List<VistaPedidoOperacion> pedidos, List<Incidente> incidentes, List<String> avisos,
        boolean calculando, long planesDescartados, String error) {
    public enum Estado { EN_CURSO, DETENIDA, FALLIDA }
    public record Incidente(String comandoId, String placa, int tipo, LocalDateTime inicio,
                            LocalDateTime reincorporacion, boolean resuelto) { }

    public VistaOperacion {
        pedidos = List.copyOf(pedidos);
        incidentes = List.copyOf(incidentes);
        avisos = List.copyOf(avisos);
    }
}
