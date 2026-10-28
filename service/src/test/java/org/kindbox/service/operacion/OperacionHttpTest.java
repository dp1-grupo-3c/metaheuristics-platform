package org.kindbox.service.operacion;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.operacion.ComandoOperacion;
import org.kindbox.core.operacion.SolicitudAveriaOperacion;
import org.kindbox.core.operacion.SolicitudPedidoOperacion;
import org.kindbox.core.operacion.PrioridadPedido;
import org.kindbox.core.operacion.VistaOperacion;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.kindbox.service.dto.SolicitudOperacion;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.simulacion.FabricaMotor;
import org.kindbox.service.simulacion.PublicadorDeEstado;
import org.kindbox.service.simulacion.RegistroAlgoritmos;
import org.kindbox.service.simulacion.ServicioSimulacion;
import org.kindbox.service.web.ControladorOperacion;
import org.kindbox.service.web.ManejadorDeErrores;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperacionHttpTest {
    @TempDir Path raiz;
    ServicioOperacion servicio;
    CoordinadorEjecucion coordinador;
    RepositorioDatos repositorio;
    RegistroAlgoritmos algoritmos;
    MockMvc mvc;
    ObjectMapper json;

    @BeforeEach void preparar() throws Exception {
        Files.writeString(raiz.resolve("flota.txt"), "TA01\n");
        repositorio = new RepositorioDatos(raiz);
        algoritmos = new RegistroAlgoritmos();
        coordinador = new CoordinadorEjecucion();
        servicio = new ServicioOperacion(repositorio, algoritmos, coordinador,
                Clock.fixed(Instant.parse("2026-10-28T17:00:00Z"), ZoneOffset.UTC), false);
        json = Jackson2ObjectMapperBuilder.json().build();
        mvc = MockMvcBuilders.standaloneSetup(new ControladorOperacion(servicio))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    @AfterEach void cerrar() { servicio.close(); }

    String iniciar() throws Exception {
        var respuesta = mvc.perform(post("/api/operaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presupuestoPlanificacionMs\":50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("EN_CURSO"))
                .andExpect(jsonPath("$.zonaHoraria").value("America/Lima"))
                .andExpect(jsonPath("$.pedidos").isEmpty()).andReturn();
        return json.readTree(respuesta.getResponse().getContentAsString()).path("contextoId").asText();
    }

    String cuerpo(String clave, int cantidad) {
        return """
            {"solicitudId":"%s","codigoCliente":"c1","nombreCliente":"Nombre",
             "telefonoCliente":"999","direccion":"Calle Uno","x":28,"y":14,
             "cantidad":%d,"prioridad":"NORMAL"}
            """.formatted(clave, cantidad);
    }

    @Test void altaConsultaIdempotenciaYPaginacionFuncionanPorHttp() throws Exception {
        String id = iniciar();
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("nuevo", 2)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.estado").value("ACEPTADO"));
        servicio.actualizar();
        mvc.perform(get("/api/operaciones/{id}/comandos/nuevo", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("APLICADO"))
                .andExpect(jsonPath("$.pedidoId").value(1));
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("nuevo", 2)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.pedidoId").value(1));
        mvc.perform(get("/api/operaciones/{id}/pedidos", id).param("tamano", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPedidos").value(1))
                .andExpect(jsonPath("$.pedidos[0].nombreCliente").value("Nombre"))
                .andExpect(jsonPath("$.pedidos[0].direccion").value("Calle Uno"));
        mvc.perform(get("/api/operaciones/{id}/pedidos", id).param("pagina", "1").param("tamano", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos").isEmpty());
        mvc.perform(get("/api/operaciones/{id}/metricas", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidosRegistrados").value(1));
    }

    @Test void validacionYConflictosTienenRespuestaEstable() throws Exception {
        String id = iniciar();
        mvc.perform(post("/api/operaciones")).andExpect(status().isConflict());
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo("invalido", 0))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo("invalido", 1).replace("\"x\":28", "\"x\":71"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo("nuevo", 1))).andExpect(status().isAccepted());
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo("nuevo", 2))).andExpect(status().isConflict());
        mvc.perform(get("/api/operaciones/{id}/pedidos", id).param("pagina", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/operaciones/{id}/pedidos", id).param("tamano", "501"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/operaciones/{id}/comandos/inexistente", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/operaciones/desconocida")).andExpect(status().isNotFound());
    }

    @Test void aplicaAveriaYParametrosYPermiteDescubrirOperacionActual() throws Exception {
        String id = iniciar();
        mvc.perform(get("/api/operaciones/actual")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contextoId").value(id));
        mvc.perform(post("/api/operaciones/{id}/averias", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solicitudId\":\"a1\",\"placa\":\"TA01\",\"tipo\":1}"))
                .andExpect(status().isAccepted());
        mvc.perform(put("/api/operaciones/{id}/parametros", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solicitudId\":\"p1\",\"velocidades\":{\"AUTO\":20},\"ambar\":100,\"verde\":200}"))
                .andExpect(status().isAccepted());
        servicio.actualizar();
        mvc.perform(get("/api/operaciones/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.instantanea.unidades[0].estado").value("AVERIADA"))
                .andExpect(jsonPath("$.incidentes[0].tipo").value(1))
                .andExpect(jsonPath("$.parametros.umbralSemaforoVerde").value(200));
        mvc.perform(post("/api/operaciones/{id}/averias", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solicitudId\":\"a2\",\"placa\":\"TA99\",\"tipo\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test void detenerConservaHistorialYLiberaLaReserva() throws Exception {
        String id = iniciar();
        mvc.perform(post("/api/operaciones/{id}/detener", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DETENIDA"));
        mvc.perform(post("/api/operaciones/{id}/pedidos", id).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo("nuevo", 1))).andExpect(status().isConflict());
        String segundo = iniciar();
        assertNotEquals(id, segundo);
        mvc.perform(get("/api/operaciones/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DETENIDA"));
    }

    @Test void unErrorDeConfiguracionNoOcupaLaReserva() throws Exception {
        mvc.perform(post("/api/operaciones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zonaHoraria\":\"inexistente\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/operaciones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"presupuestoPlanificacionMs\":18001}")).andExpect(status().isBadRequest());
        iniciar();
    }

    @Test void planificadorRealAsignaElPedidoNuevoSinFrontend() throws Exception {
        String id = iniciar();
        servicio.aceptar(id, new SolicitudPedidoOperacion("real", "c1", "Cliente", "999", "Direccion",
                28, 14, 2, PrioridadPedido.NORMAL));
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            servicio.actualizar();
            var vista = servicio.detalle(id);
            assertEquals(VistaOperacion.Estado.EN_CURSO, vista.estado(), vista.error());
            if (!vista.pedidos().getFirst().estado().name().equals("REGISTRADO")) break;
            Thread.sleep(10);
        } while (System.nanoTime() < limite);
        assertFalse(servicio.detalle(id).pedidos().getFirst().estado().name().equals("REGISTRADO"));
        assertTrue(servicio.detalle(id).instantanea().metricas().particionCoherente());
    }

    @Test void exclusividadConSimulacionesNoInterrumpeLaOperacion() throws Exception {
        PublicadorDeEstado publicador = new PublicadorDeEstado() {
            @Override public ObservadorSimulacion observadorDe(String id) { return new ObservadorSimulacion() { }; }
            @Override public void publicar(String tipo, String id, Object carga) { }
        };
        var simulaciones = new ServicioSimulacion(repositorio, new org.kindbox.core.modelo.ParametrosOperacion(),
                new FabricaMotor(), algoritmos, publicador, new PropiedadesKindBox(), coordinador);
        try {
            String id = iniciar();
            assertThrows(ConflictoDeEstado.class, () -> simulaciones.arrancar(null));
            assertEquals(VistaOperacion.Estado.EN_CURSO, servicio.detalle(id).estado());
            servicio.detener(id);
            assertThrows(RuntimeException.class, () -> simulaciones.arrancar(null));
            iniciar();
        } finally { simulaciones.cerrar(); }
    }

    @Test void unaReservaSimuladaBloqueaElInicioOperativoHastaLiberarse() throws Exception {
        try (var reserva = coordinador.reservar("simulacion")) {
            mvc.perform(post("/api/operaciones")).andExpect(status().isConflict());
        }
        iniciar();
    }
}
