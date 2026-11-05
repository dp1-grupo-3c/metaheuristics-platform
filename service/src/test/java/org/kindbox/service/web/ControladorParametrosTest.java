package org.kindbox.service.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.AtributosAveria;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoAveria;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ControladorParametrosTest {

    ParametrosOperacion parametros;
    MockMvc mvc;

    @BeforeEach
    void preparar() {
        parametros = new ParametrosOperacion();
        mvc = MockMvcBuilders.standaloneSetup(new ControladorParametros(parametros))
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
}
