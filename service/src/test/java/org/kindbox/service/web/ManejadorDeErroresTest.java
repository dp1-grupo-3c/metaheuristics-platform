package org.kindbox.service.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.simulacion.ServicioSimulacion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Codigos y cuerpo de las respuestas de error de la API.
 *
 * <p>Todavia no hay frontend, de modo que este es el momento de fijar el contrato: toda
 * respuesta de error, la levante el servicio o el propio contenedor antes de llegar al
 * controlador, trae el mismo cuerpo {@code RespuestaError} con codigo, nombre corto de la
 * condicion, mensaje en espanol, ruta e instante.</p>
 *
 * <p>Los cuatro casos que antes caian en el manejador generico y respondian 500 con el nombre
 * de una clase Java de Spring en el mensaje eran el metodo no permitido, el tipo de contenido
 * no soportado, la carga que no es multipart y la ruta desconocida. Aqui se comprueba que cada
 * uno responde con su codigo.</p>
 */
@WebMvcTest(ControladorSimulaciones.class)
class ManejadorDeErroresTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ServicioSimulacion servicio;

    @Test
    @DisplayName("Una corrida que no existe responde 404 con el cuerpo de error completo")
    void corridaInexistente() throws Exception {
        given(servicio.detalle("sim-999")).willThrow(new RecursoNoEncontrado("No existe la corrida sim-999"));

        mvc.perform(get("/api/simulaciones/sim-999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("No encontrado"))
                .andExpect(jsonPath("$.mensaje").value("No existe la corrida sim-999"))
                .andExpect(jsonPath("$.ruta").value("/api/simulaciones/sim-999"))
                .andExpect(jsonPath("$.instante").exists());
    }

    @Test
    @DisplayName("Una ruta que no existe responde 404 y no 500")
    void rutaInexistente() throws Exception {
        mvc.perform(get("/api/extremo-que-no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("No encontrado"))
                .andExpect(jsonPath("$.mensaje", containsString("/api/extremo-que-no-existe")));
    }

    @Test
    @DisplayName("Un metodo no mapeado responde 405 y dice cuales admite la ruta")
    void metodoNoPermitido() throws Exception {
        mvc.perform(put("/api/simulaciones/sim-001").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value(405))
                .andExpect(jsonPath("$.error").value("Metodo no permitido"))
                .andExpect(jsonPath("$.mensaje", containsString("PUT")))
                .andExpect(jsonPath("$.mensaje", containsString("GET")))
                .andExpect(jsonPath("$.ruta").value("/api/simulaciones/sim-001"));
    }

    @Test
    @DisplayName("Un cuerpo con un tipo que el extremo no lee responde 415")
    void tipoDeContenidoNoSoportado() throws Exception {
        mvc.perform(post("/api/simulaciones").contentType(MediaType.TEXT_PLAIN).content("arranca ya"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value(415))
                .andExpect(jsonPath("$.error").value("Tipo de contenido no soportado"))
                .andExpect(jsonPath("$.mensaje", containsString("text/plain")));
    }

    @Test
    @DisplayName("Arrancar con una corrida ya en curso responde 409")
    void corridaYaEnCurso() throws Exception {
        given(servicio.arrancar(any()))
                .willThrow(new ConflictoDeEstado("Ya hay una simulacion en curso (sim-001)."));

        mvc.perform(post("/api/simulaciones").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.error").value("Conflicto de estado"))
                .andExpect(jsonPath("$.mensaje", containsString("sim-001")));
    }

    @Test
    @DisplayName("Averiar una unidad que ya esta parada responde 409")
    void averiaSobreUnidadParada() throws Exception {
        given(servicio.registrarAveria("sim-001", "TA01", 2))
                .willThrow(new ConflictoDeEstado("La unidad TA01 ya esta inmovilizada por una averia "
                        + "de tipo 2. Espere a que se reincorpore para registrar otra."));

        mvc.perform(post("/api/simulaciones/sim-001/averias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"TA01\",\"tipo\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.error").value("Conflicto de estado"))
                .andExpect(jsonPath("$.mensaje", containsString("TA01")));
    }

    @Test
    @DisplayName("Un JSON mal formado responde 400 con un mensaje en espanol")
    void cuerpoMalFormado() throws Exception {
        mvc.perform(post("/api/simulaciones").contentType(MediaType.APPLICATION_JSON).content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Solicitud invalida"))
                .andExpect(jsonPath("$.mensaje").value("El cuerpo de la peticion no se pudo interpretar "
                        + "como JSON valido"));
    }

    @Test
    @DisplayName("Un parametro con el tipo equivocado responde 400")
    void parametroConTipoEquivocado() throws Exception {
        mvc.perform(get("/api/simulaciones/sim-001/pedidos").param("pagina", "primera"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Solicitud invalida"));
    }

    @Test
    @DisplayName("La carga masiva sin la parte 'archivo' responde 400")
    void cargaMasivaSinArchivo() throws Exception {
        mvc.perform(multipart("/api/simulaciones/sim-001/averias/masivo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Solicitud invalida"));
    }

    @Test
    @DisplayName("La carga masiva que no es multipart responde 400 y no 500")
    void cargaMasivaQueNoEsMultipart() throws Exception {
        mvc.perform(post("/api/simulaciones/sim-001/averias/masivo")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Solicitud invalida"))
                .andExpect(jsonPath("$.mensaje", containsString("archivo")));
    }
}
