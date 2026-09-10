package org.kindbox.service.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.service.dto.DetalleCorrida;
import org.kindbox.service.dto.PaginaPedidos;
import org.kindbox.service.dto.RespuestaAveria;
import org.kindbox.service.dto.ResultadoCargaAverias;
import org.kindbox.service.dto.ResumenCorrida;
import org.kindbox.service.dto.SolicitudAveria;
import org.kindbox.service.dto.SolicitudSimulacion;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.simulacion.ServicioSimulacion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Extremos de las corridas de simulacion.
 *
 * <p>Cubre las tres pantallas del prototipo del visualizador: la de inicio arranca la
 * corrida, la barra superior y los paneles laterales la consultan y la cancelan, y el modal
 * de fin de simulacion lee su resumen final.</p>
 */
@RestController
@RequestMapping("/api/simulaciones")
public class ControladorSimulaciones {

    private final ServicioSimulacion servicio;

    public ControladorSimulaciones(ServicioSimulacion servicio) {
        this.servicio = servicio;
    }

    /**
     * Arranca una corrida. Responde 201 con la cabecera, o 409 si ya hay una en curso.
     *
     * <p>El cuerpo admite ser vacio: en ese caso se arranca la simulacion de cinco dias con
     * los valores por defecto de {@code application.properties}.</p>
     */
    @PostMapping
    public ResponseEntity<ResumenCorrida> arrancar(@RequestBody(required = false) SolicitudSimulacion solicitud) {
        ResumenCorrida resumen = servicio.arrancar(solicitud);
        return ResponseEntity.status(HttpStatus.CREATED).body(resumen);
    }

    /** Corridas conocidas, de la mas reciente a la mas antigua. */
    @GetMapping
    public List<ResumenCorrida> listar() {
        return servicio.listar();
    }

    /** Estado e instantanea actual de una corrida. */
    @GetMapping("/{id}")
    public DetalleCorrida detalle(@PathVariable("id") String id) {
        return servicio.detalle(id);
    }

    /** Indicadores acumulados: tiempos por prioridad, costo, kilometros y semaforos. */
    @GetMapping("/{id}/metricas")
    public MetricasSimulacion metricas(@PathVariable("id") String id) {
        return servicio.metricas(id);
    }

    /** Resumen final de la corrida. Responde 409 mientras siga en curso. */
    @GetMapping("/{id}/resultado")
    public ResultadoSimulacion resultado(@PathVariable("id") String id) {
        return servicio.resultado(id);
    }

    /** Cancela la corrida. Es el boton Cancelar de la barra superior. */
    @DeleteMapping("/{id}")
    public ResumenCorrida cancelar(@PathVariable("id") String id) {
        return servicio.cancelar(id);
    }

    /**
     * Tabla de pedidos activos, con buscador y paginacion.
     *
     * @param busqueda    texto libre que casa con identificador, cliente, coordenadas o plazo
     * @param pagina      numero de pagina, empezando en cero
     * @param tamano      filas por pagina
     * @param soloActivos si se excluyen los pedidos ya entregados; cierto por defecto
     */
    @GetMapping("/{id}/pedidos")
    public PaginaPedidos pedidos(@PathVariable("id") String id,
                                 @RequestParam(name = "busqueda", required = false) String busqueda,
                                 @RequestParam(name = "pagina", required = false) Integer pagina,
                                 @RequestParam(name = "tamano", required = false) Integer tamano,
                                 @RequestParam(name = "soloActivos", defaultValue = "true") boolean soloActivos) {
        return servicio.pedidos(id, busqueda, pagina, tamano, soloActivos);
    }

    /** Registro individual de una averia: placa y tipo. */
    @PostMapping("/{id}/averias")
    public RespuestaAveria registrarAveria(@PathVariable("id") String id, @RequestBody SolicitudAveria solicitud) {
        if (solicitud == null) {
            throw new SolicitudInvalida("Falta el cuerpo con la placa y el tipo de averia");
        }
        return servicio.registrarAveria(id, solicitud.placa(), solicitud.tipo());
    }

    /**
     * Carga masiva de averias por archivo, con el formato {@code ##d##h##m:TTNN:T}.
     *
     * @param archivo archivo de texto enviado como parte multipart
     */
    @PostMapping("/{id}/averias/masivo")
    public ResultadoCargaAverias cargarAverias(@PathVariable("id") String id,
                                               @RequestParam("archivo") MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new SolicitudInvalida("No llego ningun archivo en la parte 'archivo'");
        }
        String contenido;
        try {
            contenido = new String(archivo.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SolicitudInvalida("No se pudo leer el archivo de averias: " + e.getMessage(), e);
        }
        return servicio.cargarAverias(id, archivo.getOriginalFilename(), contenido);
    }
}
