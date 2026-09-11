package org.kindbox.service.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Fallos que levanta el contenedor y que no se pueden provocar desde {@code MockMvc} con una
 * peticion ordinaria, porque ocurren en la capa que {@code MockMvc} sustituye.
 *
 * <p>Se prueban con un controlador de juguete que lanza cada excepcion y el manejador real
 * montado como consejo, que es exactamente el camino que sigue la excepcion en produccion. Son
 * el archivo que supera el limite de subida, la peticion que no es multipart y el recurso
 * estatico inexistente; los tres respondian 500 con el nombre de una clase Java de Spring en el
 * cuerpo.</p>
 */
class ManejadorDeErroresDirectoTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControladorDeJuguete())
            .setControllerAdvice(new ManejadorDeErrores())
            .build();

    @Test
    @DisplayName("Un archivo por encima del limite responde 413 y dice el maximo")
    void archivoDemasiadoGrande() throws Exception {
        mvc.perform(get("/juguete/archivo-grande"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value(413))
                .andExpect(jsonPath("$.error").value("Archivo demasiado grande"))
                .andExpect(jsonPath("$.mensaje", containsString("1048576")))
                .andExpect(jsonPath("$.ruta").value("/juguete/archivo-grande"));
    }

    @Test
    @DisplayName("Una peticion que no es multipart responde 400 y explica que se espera")
    void peticionQueNoEsMultipart() throws Exception {
        mvc.perform(get("/juguete/sin-multipart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Solicitud invalida"))
                .andExpect(jsonPath("$.mensaje", containsString("multipart/form-data")));
    }

    @Test
    @DisplayName("Un recurso inexistente responde 404 y no 500")
    void recursoInexistente() throws Exception {
        mvc.perform(get("/juguete/recurso"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("No encontrado"))
                .andExpect(jsonPath("$.mensaje", containsString("/juguete/recurso")));
    }

    /** Controlador que solo existe para que cada excepcion recorra el camino real del consejo. */
    @RestController
    static class ControladorDeJuguete {

        @GetMapping("/juguete/archivo-grande")
        String archivoGrande() {
            throw new MaxUploadSizeExceededException(1_048_576L);
        }

        @GetMapping("/juguete/sin-multipart")
        String sinMultipart() {
            throw new MultipartException("Current request is not a multipart request");
        }

        @GetMapping("/juguete/recurso")
        String recurso() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/juguete/recurso");
        }
    }
}
