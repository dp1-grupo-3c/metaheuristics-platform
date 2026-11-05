package org.kindbox.core.simulacion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.TipoParada;

// Ultimo plan comprometido: cuando, con que algoritmo, la ruta de cada unidad y lo que quedo sin asignar.
public record PlanVigente(
        long minuto,
        LocalDateTime fechaHora,
        String algoritmo,
        List<RutaPlan> rutas,
        Map<Integer, Integer> sinAsignar,
        int kilometros,
        double costo,
        int paquetesPlanificados) {

    public record RutaPlan(String codigoUnidad, TipoUnidad tipoUnidad, int kilometros, double costo,
                           List<ParadaPlan> paradas) {
        public RutaPlan {
            paradas = List.copyOf(paradas);
        }
    }

    // Llegada y salida son las comprometidas en el itinerario, no las que propuso el planificador.
    public record ParadaPlan(TipoParada tipo, int x, int y, int idPedido, int idAlmacen, int cantidad,
                             long minutoLlegada, long minutoSalida,
                             LocalDateTime fechaHoraLlegada, LocalDateTime fechaHoraSalida) {
    }

    public PlanVigente {
        rutas = List.copyOf(rutas);
        sinAsignar = Collections.unmodifiableMap(new TreeMap<>(sinAsignar));
    }

    public static PlanVigente de(long minuto, CalendarioEscenario calendario, String algoritmo,
                                 UnidadEnCurso[] unidades, Map<Integer, Integer> sinAsignar) {
        List<RutaPlan> rutas = new ArrayList<>();
        int kilometros = 0;
        double costo = 0.0;
        int paquetes = 0;
        for (UnidadEnCurso u : unidades) {
            Ruta ruta = u.ruta();
            if (ruta == null || ruta.paradas().isEmpty() || u.cantidadParadas() != ruta.paradas().size()) {
                continue;
            }
            List<ParadaPlan> paradas = new ArrayList<>(ruta.paradas().size());
            for (int k = 0; k < ruta.paradas().size(); k++) {
                Parada p = ruta.paradas().get(k);
                long llegada = u.minutoLlegadaParada(k);
                long salida = u.minutoSalidaParada(k);
                paradas.add(new ParadaPlan(p.tipo(), Ciudad.x(p.nodo()), Ciudad.y(p.nodo()), p.idPedido(),
                        p.idAlmacen(), p.cantidad(), llegada, salida, calendario.aFecha(llegada),
                        calendario.aFecha(salida)));
                if (p.tipo() == TipoParada.ENTREGA) {
                    paquetes += p.cantidad();
                }
            }
            rutas.add(new RutaPlan(u.codigo(), ruta.tipoUnidad(), ruta.kilometros(), ruta.costo(), paradas));
            kilometros += ruta.kilometros();
            costo += ruta.costo();
        }
        return new PlanVigente(minuto, calendario.aFecha(minuto), algoritmo, rutas,
                sinAsignar == null ? Map.of() : sinAsignar, kilometros, costo, paquetes);
    }
}
