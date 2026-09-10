package org.kindbox.service.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.service.dto.SolicitudSemaforo;
import org.kindbox.service.dto.SolicitudVelocidad;
import org.kindbox.service.dto.VistaParametros;
import org.kindbox.service.error.SolicitudInvalida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parametros de operacion modificables en caliente.
 *
 * <p>Los dos extremos de escritura son los que exige el enunciado. La velocidad cubre las
 * respuestas 6, 15 y 16 del cuestionario: se cambia mientras el software esta funcionando,
 * se cambia <b>por tipo de unidad</b> y no por unidad individual, y el nuevo valor se aplica
 * a partir de la siguiente iteracion de planificacion. Esa ultima garantia no la da este
 * controlador sino {@code ParametrosOperacion}: el planificador captura una instantanea
 * inmutable al arrancar cada iteracion, de modo que un cambio a mitad de iteracion nunca
 * produce una solucion evaluada con dos juegos de parametros.</p>
 *
 * <p>El semaforo cubre el requisito no funcional (d), que pide que los rangos de los colores
 * verde, ambar y rojo sean configurables por parametro.</p>
 */
@RestController
@RequestMapping("/api/parametros")
public class ControladorParametros {

    private static final Logger LOG = LoggerFactory.getLogger(ControladorParametros.class);

    private final ParametrosOperacion parametros;

    public ControladorParametros(ParametrosOperacion parametros) {
        this.parametros = parametros;
    }

    /** Parametros de operacion vigentes. */
    @GetMapping
    public VistaParametros vigentes() {
        return vista();
    }

    /**
     * Cambia en caliente la velocidad de un tipo de unidad.
     *
     * @param solicitud tipo (AUTO, MOTO, BICICLETA o su prefijo TA, TM, TB) y velocidad en Km/h
     */
    @PutMapping("/velocidad")
    public VistaParametros velocidad(@RequestBody SolicitudVelocidad solicitud) {
        if (solicitud == null || solicitud.tipo() == null) {
            throw new SolicitudInvalida("Falta el tipo de unidad. Los admitidos son AUTO, MOTO y BICICLETA");
        }
        if (solicitud.kmPorHora() == null) {
            throw new SolicitudInvalida("Falta la velocidad en kilometros por hora");
        }
        TipoUnidad tipo = resolverTipo(solicitud.tipo());
        try {
            parametros.velocidad(tipo, solicitud.kmPorHora());
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        LOG.info("Velocidad de {} cambiada a {} Km/h; se aplica en la siguiente iteracion de planificacion",
                tipo.etiqueta(), solicitud.kmPorHora());
        return vista();
    }

    /**
     * Cambia los umbrales del semaforo de almacenes.
     *
     * @param solicitud umbral inferior del intervalo ambar y del intervalo verde
     */
    @PutMapping("/semaforo")
    public VistaParametros semaforo(@RequestBody SolicitudSemaforo solicitud) {
        if (solicitud == null || solicitud.ambar() == null || solicitud.verde() == null) {
            throw new SolicitudInvalida("Faltan los umbrales ambar y verde del semaforo");
        }
        try {
            parametros.umbralesSemaforo(solicitud.ambar(), solicitud.verde());
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        LOG.info("Umbrales del semaforo cambiados a ambar={} y verde={}", solicitud.ambar(), solicitud.verde());
        return vista();
    }

    private static TipoUnidad resolverTipo(String texto) {
        String clave = texto.trim().toUpperCase(Locale.ROOT);
        for (TipoUnidad tipo : TipoUnidad.values()) {
            if (tipo.name().equals(clave) || tipo.prefijo().equals(clave)) {
                return tipo;
            }
        }
        throw new SolicitudInvalida("Tipo de unidad desconocido: " + texto
                + ". Los admitidos son AUTO, MOTO y BICICLETA, o los prefijos TA, TM y TB");
    }

    private VistaParametros vista() {
        ParametrosOperacion.Instantanea vigentes = parametros.instantanea();
        Map<String, Double> velocidades = new LinkedHashMap<>();
        List<VistaParametros.FichaTipoUnidad> tipos = new java.util.ArrayList<>();
        for (TipoUnidad tipo : TipoUnidad.values()) {
            velocidades.put(tipo.name(), vigentes.velocidad(tipo));
            tipos.add(new VistaParametros.FichaTipoUnidad(tipo.name(), tipo.prefijo(), tipo.etiqueta(),
                    tipo.capacidad(), vigentes.velocidad(tipo), tipo.velocidadPorDefecto(), tipo.costoPorKm()));
        }
        return new VistaParametros(velocidades, tipos, vigentes.umbralSemaforoAmbar(),
                vigentes.umbralSemaforoVerde(), 0, Almacen.CAPACIDAD_INTERMEDIO,
                vigentes.minutosAcondicionamiento(), vigentes.minutosAlimentacion(),
                vigentes.minutosSeparacionCambioTurno(), vigentes.minutosTrasvase(), vigentes.version());
    }
}
