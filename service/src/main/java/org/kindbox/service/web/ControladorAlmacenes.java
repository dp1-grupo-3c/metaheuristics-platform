package org.kindbox.service.web;

import java.util.List;
import org.kindbox.core.simulacion.VistaAlmacen;
import org.kindbox.service.simulacion.ServicioSimulacion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado y color de los tres almacenes de la empresa.
 *
 * <p>Sirve a dos pantallas del prototipo. En la de inicio dibuja el mapa con la ubicacion de
 * los almacenes: el central en {@code (27,14)} y los intermedios en {@code (12,38)} y
 * {@code (57,27)}. Durante la simulacion informa del nivel de ocupacion de cada uno y de su
 * color de semaforo, que es el requisito no funcional (d).</p>
 *
 * <p>Cuando no hay ninguna corrida el extremo responde el estado de reposo, con los
 * intermedios a capacidad plena, de modo que la pantalla de inicio pueda pintarse sin haber
 * arrancado nada.</p>
 */
@RestController
@RequestMapping("/api/almacenes")
public class ControladorAlmacenes {

    private final ServicioSimulacion servicio;

    public ControladorAlmacenes(ServicioSimulacion servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<VistaAlmacen> almacenes() {
        return servicio.almacenes();
    }
}
