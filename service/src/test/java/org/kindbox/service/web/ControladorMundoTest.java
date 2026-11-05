package org.kindbox.service.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.configuracion.ParametrosMundo;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.service.configuracion.ServicioParametrosMundo;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ControladorMundoTest {

    static final String CUERPO = """
            {"mapa":{"largoKm":90,"anchoKm":60},
             "almacenes":[{"id":0,"x":30,"y":15},
                          {"id":1,"x":80,"y":55,"capacidad":400},
                          {"id":2,"x":57,"y":27,"capacidad":1500}]}""";

    @TempDir
    Path directorio;
    Path archivo;
    CoordinadorEjecucion coordinador;
    ServicioParametrosMundo servicio;
    MockMvc mvc;

    @BeforeEach
    void preparar() {
        archivo = directorio.resolve("mundo.json");
        coordinador = new CoordinadorEjecucion();
        servicio = new ServicioParametrosMundo(archivo, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new ControladorMundo(servicio, coordinador))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    // El mapa y los almacenes son globales: ninguna otra prueba debe verlos cambiados.
    @AfterEach
    void restablecer() {
        ParametrosMundo.delEnunciado().aplicarAlNucleo();
    }

    @Test
    void consultaLosValoresVigentes() throws Exception {
        mvc.perform(get("/api/parametros/mundo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapa.largoKm").value(70))
                .andExpect(jsonPath("$.mapa.anchoKm").value(50))
                .andExpect(jsonPath("$.almacenes[1].x").value(12))
                .andExpect(jsonPath("$.almacenes[1].capacidad").value(1000))
                .andExpect(jsonPath("$.ejecucionActiva").doesNotExist());
        try (var reserva = coordinador.reservar("operaciones diarias")) {
            mvc.perform(get("/api/parametros/mundo")).andExpect(jsonPath("$.ejecucionActiva").value("operaciones diarias"));
        }
    }

    @Test
    void elCambioSeGuardaYRigeEnElNucleo() throws Exception {
        mvc.perform(put("/api/parametros/mundo").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.mapa.largoKm").value(90))
                .andExpect(jsonPath("$.almacenes[0].capacidad").doesNotExist());

        assertEquals(90, Ciudad.LARGO_KM);
        assertEquals(91 * 61, Ciudad.TOTAL_NODOS);
        Almacen noroeste = Almacen.todos().get(1);
        assertEquals("Almacen Intermedio Nor-Oeste", noroeste.nombre());
        assertEquals(Ciudad.nodo(80, 55), noroeste.nodo());
        assertEquals(400, noroeste.capacidad());
        assertEquals(Ciudad.nodo(30, 15), Almacen.crearCentral().nodo());
        assertEquals(Integer.MAX_VALUE, Almacen.crearCentral().capacidad());
        assertTrue(Files.readString(archivo).contains("\"largoKm\" : 90"));
        // Lo que no se edita aqui se conserva y sobrevive a un reinicio.
        ParametrosMundo releidos = new ServicioParametrosMundo(archivo, new ObjectMapper()).vigente().parametros();
        assertEquals(90, releidos.mapa().largoKm());
        assertEquals(ParametrosMundo.delEnunciado().flota(), releidos.flota());
    }

    @Test
    void conUnaEjecucionActivaNoCambiaNada() throws Exception {
        String antes = Files.readString(archivo);
        try (var reserva = coordinador.reservar("simulacion")) {
            mvc.perform(put("/api/parametros/mundo").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigoError").value("CONFLICTO_ESTADO"));
        }
        assertEquals(antes, Files.readString(archivo));
        assertEquals(70, Ciudad.LARGO_KM);
    }

    @Test
    void laReservaSeLiberaAlTerminar() throws Exception {
        mvc.perform(put("/api/parametros/mundo").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isOk());
        assertEquals(null, coordinador.ejecucionActiva());
        mvc.perform(put("/api/parametros/mundo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(null, coordinador.ejecucionActiva());
    }

    @Test
    void rechazaDatosIncoherentesSinTocarElNucleo() throws Exception {
        String[] cuerpos = {
                "{}",
                "{\"mapa\":{\"largoKm\":90}}",
                "{\"mapa\":{\"largoKm\":90,\"anchoKm\":60},\"almacenes\":[{\"id\":0,\"x\":1,\"y\":1}]}",
                // Un almacen que queda fuera al achicar el mapa.
                CUERPO.replace("\"largoKm\":90", "\"largoKm\":40"),
                CUERPO.replace("\"largoKm\":90", "\"largoKm\":501"),
                CUERPO.replace("\"capacidad\":400", "\"capacidad\":0"),
                CUERPO.replace(",\"capacidad\":400", ""),
                // Dos almacenes en el mismo lugar.
                CUERPO.replace("\"x\":80,\"y\":55", "\"x\":57,\"y\":27"),
                CUERPO.replace("\"id\":2", "\"id\":9")};
        for (String cuerpo : cuerpos) {
            mvc.perform(put("/api/parametros/mundo").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(70, Ciudad.LARGO_KM);
        assertEquals(1, servicio.vigente().version());
    }
}
