package org.kindbox.service.web;

import java.util.List;
import org.kindbox.service.cliente.Cliente;
import org.kindbox.service.cliente.ServicioClientes;
import org.kindbox.service.cliente.SolicitudCliente;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de clientes de la operacion diaria. El documento (DNI o RUC) identifica al
 * cliente y es el codigo que se envia como {@code codigoCliente} al registrar un pedido.
 */
@RestController
@RequestMapping("/api/clientes")
public class ControladorClientes {
    private final ServicioClientes servicio;

    public ControladorClientes(ServicioClientes servicio) { this.servicio = servicio; }

    @PostMapping
    public ResponseEntity<Cliente> registrar(@RequestBody SolicitudCliente solicitud) {
        return ResponseEntity.status(201).body(servicio.registrar(solicitud));
    }

    @GetMapping
    public List<Cliente> listar(@RequestParam(name = "busqueda", required = false) String busqueda) {
        return servicio.listar(busqueda);
    }

    @GetMapping("/{documento}")
    public Cliente buscar(@PathVariable("documento") String documento) { return servicio.buscar(documento); }

    @PutMapping("/{documento}")
    public Cliente actualizar(@PathVariable("documento") String documento, @RequestBody SolicitudCliente solicitud) {
        return servicio.actualizar(documento, solicitud);
    }
}
