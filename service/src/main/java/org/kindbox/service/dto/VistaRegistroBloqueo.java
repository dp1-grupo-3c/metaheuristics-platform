package org.kindbox.service.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.io.RepositorioDatos;

// Bloqueo guardado en un archivo mensual; mes y linea lo identifican para editarlo o borrarlo.
public record VistaRegistroBloqueo(String mes, String archivo, int linea, String registro,
                                   LocalDateTime inicio, LocalDateTime fin, List<SolicitudBloqueo.Nodo> nodos) {

    public static VistaRegistroBloqueo de(RepositorioDatos.RegistroBloqueo r) {
        List<SolicitudBloqueo.Nodo> nodos = new ArrayList<>();
        for (int i = 0; i + 1 < r.coordenadas().size(); i += 2) {
            nodos.add(new SolicitudBloqueo.Nodo(r.coordenadas().get(i), r.coordenadas().get(i + 1)));
        }
        return new VistaRegistroBloqueo(r.mes().toString(), r.archivo(), r.linea(), r.registro(),
                r.inicio(), r.fin(), nodos);
    }
}
