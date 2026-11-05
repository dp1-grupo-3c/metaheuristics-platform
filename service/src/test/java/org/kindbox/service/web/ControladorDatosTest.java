package org.kindbox.service.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ControladorDatosTest {

    static final String BLOQUEO = "01d08h00m-01d18h30m:12,20,13,20,13,21\n";

    @TempDir
    Path raiz;
    CoordinadorEjecucion coordinador;
    MockMvc mvc;

    @BeforeEach
    void preparar() {
        coordinador = new CoordinadorEjecucion();
        mvc = MockMvcBuilders.standaloneSetup(new ControladorDatos(new RepositorioDatos(raiz), coordinador))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    static MockMultipartFile archivo(String nombre, String contenido) {
        return new MockMultipartFile("archivo", nombre, "text/plain", contenido.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void inventarioYCargaDeFlota() throws Exception {
        mvc.perform(get("/api/datos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flota").doesNotExist())
                .andExpect(jsonPath("$.bloqueos").isEmpty());

        mvc.perform(multipart("/api/datos/flota").file(archivo("flota.csv", "AUTO,3\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruta").value("flota.txt"))
                .andExpect(jsonPath("$.registros").value(3))
                .andExpect(jsonPath("$.reemplazado").value(false));
        assertEquals("AUTO,3\n", Files.readString(raiz.resolve("flota.txt")));

        mvc.perform(get("/api/datos")).andExpect(jsonPath("$.flota").value("flota.txt"));
    }

    @Test
    void laFlotaNoSeReemplazaConUnaEjecucionActiva() throws Exception {
        try (var reserva = coordinador.reservar("operaciones diarias")) {
            mvc.perform(multipart("/api/datos/flota").file(archivo("flota.txt", "AUTO,3\n")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigoError").value("CONFLICTO_ESTADO"));
        }
        assertFalse(Files.exists(raiz.resolve("flota.txt")));
    }

    @Test
    void cargaDeBloqueosConYSinMes() throws Exception {
        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("202601.bloqueadas", BLOQUEO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruta").value("bloqueos/202601.bloqueadas"))
                .andExpect(jsonPath("$.registros").value(1));

        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("cualquiera.txt", BLOQUEO)).param("mes", "2026-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruta").value("bloqueos/202602.bloqueadas"));

        mvc.perform(get("/api/datos"))
                .andExpect(jsonPath("$.bloqueos[0]").value("bloqueos/202601.bloqueadas"))
                .andExpect(jsonPath("$.bloqueos[1]").value("bloqueos/202602.bloqueadas"));
    }

    @Test
    void erroresDeLaCarga() throws Exception {
        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("cualquiera.txt", BLOQUEO)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("x.txt", BLOQUEO)).param("mes", "2026-13"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("202601.bloqueadas", "basura\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("202601.bloqueadas")));
        mvc.perform(multipart("/api/datos/flota").file(archivo("flota.txt", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/datos/mantenimiento").file(archivo("plan.txt", "20260901:TA05\n")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/datos/mantenimiento"))
                .andExpect(status().isBadRequest());

        Files.createDirectories(raiz.resolve("bloqueos/2026"));
        Files.writeString(raiz.resolve("bloqueos/202603.bloqueadas"), "");
        Files.writeString(raiz.resolve("bloqueos/2026/bloqueo.2603.txt"), "");
        mvc.perform(multipart("/api/datos/bloqueos").file(archivo("202603.bloqueadas", BLOQUEO)))
                .andExpect(status().isConflict());
    }

    @Test
    void cargaDeMantenimiento() throws Exception {
        mvc.perform(multipart("/api/datos/mantenimiento").file(archivo("mant.preventivo.09.10", "20260901:TA05\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruta").value("mantenimiento/2026/mant.preventivo.09.10"))
                .andExpect(jsonPath("$.registros").value(1));
        mvc.perform(get("/api/datos"))
                .andExpect(jsonPath("$.mantenimiento[0]").value("mantenimiento/2026/mant.preventivo.09.10"));
    }
}
