package org.kindbox.service.web;

import java.nio.file.Files;
import java.time.Instant;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.service.dto.RespuestaSalud;
import org.kindbox.service.simulacion.Corrida;
import org.kindbox.service.simulacion.FabricaMotor;
import org.kindbox.service.simulacion.RegistroAlgoritmos;
import org.kindbox.service.simulacion.ServicioSimulacion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comprobacion de vida del servicio.
 *
 * <p>Ademas de responder que el proceso esta arriba informa de lo que el equipo necesita
 * verificar antes de una presentacion: que el directorio de datos se resolvio y es legible,
 * que motor de simulacion esta enchufado, que algoritmos hay disponibles, si hay una corrida
 * en curso y cuantos dispositivos siguen el canal de retransmision.</p>
 */
@RestController
@RequestMapping("/api/salud")
public class ControladorSalud {

    private final RepositorioDatos repositorio;
    private final ServicioSimulacion servicio;
    private final FabricaMotor fabrica;
    private final RegistroAlgoritmos algoritmos;
    private final ManejadorWebSocketSimulacion manejador;

    public ControladorSalud(RepositorioDatos repositorio, ServicioSimulacion servicio, FabricaMotor fabrica,
                            RegistroAlgoritmos algoritmos, ManejadorWebSocketSimulacion manejador) {
        this.repositorio = repositorio;
        this.servicio = servicio;
        this.fabrica = fabrica;
        this.algoritmos = algoritmos;
        this.manejador = manejador;
    }

    @GetMapping
    public RespuestaSalud salud() {
        Corrida activa = servicio.corridaActiva();
        return new RespuestaSalud("vivo", Instant.now(),
                repositorio.raiz().toAbsolutePath().toString(),
                Files.isDirectory(repositorio.raiz()),
                fabrica.descripcion(), algoritmos.nombres(),
                activa == null ? null : activa.id(),
                servicio.listar().size(),
                manejador.clientesConectados());
    }
}
