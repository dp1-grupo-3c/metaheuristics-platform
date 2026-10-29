package org.kindbox.service.cliente;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.web.ControladorClientes;
import org.kindbox.service.web.ManejadorDeErrores;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClientesHttpTest {
    static final String PERSONA = """
        {"tipo":"PERSONA","documento":"45678912","nombre":"Ana Perez","telefono":"999111222",
         "direccion":"Calle Uno 123","x":28,"y":14}
        """;
    static final String EMPRESA = """
        {"tipo":"EMPRESA","documento":"20600000005","nombre":"Comercial Andina SAC","telefono":"014567890",
         "contacto":"Luis Rojas","direccion":"Av. Industrial 450","x":60,"y":40}
        """;

    @TempDir Path raiz;
    Path archivo;
    ObjectMapper json;
    MockMvc mvc;

    @BeforeEach void preparar() {
        archivo = raiz.resolve("datos/clientes.json");
        // Como el mapper de Spring Boot: fechas en ISO.
        json = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = mvc(new ServicioClientes(archivo, json, Clock.fixed(Instant.parse("2026-10-29T15:00:00Z"), ZoneOffset.UTC)));
    }

    MockMvc mvc(ServicioClientes servicio) {
        return MockMvcBuilders.standaloneSetup(new ControladorClientes(servicio))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    ResultActions registrar(String cuerpo) throws Exception {
        return mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    @Test void registraPersonaYEmpresaYLasBuscaPorDocumento() throws Exception {
        registrar(PERSONA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("PERSONA"))
                .andExpect(jsonPath("$.contacto").doesNotExist())
                .andExpect(jsonPath("$.registrado").value("2026-10-29T15:00:00Z"));
        registrar(EMPRESA).andExpect(status().isCreated()).andExpect(jsonPath("$.contacto").value("Luis Rojas"));
        mvc.perform(get("/api/clientes/20600000005")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Comercial Andina SAC"))
                .andExpect(jsonPath("$.x").value(60)).andExpect(jsonPath("$.y").value(40));
        mvc.perform(get("/api/clientes/45678912")).andExpect(jsonPath("$.direccion").value("Calle Uno 123"));
    }

    @Test void listaFiltraPorDocumentoONombre() throws Exception {
        registrar(PERSONA).andExpect(status().isCreated());
        registrar(EMPRESA).andExpect(status().isCreated());
        mvc.perform(get("/api/clientes")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Ana Perez"));
        mvc.perform(get("/api/clientes").param("busqueda", "andina")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("20600000005"));
        mvc.perform(get("/api/clientes").param("busqueda", "4567")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void documentoDuplicadoEsConflicto() throws Exception {
        registrar(PERSONA).andExpect(status().isCreated());
        registrar(PERSONA).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un cliente con el documento 45678912"));
    }

    @Test void validaDniRucYDatosConMensajesClaros() throws Exception {
        registrar(PERSONA.replace("45678912", "4567891")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El DNI debe tener 8 digitos"));
        registrar(EMPRESA.replace("20600000005", "20600000006")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El RUC no es valido: su digito verificador no coincide"));
        registrar(EMPRESA.replace("20600000005", "30600000005")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El RUC debe empezar con 10 o 20"));
        registrar(EMPRESA.replace("20600000005", "45678912")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El RUC debe tener 11 digitos"));
        registrar(PERSONA.replace("\"x\":28", "\"x\":71")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El nodo de entrega debe estar en la ciudad: X de 0 a 70 e Y de 0 a 50"));
        registrar(PERSONA.replace("Ana Perez", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es obligatorio y admite hasta 200 caracteres"));
        registrar(PERSONA.replace("999111222", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El telefono debe tener de 7 a 15 digitos"));
        registrar(PERSONA.replace("\"tipo\":\"PERSONA\",", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Indique el tipo de cliente: PERSONA o EMPRESA"));
        assertFalse(Files.exists(archivo));
    }

    @Test void rucDePersonaConNegocioSeAceptaComoEmpresa() throws Exception {
        registrar(EMPRESA.replace("20600000005", "10456789019")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("EMPRESA"));
    }

    @Test void buscarDocumentoInexistenteEs404() throws Exception {
        mvc.perform(get("/api/clientes/12345678")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("No hay ningun cliente con el documento 12345678"));
    }

    @Test void editarConservaDocumentoTipoYFechaDeRegistro() throws Exception {
        registrar(EMPRESA).andExpect(status().isCreated());
        String editado = EMPRESA.replace("Av. Industrial 450", "Jr. Nuevo 9").replace("\"x\":60", "\"x\":10")
                .replace("\"contacto\":\"Luis Rojas\",", "");
        mvc.perform(put("/api/clientes/20600000005").contentType(MediaType.APPLICATION_JSON).content(editado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direccion").value("Jr. Nuevo 9"))
                .andExpect(jsonPath("$.x").value(10))
                .andExpect(jsonPath("$.contacto").doesNotExist())
                .andExpect(jsonPath("$.registrado").value("2026-10-29T15:00:00Z"));
        mvc.perform(put("/api/clientes/20600000005").contentType(MediaType.APPLICATION_JSON)
                        .content(EMPRESA.replace("\"EMPRESA\"", "\"PERSONA\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El tipo de un cliente no se puede cambiar"));
        mvc.perform(put("/api/clientes/20123456786").contentType(MediaType.APPLICATION_JSON).content(EMPRESA))
                .andExpect(status().isNotFound());
    }

    @Test void losClientesSobrevivenAUnNuevoServicioSobreElMismoArchivo() throws Exception {
        registrar(PERSONA).andExpect(status().isCreated());
        registrar(EMPRESA).andExpect(status().isCreated());
        assertTrue(Files.exists(archivo));
        assertFalse(Files.exists(archivo.resolveSibling("clientes.json.tmp")));
        assertTrue(Files.readString(archivo).contains("\"registrado\" : \"2026-10-29T15:00:00Z\""));
        MockMvc reiniciado = mvc(new ServicioClientes(archivo, json, Clock.systemUTC()));
        reiniciado.perform(get("/api/clientes")).andExpect(jsonPath("$.length()").value(2));
        reiniciado.perform(get("/api/clientes/45678912")).andExpect(jsonPath("$.telefono").value("999111222"));
    }

    @Test void unArchivoIlegibleDetieneElArranqueSinSobrescribirlo() throws Exception {
        Files.createDirectories(archivo.getParent());
        Files.writeString(archivo, "{no es json");
        assertThrows(ErrorDeDatos.class, () -> new ServicioClientes(archivo, json, Clock.systemUTC()));
        assertEquals("{no es json", Files.readString(archivo));
    }
}
