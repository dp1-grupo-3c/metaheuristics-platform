package org.kindbox.service.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.stream.Stream;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
