package org.kindbox.service.operacion;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperacionWebSocketTest {
    static final Path datos;
    static {
        try {
            datos = Files.createTempDirectory("kindbox-operacion-ws-");
            Files.writeString(datos.resolve("flota.txt"), "TA01\n");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("kindbox.directorio-datos", datos::toString);
    }

    @AfterAll static void limpiar() throws Exception {
        Files.deleteIfExists(datos.resolve("flota.txt"));
        Files.deleteIfExists(datos);
    }

    @LocalServerPort int puerto;
    @Autowired ObjectMapper json;

    static final class Cliente implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> mensajes = new LinkedBlockingQueue<>(20);
        final StringBuilder fragmentos = new StringBuilder();
        WebSocket conexion;
        volatile int cierre;

        @Override public void onOpen(WebSocket socket) { conexion = socket; socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence texto, boolean ultimo) {
            fragmentos.append(texto);
            if (ultimo) {
                if (!mensajes.offer(fragmentos.toString())) {
                    mensajes.poll();
                    mensajes.offer(fragmentos.toString());
                }
                fragmentos.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int codigo, String razon) {
            cierre = codigo;
            return null;
        }
        @Override public void close() { if (conexion != null) conexion.abort(); }
    }

    Cliente conectar(HttpClient http, String id) throws Exception {
        Cliente cliente = new Cliente();
        http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + puerto
                + "/ws/operaciones?contextoId=" + id), cliente).get(5, TimeUnit.SECONDS);
        return cliente;
    }

    JsonNode esperar(Cliente cliente, Predicate<JsonNode> condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < limite) {
            String mensaje = cliente.mensajes.poll(1, TimeUnit.SECONDS);
            if (mensaje == null) continue;
            JsonNode nodo = json.readTree(mensaje);
            if (condicion.test(nodo)) return nodo;
        }
        throw new AssertionError("No llego el estado esperado del canal operativo");
    }

    HttpResponse<String> enviar(HttpClient http, String ruta, String cuerpo) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + ruta))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void dosSesionesRecibenPedidosYLaReconexionObtieneEstadoActualDelContexto() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var inicio = enviar(http, "/api/operaciones", "{\"presupuestoPlanificacionMs\":50}");
            assertEquals(201, inicio.statusCode(), inicio.body());
            String id = json.readTree(inicio.body()).path("contextoId").asText();
            try (Cliente uno = conectar(http, id); Cliente dos = conectar(http, id)) {
                var inicial = esperar(uno, n -> n.path("contextoId").asText().equals(id));
                esperar(dos, n -> n.path("contextoId").asText().equals(id));
                var alta = enviar(http, "/api/operaciones/" + id + "/pedidos", """
                    {"solicitudId":"ws1","codigoCliente":"c1","nombreCliente":"Nombre",
                     "telefonoCliente":"999","direccion":"Calle Uno","x":28,"y":14,
                     "cantidad":2,"prioridad":"NORMAL"}
                    """);
                assertEquals(202, alta.statusCode(), alta.body());
                var estadoUno = esperar(uno, n -> n.path("pedidos").size() == 1);
                var estadoDos = esperar(dos, n -> n.path("pedidos").size() == 1);
                assertEquals(estadoUno.path("pedidos").get(0).path("id"), estadoDos.path("pedidos").get(0).path("id"));
                assertTrue(estadoUno.path("revision").asLong() > inicial.path("revision").asLong());
                try (Cliente reconectado = conectar(http, id)) {
                    var recuperado = esperar(reconectado, n -> n.path("pedidos").size() == 1);
                    assertTrue(recuperado.path("revision").asLong() >= estadoUno.path("revision").asLong());
                }
                assertEquals(409, enviar(http, "/api/simulaciones", "{}").statusCode());
                assertEquals(200, enviar(http, "/api/operaciones/" + id + "/detener", "{}").statusCode());
                esperar(uno, n -> n.path("estado").asText().equals("DETENIDA"));
                var segundo = enviar(http, "/api/operaciones", "{\"presupuestoPlanificacionMs\":50}");
                assertEquals(201, segundo.statusCode());
                String otroId = json.readTree(segundo.body()).path("contextoId").asText();
                try (Cliente nuevaSesion = conectar(http, otroId)) {
                    var nueva = esperar(nuevaSesion, n -> n.path("contextoId").asText().equals(otroId));
                    assertTrue(nueva.path("pedidos").isEmpty());
                    assertFalse(id.equals(otroId));
                } finally { enviar(http, "/api/operaciones/" + otroId + "/detener", "{}"); }
                assertTrue(uno.mensajes.stream().allMatch(m -> m.contains(id)));
            }
        }
    }
}
