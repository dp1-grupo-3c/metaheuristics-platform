package org.kindbox.service.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.kindbox.core.configuracion.ParametrosMundo;
import org.kindbox.service.configuracion.ServicioParametrosMundo;
import org.kindbox.service.dto.SolicitudMundo;
import org.kindbox.service.dto.VistaMundo;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tamano del mapa y red de almacenes, editables solo sin ejecuciones en curso: cambiar el
 * mapa cambia el identificador de los nodos, de modo que una corrida no podria seguir con
 * los valores anteriores. El cambio se guarda en el archivo de parametros y rige desde la
 * siguiente corrida.
 */
@RestController
@RequestMapping("/api/parametros/mundo")
public class ControladorMundo {

    private static final Logger LOG = LoggerFactory.getLogger(ControladorMundo.class);

    private final ServicioParametrosMundo mundo;
    private final CoordinadorEjecucion coordinador;

    public ControladorMundo(ServicioParametrosMundo mundo, CoordinadorEjecucion coordinador) {
        this.mundo = mundo;
        this.coordinador = coordinador;
    }

    @GetMapping
    public VistaMundo vigente() {
        return vista(mundo.vigente());
    }

    @PutMapping
    public VistaMundo actualizar(@RequestBody SolicitudMundo solicitud) {
        if (solicitud == null || solicitud.mapa() == null || solicitud.mapa().largoKm() == null
                || solicitud.mapa().anchoKm() == null) {
            throw new SolicitudInvalida("Faltan el largo y el ancho del mapa");
        }
        // La reserva impide que una corrida arranque a mitad del cambio; responde 409 si ya hay una.
        try (CoordinadorEjecucion.Reserva reserva = coordinador.reservar("cambio de parametros del mundo")) {
            ParametrosMundo vigentes = mundo.vigente().parametros();
            ParametrosMundo nuevos = vigentes.conMapaYAlmacenes(
                    new ParametrosMundo.Mapa(solicitud.mapa().largoKm(), solicitud.mapa().anchoKm()),
                    almacenes(solicitud.almacenes(), vigentes.almacenes()));
            ServicioParametrosMundo.Versionados guardados = mundo.actualizar(nuevos);
            nuevos.aplicarAlNucleo();
            LOG.info("Parametros del mundo cambiados a la version {}: mapa {}, almacenes {}; rigen desde la"
                    + " siguiente corrida", guardados.version(), nuevos.mapa(), nuevos.almacenes());
            return new VistaMundo(guardados.version(), nuevos.mapa(), nuevos.almacenes(), null);
        }
    }

    private static List<ParametrosMundo.AlmacenParam> almacenes(List<SolicitudMundo.AlmacenMundo> solicitados,
                                                                List<ParametrosMundo.AlmacenParam> vigentes) {
        if (solicitados == null || solicitados.size() != vigentes.size()) {
            throw new SolicitudInvalida("Deben llegar los " + vigentes.size() + " almacenes de la red");
        }
        Map<Integer, SolicitudMundo.AlmacenMundo> porId = new HashMap<>();
        for (SolicitudMundo.AlmacenMundo a : solicitados) {
            if (a == null || a.id() == null || a.x() == null || a.y() == null) {
                throw new SolicitudInvalida("Cada almacen necesita su id y sus coordenadas x e y");
            }
            porId.put(a.id(), a);
        }
        List<ParametrosMundo.AlmacenParam> nuevos = new ArrayList<>();
        for (ParametrosMundo.AlmacenParam vigente : vigentes) {
            SolicitudMundo.AlmacenMundo a = porId.get(vigente.id());
            if (a == null) {
                throw new SolicitudInvalida("Falta el almacen " + vigente.id() + " (" + vigente.nombre() + ")");
            }
            nuevos.add(new ParametrosMundo.AlmacenParam(vigente.id(), vigente.nombre(), a.x(), a.y(),
                    vigente.central(), vigente.central() ? null : a.capacidad()));
        }
        return nuevos;
    }

    private VistaMundo vista(ServicioParametrosMundo.Versionados vigentes) {
        return new VistaMundo(vigentes.version(), vigentes.parametros().mapa(), vigentes.parametros().almacenes(),
                coordinador.ejecucionActiva());
    }
}
