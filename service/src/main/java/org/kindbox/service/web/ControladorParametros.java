package org.kindbox.service.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.AtributosAveria;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.service.dto.SolicitudAtributosAveria;
import org.kindbox.service.dto.SolicitudFlota;
import org.kindbox.service.dto.SolicitudSemaforo;
import org.kindbox.service.dto.SolicitudVelocidad;
import org.kindbox.service.dto.VistaParametros;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
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
    private final RepositorioDatos repositorio;
    private final CoordinadorEjecucion coordinador;

    public ControladorParametros(ParametrosOperacion parametros, RepositorioDatos repositorio,
                                 CoordinadorEjecucion coordinador) {
        this.parametros = parametros;
        this.repositorio = repositorio;
        this.coordinador = coordinador;
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
    public synchronized VistaParametros semaforo(@RequestBody SolicitudSemaforo solicitud) {
        boolean almacenes = solicitud != null && (solicitud.ambar() != null || solicitud.verde() != null);
        boolean unidades = solicitud != null
                && (solicitud.ocupacionAmbar() != null || solicitud.ocupacionRojo() != null);
        if (!almacenes && !unidades) {
            throw new SolicitudInvalida("Faltan los umbrales ambar y verde del semaforo de almacenes,"
                    + " u ocupacionAmbar y ocupacionRojo del de unidades");
        }
        if (almacenes && (solicitud.ambar() == null || solicitud.verde() == null)) {
            throw new SolicitudInvalida("Faltan los umbrales ambar y verde del semaforo");
        }
        if (unidades && (solicitud.ocupacionAmbar() == null || solicitud.ocupacionRojo() == null)) {
            throw new SolicitudInvalida("Faltan los umbrales ocupacionAmbar y ocupacionRojo del semaforo de unidades");
        }
        // Se validan los dos pares antes de aplicar para no dejar un cambio a medias.
        try {
            if (almacenes) {
                ParametrosOperacion.exigirUmbralesSemaforo(solicitud.ambar(), solicitud.verde());
            }
            if (unidades) {
                ParametrosOperacion.exigirUmbralesOcupacion(solicitud.ocupacionAmbar(), solicitud.ocupacionRojo());
            }
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        if (almacenes) {
            parametros.umbralesSemaforo(solicitud.ambar(), solicitud.verde());
            LOG.info("Umbrales del semaforo cambiados a ambar={} y verde={}", solicitud.ambar(), solicitud.verde());
        }
        if (unidades) {
            parametros.umbralesOcupacion(solicitud.ocupacionAmbar(), solicitud.ocupacionRojo());
            LOG.info("Umbrales de ocupacion de unidades cambiados a ambar={}% y rojo={}%",
                    solicitud.ocupacionAmbar(), solicitud.ocupacionRojo());
        }
        return vista();
    }

    // Se valida todo antes de aplicar; la velocidad va en caliente y la cantidad exige no tener ejecuciones.
    @PutMapping("/flota")
    public synchronized VistaParametros flota(@RequestBody SolicitudFlota solicitud) {
        if (solicitud == null || solicitud.unidades() == null || solicitud.unidades().isEmpty()) {
            throw new SolicitudInvalida("Faltan las unidades por tipo, por ejemplo"
                    + " {\"unidades\":{\"AUTO\":{\"cantidad\":10,\"kmPorHora\":40}}}");
        }
        Map<TipoUnidad, Integer> cantidades = new EnumMap<>(TipoUnidad.class);
        Map<TipoUnidad, Double> velocidades = new EnumMap<>(TipoUnidad.class);
        Set<TipoUnidad> vistos = EnumSet.noneOf(TipoUnidad.class);
        for (Map.Entry<String, SolicitudFlota.UnidadesTipo> entrada : solicitud.unidades().entrySet()) {
            TipoUnidad tipo = resolverTipo(entrada.getKey());
            if (!vistos.add(tipo)) {
                throw new SolicitudInvalida("El tipo " + tipo.name() + " aparece mas de una vez");
            }
            SolicitudFlota.UnidadesTipo unidades = entrada.getValue();
            if (unidades == null || (unidades.cantidad() == null && unidades.kmPorHora() == null)) {
                throw new SolicitudInvalida("Falta la cantidad o la velocidad de " + tipo.name());
            }
            if (unidades.cantidad() != null) {
                double cantidad = unidades.cantidad().doubleValue();
                if (cantidad != Math.rint(cantidad) || cantidad < 0 || cantidad > 99) {
                    throw new SolicitudInvalida("La cantidad de " + tipo.name()
                            + " debe ser un entero entre 0 y 99 y es " + unidades.cantidad());
                }
                cantidades.put(tipo, (int) cantidad);
            }
            if (unidades.kmPorHora() != null) {
                double kmPorHora = unidades.kmPorHora().doubleValue();
                if (!(kmPorHora > 0) || !Double.isFinite(kmPorHora)) {
                    throw new SolicitudInvalida("Velocidad invalida para " + tipo.name() + ": " + unidades.kmPorHora());
                }
                velocidades.put(tipo, kmPorHora);
            }
        }

        Map<TipoUnidad, Integer> vigentes;
        try {
            vigentes = repositorio.cantidadesFlotaVigentes();
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo leer la flota del directorio de datos", e);
        }
        Map<TipoUnidad, Integer> nuevas = new EnumMap<>(vigentes);
        nuevas.putAll(cantidades);
        if (!nuevas.equals(vigentes)) {
            String activa = coordinador.ejecucionActiva();
            if (activa != null) {
                throw new ConflictoDeEstado("Hay una ejecucion activa de " + activa
                        + "; la cantidad de unidades solo se puede cambiar sin ejecuciones en curso");
            }
            try {
                repositorio.cantidadesFlota(nuevas);
            } catch (IllegalArgumentException e) {
                throw new SolicitudInvalida(e.getMessage(), e);
            } catch (IOException e) {
                throw new ErrorDeDatos("No se pudo leer la flota del directorio de datos", e);
            }
            LOG.info("Cantidades de la flota cambiadas a {}; rigen desde la proxima ejecucion", nuevas);
        }
        for (Map.Entry<TipoUnidad, Double> entrada : velocidades.entrySet()) {
            if (parametros.velocidad(entrada.getKey()) != entrada.getValue()) {
                parametros.velocidad(entrada.getKey(), entrada.getValue());
                LOG.info("Velocidad de {} cambiada a {} Km/h", entrada.getKey().etiqueta(), entrada.getValue());
            }
        }
        return vista();
    }

    // Cambio parcial: los atributos que no llegan conservan su valor vigente.
    @PutMapping("/averias")
    public VistaParametros averias(@RequestBody SolicitudAtributosAveria solicitud) {
        if (solicitud == null || solicitud.tipo() == null) {
            throw new SolicitudInvalida("Falta el tipo de averia. Los admitidos son 1, 2 y 3");
        }
        if (solicitud.minutosEnElLugar() == null && solicitud.turnosHastaReincorporacion() == null
                && solicitud.diasMantenimiento() == null) {
            throw new SolicitudInvalida("Falta al menos uno de minutosEnElLugar, turnosHastaReincorporacion"
                    + " o diasMantenimiento");
        }
        TipoAveria tipo;
        AtributosAveria nuevos;
        try {
            tipo = TipoAveria.porCodigo(solicitud.tipo());
            synchronized (this) {
                AtributosAveria actuales = tipo.atributos();
                nuevos = new AtributosAveria(
                        valorO(solicitud.minutosEnElLugar(), actuales.minutosEnElLugar()),
                        valorO(solicitud.turnosHastaReincorporacion(), actuales.turnosHastaReincorporacion()),
                        valorO(solicitud.diasMantenimiento(), actuales.diasMantenimiento()));
                parametros.atributosAveria(tipo, nuevos);
            }
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        LOG.info("Atributos de la averia de tipo {} cambiados a {}; rigen para las averias siguientes",
                tipo.codigo(), nuevos);
        return vista();
    }

    private static int valorO(Integer valor, int vigente) {
        return valor == null ? vigente : valor;
    }

    // Regla de reincorporacion con los atributos vigentes, para mostrarla tal cual.
    static String regla(TipoAveria tipo) {
        AtributosAveria a = tipo.atributos();
        return switch (tipo) {
            case TIPO_1 -> "Queda " + a.minutosEnElLugar() + " minutos inmovilizada en el lugar y se"
                    + " reincorpora ahi mismo";
            case TIPO_2 -> "Queda " + a.minutosEnElLugar() + " minutos en el lugar, pasa al almacen central"
                    + " y vuelve al cierre del " + (a.turnosHastaReincorporacion() == 1
                    ? "turno siguiente al del incidente"
                    : "turno del incidente mas " + a.turnosHastaReincorporacion() + " turnos");
            case TIPO_3 -> "Queda " + a.minutosEnElLugar() + " minutos en el lugar, pasa al almacen central"
                    + " y, tras " + a.diasMantenimiento() + (a.diasMantenimiento() == 1 ? " dia" : " dias")
                    + " de mantenimiento, reingresa al inicio del turno de 15:00";
        };
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

    // Una flota ilegible no debe impedir consultar el resto de los parametros.
    private Map<String, Integer> cantidades() {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        try {
            repositorio.cantidadesFlotaVigentes().forEach((tipo, n) -> cantidades.put(tipo.name(), n));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("No se pudieron leer las cantidades de la flota: {}", e.getMessage());
        }
        return cantidades;
    }

    private VistaParametros vista() {
        ParametrosOperacion.Instantanea vigentes = parametros.instantanea();
        Map<String, Double> velocidades = new LinkedHashMap<>();
        List<VistaParametros.FichaTipoUnidad> tipos = new ArrayList<>();
        for (TipoUnidad tipo : TipoUnidad.values()) {
            velocidades.put(tipo.name(), vigentes.velocidad(tipo));
            tipos.add(new VistaParametros.FichaTipoUnidad(tipo.name(), tipo.prefijo(), tipo.etiqueta(),
                    tipo.capacidad(), vigentes.velocidad(tipo), tipo.velocidadPorDefecto(), tipo.costoPorKm()));
        }
        List<VistaParametros.FichaAveria> averias = new ArrayList<>();
        for (TipoAveria tipo : TipoAveria.values()) {
            averias.add(new VistaParametros.FichaAveria(tipo.codigo(), tipo.atributos(),
                    tipo.trasladaAlmacenCentral(), regla(tipo)));
        }
        return new VistaParametros(velocidades, tipos, cantidades(), vigentes.umbralSemaforoAmbar(),
                vigentes.umbralSemaforoVerde(), vigentes.umbralOcupacionAmbar(), vigentes.umbralOcupacionRojo(),
                0, Almacen.todos().stream().filter(a -> !a.central()).mapToInt(Almacen::capacidad).max()
                        .orElse(Almacen.CAPACIDAD_INTERMEDIO),
                vigentes.minutosAcondicionamiento(), vigentes.minutosAlimentacion(),
                vigentes.minutosSeparacionCambioTurno(), vigentes.minutosTrasvase(), averias, vigentes.version());
    }
}
