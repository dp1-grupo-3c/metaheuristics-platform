package org.kindbox.service.configuracion;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.configuracion.ParametrosMundo;
import org.kindbox.core.configuracion.ParametrosMundoInvalidos;

class ServicioParametrosMundoTest {

    @TempDir Path raiz;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sinArchivoCreaUnoConLosValoresDelEnunciado() throws Exception {
        Path archivo = raiz.resolve("config/parametros-mundo.json");
        ServicioParametrosMundo servicio = new ServicioParametrosMundo(archivo, json);
        assertTrue(Files.exists(archivo));
        assertEquals(ParametrosMundo.delEnunciado(), servicio.vigente().parametros());
        // Lo escrito vuelve a leerse igual.
        assertEquals(ParametrosMundo.delEnunciado(), new ServicioParametrosMundo(archivo, json).vigente().parametros());
    }

    @Test
    void elArchivoDelRepositorioCoincideConElEnunciado() throws Exception {
        Path delRepo = Path.of("..", "config", "parametros-mundo.json");
        assertTrue(Files.exists(delRepo), "falta " + delRepo.toAbsolutePath());
        ServicioParametrosMundo servicio = new ServicioParametrosMundo(raiz.resolve("x.json"), json);
        assertEquals(ParametrosMundo.delEnunciado(), servicio.interpretar(Files.readString(delRepo)));
    }

    @Test
    void recargarAplicaLaEdicionManualYSubeLaVersion() throws Exception {
        Path archivo = raiz.resolve("p.json");
        ServicioParametrosMundo servicio = new ServicioParametrosMundo(archivo, json);
        Files.writeString(archivo, Files.readString(archivo).replace("\"largoKm\" : 70", "\"largoKm\" : 80"));
        ServicioParametrosMundo.Versionados v = servicio.recargar();
        assertEquals(2, v.version());
        assertEquals(80, v.parametros().mapa().largoKm());
    }

    @Test
    void unaEdicionInvalidaNoCambiaLaVersionVigenteYReportaTodosLosErrores() throws Exception {
        Path archivo = raiz.resolve("p.json");
        ServicioParametrosMundo servicio = new ServicioParametrosMundo(archivo, json);
        Files.writeString(archivo, Files.readString(archivo)
                .replace("\"largoKm\" : 70", "\"largoKm\" : 20")
                .replace("\"verdeDesdePorcentaje\" : 50", "\"verdeDesdePorcentaje\" : 10"));
        ParametrosMundoInvalidos error = assertThrows(ParametrosMundoInvalidos.class, servicio::recargar);
        assertTrue(error.errores().size() >= 3, error.errores().toString());
        assertTrue(error.errores().get(0).startsWith("p.json: "));
        assertEquals(1, servicio.vigente().version());
    }

    @Test
    void rechazaCamposDesconocidos() throws Exception {
        Path archivo = raiz.resolve("p.json");
        ServicioParametrosMundo servicio = new ServicioParametrosMundo(archivo, json);
        String texto = Files.readString(archivo).replace("\"largoKm\"", "\"largoKms\"");
        assertThrows(IllegalArgumentException.class, () -> servicio.interpretar(texto));
    }
}
