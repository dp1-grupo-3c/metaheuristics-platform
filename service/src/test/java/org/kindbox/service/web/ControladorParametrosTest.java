package org.kindbox.service.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.AtributosAveria;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ControladorParametrosTest {

    @TempDir
    Path raiz;
    ParametrosOperacion parametros;
    RepositorioDatos repositorio;
    CoordinadorEjecucion coordinador;
    MockMvc mvc;

    @BeforeEach
    void preparar() {
        parametros = new ParametrosOperacion();
        repositorio = new RepositorioDatos(raiz);
        coordinador = new CoordinadorEjecucion();
        mvc = MockMvcBuilders.standaloneSetup(new ControladorParametros(parametros, repositorio, coordinador))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @AfterEach
    void restablecer() {
        TipoAveria.restablecerAtributos();
    }

    @Test
    void laVistaIncluyeLosAtributosDeLasAverias() throws Exception {
        mvc.perform(get("/api/parametros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averias.length()").value(3))
                .andExpect(jsonPath("$.averias[0].tipo").value(1))
                .andExpect(jsonPath("$.averias[0].atributos.minutosEnElLugar").value(120))
                .andExpect(jsonPath("$.averias[0].trasladaAlmacenCentral").value(false))
                .andExpect(jsonPath("$.averias[1].regla").value(containsString("turno siguiente")))
                .andExpect(jsonPath("$.averias[2].atributos.diasMantenimiento").value(2))
                .andExpect(jsonPath("$.averias[2].regla").value(containsString("2 dias")));
    }

    @Test
    void elCambioParcialConservaLosDemasAtributos() throws Exception {
        long antes = parametros.version();
        mvc.perform(put("/api/parametros/averias").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":2,\"turnosHastaReincorporacion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averias[1].atributos.turnosHastaReincorporacion").value(3))
                .andExpect(jsonPath("$.averias[1].atributos.minutosEnElLugar").value(240))
                .andExpect(jsonPath("$.averias[1].regla").value(containsString("mas 3 turnos")))
                .andExpect(jsonPath("$.version").value(antes + 1));
        assertEquals(new AtributosAveria(240, 3, 2), TipoAveria.TIPO_2.atributos());
    }

    @Test
    void rechazaSolicitudesInvalidasSinCambiarNada() throws Exception {
        String[] cuerpos = {
                "{\"minutosEnElLugar\":60}",
                "{\"tipo\":4,\"minutosEnElLugar\":60}",
                "{\"tipo\":1}",
                "{\"tipo\":3,\"minutosEnElLugar\":600}",
                "{\"tipo\":1,\"minutosEnElLugar\":0}",
                "{\"tipo\":3,\"diasMantenimiento\":15}"
        };
        for (String cuerpo : cuerpos) {
            mvc.perform(put("/api/parametros/averias").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigoError").value("SOLICITUD_INVALIDA"));
        }
        for (TipoAveria tipo : TipoAveria.values()) {
            assertEquals(tipo.atributosPorDefecto(), tipo.atributos());
        }
        assertEquals(0, parametros.version());
    }

    static String flota(int autos, double kmAuto, int motos, int bicicletas) {
        return """
            {"unidades":{"AUTO":{"cantidad":%d,"kmPorHora":%s},
                         "MOTO":{"cantidad":%d,"kmPorHora":25},
                         "BICICLETA":{"cantidad":%d,"kmPorHora":12}}}
            """.formatted(autos, kmAuto, motos, bicicletas);
    }

    @Test
    void laVistaIncluyeLasCantidadesDeLaFlota() throws Exception {
        mvc.perform(get("/api/parametros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidades.AUTO").value(10))
                .andExpect(jsonPath("$.cantidades.MOTO").value(15))
                .andExpect(jsonPath("$.cantidades.BICICLETA").value(12));
    }

    @Test
    void cambiaCantidadYVelocidadDeLaFlota() throws Exception {
        mvc.perform(put("/api/parametros/flota").contentType(MediaType.APPLICATION_JSON)
                        .content(flota(5, 45, 15, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidades.AUTO").value(5))
                .andExpect(jsonPath("$.cantidades.MOTO").value(15))
                .andExpect(jsonPath("$.cantidades.BICICLETA").value(20))
                .andExpect(jsonPath("$.velocidades.AUTO").value(45.0));
        assertEquals(45.0, parametros.velocidad(TipoUnidad.AUTO));
        RepositorioDatos.DatosEscenario datos = repositorio.cargar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(40, datos.unidades().size());
    }

    @Test
    void conEjecucionActivaSoloAdmiteCambiarLaVelocidad() throws Exception {
        try (var reserva = coordinador.reservar("simulacion")) {
            mvc.perform(put("/api/parametros/flota").contentType(MediaType.APPLICATION_JSON)
                            .content(flota(4, 50, 15, 12)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.mensaje").value(containsString("simulacion")));
            assertEquals(40.0, parametros.velocidad(TipoUnidad.AUTO));
            assertEquals(10, repositorio.cantidadesFlotaVigentes().get(TipoUnidad.AUTO));

            mvc.perform(put("/api/parametros/flota").contentType(MediaType.APPLICATION_JSON)
                            .content(flota(10, 50, 15, 12)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.velocidades.AUTO").value(50.0))
                    .andExpect(jsonPath("$.cantidades.AUTO").value(10));
        }
    }

    @Test
    void rechazaFlotasInvalidasSinAplicarNada() throws Exception {
        String[] cuerpos = {
                "{}",
                "{\"unidades\":{}}",
                "{\"unidades\":{\"CAMION\":{\"cantidad\":1}}}",
                "{\"unidades\":{\"AUTO\":{\"cantidad\":1},\"TA\":{\"cantidad\":2}}}",
                "{\"unidades\":{\"AUTO\":{}}}",
                "{\"unidades\":{\"AUTO\":{\"cantidad\":2.5,\"kmPorHora\":50}}}",
                "{\"unidades\":{\"AUTO\":{\"cantidad\":100}}}",
                "{\"unidades\":{\"AUTO\":{\"cantidad\":-1}}}",
                "{\"unidades\":{\"AUTO\":{\"cantidad\":3,\"kmPorHora\":0}}}",
                flota(0, 50, 0, 0)
        };
        for (String cuerpo : cuerpos) {
            mvc.perform(put("/api/parametros/flota").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(40.0, parametros.velocidad(TipoUnidad.AUTO));
        assertEquals(10, repositorio.cantidadesFlotaVigentes().get(TipoUnidad.AUTO));
        assertEquals(0, parametros.version());
    }

    @Test
    void elSemaforoAceptaElCuerpoAnteriorYLosUmbralesDeUnidades() throws Exception {
        mvc.perform(put("/api/parametros/semaforo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ambar\":100,\"verde\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.umbralSemaforoAmbar").value(100))
                .andExpect(jsonPath("$.umbralSemaforoVerde").value(300))
                .andExpect(jsonPath("$.umbralOcupacionAmbar").value(50))
                .andExpect(jsonPath("$.umbralOcupacionRojo").value(80));

        mvc.perform(put("/api/parametros/semaforo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ocupacionAmbar\":40,\"ocupacionRojo\":90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.umbralSemaforoVerde").value(300))
                .andExpect(jsonPath("$.umbralOcupacionAmbar").value(40))
                .andExpect(jsonPath("$.umbralOcupacionRojo").value(90))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void elSemaforoRechazaUmbralesIncompletosOInvalidosSinAplicarNada() throws Exception {
        String[] cuerpos = {
                "{}",
                "{\"ambar\":100}",
                "{\"ocupacionRojo\":90}",
                "{\"ambar\":300,\"verde\":100}",
                "{\"ocupacionAmbar\":0,\"ocupacionRojo\":50}",
                "{\"ocupacionAmbar\":60,\"ocupacionRojo\":101}",
                "{\"ambar\":100,\"verde\":300,\"ocupacionAmbar\":90,\"ocupacionRojo\":40}"
        };
        for (String cuerpo : cuerpos) {
            mvc.perform(put("/api/parametros/semaforo").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigoError").value("SOLICITUD_INVALIDA"));
        }
        assertEquals(250, parametros.umbralSemaforoAmbar());
        assertEquals(50, parametros.umbralOcupacionAmbar());
        assertEquals(0, parametros.version());
    }
}
