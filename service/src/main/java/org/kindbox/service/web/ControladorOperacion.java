package org.kindbox.service.web;

import org.kindbox.core.operacion.ComandoOperacion;
import org.kindbox.core.operacion.SolicitudAveriaOperacion;
import org.kindbox.core.operacion.SolicitudParametrosOperacion;
import org.kindbox.core.operacion.SolicitudPedidoOperacion;
import org.kindbox.core.operacion.VistaOperacion;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.service.dto.PaginaPedidosOperacion;
import org.kindbox.service.dto.SolicitudOperacion;
import org.kindbox.service.operacion.ServicioOperacion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operaciones")
public class ControladorOperacion {
    private final ServicioOperacion servicio;

    public ControladorOperacion(ServicioOperacion servicio) { this.servicio = servicio; }

    @PostMapping
    public ResponseEntity<VistaOperacion> iniciar(@RequestBody(required = false) SolicitudOperacion solicitud) {
        return ResponseEntity.status(201).body(servicio.iniciar(solicitud));
    }

    @GetMapping("/actual")
    public VistaOperacion actual() { return servicio.actual(); }

    @GetMapping("/{id}")
    public VistaOperacion detalle(@PathVariable("id") String id) { return servicio.detalle(id); }

    @PostMapping("/{id}/detener")
    public VistaOperacion detener(@PathVariable("id") String id) { return servicio.detener(id); }

    @PostMapping("/{id}/pedidos")
    public ResponseEntity<ComandoOperacion.Resultado> pedido(@PathVariable("id") String id,
            @RequestBody SolicitudPedidoOperacion solicitud) {
        return ResponseEntity.accepted().body(servicio.aceptar(id, solicitud));
    }

    @PostMapping("/{id}/averias")
    public ResponseEntity<ComandoOperacion.Resultado> averia(@PathVariable("id") String id,
            @RequestBody SolicitudAveriaOperacion solicitud) {
        return ResponseEntity.accepted().body(servicio.aceptar(id, solicitud));
    }

    @PutMapping("/{id}/parametros")
    public ResponseEntity<ComandoOperacion.Resultado> parametros(@PathVariable("id") String id,
            @RequestBody SolicitudParametrosOperacion solicitud) {
        return ResponseEntity.accepted().body(servicio.aceptar(id, solicitud));
    }

    @GetMapping("/{id}/comandos/{comandoId}")
    public ComandoOperacion.Resultado comando(@PathVariable("id") String id,
            @PathVariable("comandoId") String comandoId) { return servicio.comando(id, comandoId); }

    @GetMapping("/{id}/pedidos")
    public PaginaPedidosOperacion pedidos(@PathVariable("id") String id,
            @RequestParam(name = "pagina", defaultValue = "0") int pagina,
            @RequestParam(name = "tamano", defaultValue = "25") int tamano,
            @RequestParam(name = "busqueda", required = false) String busqueda,
            @RequestParam(name = "soloActivos", defaultValue = "false") boolean soloActivos) {
        return servicio.pedidos(id, pagina, tamano, busqueda, soloActivos);
    }

    @GetMapping("/{id}/metricas")
    public MetricasSimulacion metricas(@PathVariable("id") String id) {
        return servicio.detalle(id).instantanea().metricas();
    }
}
