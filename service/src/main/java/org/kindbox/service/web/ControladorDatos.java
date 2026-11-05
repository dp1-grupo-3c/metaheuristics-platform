package org.kindbox.service.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.service.dto.SolicitudBloqueo;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Carga de los archivos de datos al directorio configurado, sin pasar por un escenario.
@RestController
@RequestMapping("/api/datos")
public class ControladorDatos {

    private static final Logger LOG = LoggerFactory.getLogger(ControladorDatos.class);

    private final RepositorioDatos repositorio;
    private final CoordinadorEjecucion coordinador;

    public ControladorDatos(RepositorioDatos repositorio, CoordinadorEjecucion coordinador) {
        this.repositorio = repositorio;
        this.coordinador = coordinador;
    }

    @GetMapping
    public RepositorioDatos.Inventario inventario() {
        try {
            return repositorio.inventario();
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo listar el directorio de datos " + repositorio.raiz(), e);
        }
    }

    @PostMapping("/flota")
    public RepositorioDatos.ArchivoGuardado flota(@RequestParam("archivo") MultipartFile archivo) {
        String activa = coordinador.ejecucionActiva();
        if (activa != null) {
            throw new ConflictoDeEstado("Hay una ejecucion activa de " + activa
                    + "; la flota solo se puede reemplazar sin ejecuciones en curso");
        }
        return guardar(archivo, "flota.txt", repositorio::guardarFlota);
    }

    @PostMapping("/bloqueos")
    public RepositorioDatos.ArchivoGuardado bloqueos(@RequestParam("archivo") MultipartFile archivo,
                                                     @RequestParam(name = "mes", required = false) String mes) {
        YearMonth periodo = mes == null || mes.isBlank() ? null : mes(mes);
        return guardar(archivo, "bloqueos.txt", origen -> repositorio.guardarBloqueos(periodo, origen));
    }

    // Se agrega al archivo del mes: rige desde la siguiente carga, no en una corrida en curso.
    @PostMapping("/bloqueos/manual")
    public List<RepositorioDatos.ArchivoGuardado> bloqueoManual(@RequestBody SolicitudBloqueo solicitud) {
        if (solicitud == null || solicitud.nodos() == null || solicitud.nodos().size() < 2) {
            throw new SolicitudInvalida("Un bloqueo necesita al menos dos nodos");
        }
        if (solicitud.inicio() == null || solicitud.fin() == null) {
            throw new SolicitudInvalida("Faltan el inicio y el fin del bloqueo");
        }
        if (solicitud.inicio().getSecond() != 0 || solicitud.inicio().getNano() != 0
                || solicitud.fin().getSecond() != 0 || solicitud.fin().getNano() != 0) {
            throw new SolicitudInvalida("El inicio y el fin se indican hasta el minuto, sin segundos");
        }
        int[] coordenadas = new int[solicitud.nodos().size() * 2];
        for (int i = 0; i < solicitud.nodos().size(); i++) {
            SolicitudBloqueo.Nodo nodo = solicitud.nodos().get(i);
            if (nodo == null || nodo.x() == null || nodo.y() == null) {
                throw new SolicitudInvalida("Al nodo " + (i + 1) + " le falta una coordenada");
            }
            coordenadas[2 * i] = nodo.x();
            coordenadas[2 * i + 1] = nodo.y();
        }
        try {
            List<RepositorioDatos.ArchivoGuardado> guardados =
                    repositorio.agregarBloqueo(coordenadas, solicitud.inicio(), solicitud.fin());
            LOG.info("Bloqueo manual de {} a {} agregado a {}", solicitud.inicio(), solicitud.fin(), guardados);
            return guardados;
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ConflictoDeEstado(e.getMessage());
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo guardar el bloqueo en el directorio de datos " + repositorio.raiz(), e);
        }
    }

    @PostMapping("/mantenimiento")
    public RepositorioDatos.ArchivoGuardado mantenimiento(@RequestParam("archivo") MultipartFile archivo) {
        return guardar(archivo, "mantenimiento.txt",
                origen -> repositorio.guardarMantenimiento(origen.getFileName().toString(), origen));
    }

    private interface Guardado {
        RepositorioDatos.ArchivoGuardado en(Path origen) throws IOException;
    }

    // El archivo recibido se deja con su nombre original para que los errores lo citen.
    private RepositorioDatos.ArchivoGuardado guardar(MultipartFile archivo, String nombrePorDefecto, Guardado guardado) {
        if (archivo == null || archivo.isEmpty()) {
            throw new SolicitudInvalida("No llego ningun archivo en la parte 'archivo'");
        }
        Path directorio = null;
        try {
            directorio = Files.createTempDirectory("kindbox-carga-");
            Path origen = directorio.resolve(nombreLocal(archivo.getOriginalFilename(), nombrePorDefecto));
            try (InputStream entrada = archivo.getInputStream()) {
                Files.copy(entrada, origen);
            }
            RepositorioDatos.ArchivoGuardado resultado = guardado.en(origen);
            LOG.info("Archivo de datos {} guardado con {} registros", resultado.ruta(), resultado.registros());
            return resultado;
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ConflictoDeEstado(e.getMessage());
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo guardar el archivo en el directorio de datos "
                    + repositorio.raiz(), e);
        } finally {
            borrar(directorio);
        }
    }

    // Solo el ultimo segmento del nombre: algunos navegadores envian la ruta completa.
    private static String nombreLocal(String original, String porDefecto) {
        if (original == null) {
            return porDefecto;
        }
        String nombre = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).trim();
        return nombre.isEmpty() || nombre.equals(".") || nombre.equals("..") ? porDefecto : nombre;
    }

    private static YearMonth mes(String texto) {
        try {
            return YearMonth.parse(texto.trim());
        } catch (DateTimeParseException e) {
            throw new SolicitudInvalida("El mes \"" + texto + "\" no sigue el formato AAAA-MM, por ejemplo 2026-01", e);
        }
    }

    private static void borrar(Path directorio) {
        if (directorio == null) {
            return;
        }
        try (Stream<Path> flujo = Files.walk(directorio)) {
            flujo.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.warn("No se pudo borrar el temporal {}: {}", directorio, e.getMessage());
        }
    }
}
