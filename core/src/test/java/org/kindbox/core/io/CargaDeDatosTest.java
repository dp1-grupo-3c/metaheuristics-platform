package org.kindbox.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.modelo.TipoUnidad;

class CargaDeDatosTest {

    private static final String BLOQUEO = "01d08h00m-01d18h30m:12,20,13,20,13,21\n";

    @TempDir
    Path raiz;
    @TempDir
    Path entrada;
    RepositorioDatos repositorio;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioDatos(raiz);
    }

    private Path archivo(String nombre, String contenido) throws IOException {
        return Files.writeString(entrada.resolve(nombre), contenido);
    }

    private Path enRaiz(String ruta, String contenido) throws IOException {
        Path destino = raiz.resolve(ruta);
        Files.createDirectories(destino.getParent());
        return Files.writeString(destino, contenido);
    }

    // Ningun temporal de escritura debe quedar en el directorio de datos.
    private void sinTemporales() throws IOException {
        try (Stream<Path> flujo = Files.walk(raiz)) {
            assertTrue(flujo.noneMatch(p -> p.getFileName().toString().startsWith(".carga-")));
        }
    }

    @Test
    @DisplayName("El inventario lista los archivos que usaria la carga, relativos a la raiz")
    void inventario() throws Exception {
        RepositorioDatos.Inventario vacio = repositorio.inventario();
        assertNull(vacio.flota());
        assertTrue(vacio.ventas().isEmpty() && vacio.bloqueos().isEmpty() && vacio.mantenimiento().isEmpty());

        enRaiz("flota.csv", "AUTO,2\n");
        enRaiz("ventas/ventas202601", "");
        enRaiz("ventas/2026/ventas.202602.txt", "");
        enRaiz("ventas/notas.md", "");
        enRaiz("bloqueos/202601.bloqueadas", BLOQUEO);
        enRaiz("bloqueos/bloqueo.2602.txt", BLOQUEO);
        enRaiz("bloqueos/leeme.txt", "");
        enRaiz("mantenimiento/2026/mant.preventivo.09.10", "20260901:TA01\n");
        enRaiz("mant.preventivo.11.12", "20261101:TA01\n");

        RepositorioDatos.Inventario inventario = repositorio.inventario();
        assertEquals("flota.csv", inventario.flota());
        assertEquals(List.of("ventas/2026/ventas.202602.txt", "ventas/ventas202601"), inventario.ventas());
        assertEquals(List.of("bloqueos/202601.bloqueadas", "bloqueos/bloqueo.2602.txt"), inventario.bloqueos());
        assertEquals(List.of("mant.preventivo.11.12", "mantenimiento/2026/mant.preventivo.09.10"),
                inventario.mantenimiento());

        enRaiz("flota.txt", "AUTO,1\n");
        assertEquals("flota.txt", repositorio.inventario().flota());
    }

    @Test
    @DisplayName("Guardar la flota valida, reemplaza flota.txt y descarta el ajuste de cantidades")
    void guardarFlota() throws Exception {
        repositorio.cantidadesFlota(Map.of(TipoUnidad.AUTO, 1));
        RepositorioDatos.ArchivoGuardado primero = repositorio.guardarFlota(archivo("flota.csv", "AUTO,3\nMOTO,2\n"));
        assertEquals(new RepositorioDatos.ArchivoGuardado("flota.txt", 5, false), primero);
        assertEquals("AUTO,3\nMOTO,2\n", Files.readString(raiz.resolve("flota.txt")));
        assertEquals(3, repositorio.cantidadesFlotaVigentes().get(TipoUnidad.AUTO));

        assertTrue(repositorio.guardarFlota(archivo("otra.txt", "TA07\n")).reemplazado());
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarFlota(archivo("mala.txt", "CAMION,3\n")));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarFlota(entrada.resolve("no-existe.txt")));
        assertEquals("TA07\n", Files.readString(raiz.resolve("flota.txt")));
        sinTemporales();
    }

    @Test
    @DisplayName("Guardar bloqueos crea el archivo canonico o reemplaza el existente del mes")
    void guardarBloqueos() throws Exception {
        RepositorioDatos.ArchivoGuardado nuevo = repositorio.guardarBloqueos(null, archivo("202601.bloqueadas", BLOQUEO));
        assertEquals(new RepositorioDatos.ArchivoGuardado("bloqueos/202601.bloqueadas", 1, false), nuevo);

        Path existente = enRaiz("bloqueos/2026/bloqueo.2602.txt", "");
        RepositorioDatos.ArchivoGuardado reemplazo = repositorio.guardarBloqueos(YearMonth.of(2026, 2),
                archivo("cualquiera.txt", BLOQUEO + "02d00h00m-02d01h00m:1,1,1,3\n"));
        assertEquals(new RepositorioDatos.ArchivoGuardado("bloqueos/2026/bloqueo.2602.txt", 2, true), reemplazo);
        assertTrue(Files.readString(existente).startsWith("01d08h00m"));
        assertFalse(Files.exists(raiz.resolve("bloqueos/202602.bloqueadas")));

        RepositorioDatos.DatosEscenario datos = repositorio.cargarMes(YearMonth.of(2026, 2));
        assertEquals(2, datos.bloqueos().size());
        sinTemporales();
    }

    @Test
    @DisplayName("Bloqueos ambiguos, mal formados o sin mes deducible no tocan el directorio")
    void guardarBloqueosRechazos() throws Exception {
        enRaiz("bloqueos/202603.bloqueadas", "# a\n");
        enRaiz("bloqueos/2026/bloqueo.2603.txt", "# b\n");
        assertThrows(IllegalStateException.class,
                () -> repositorio.guardarBloqueos(YearMonth.of(2026, 3), archivo("x.txt", BLOQUEO)));
        assertEquals("# a\n", Files.readString(raiz.resolve("bloqueos/202603.bloqueadas")));
        assertEquals("# b\n", Files.readString(raiz.resolve("bloqueos/2026/bloqueo.2603.txt")));

        assertThrows(IllegalArgumentException.class,
                () -> repositorio.guardarBloqueos(YearMonth.of(2026, 4), archivo("y.txt", "01d08h00m:12,20\n")));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarBloqueos(null, archivo("z.txt", BLOQUEO)));
        assertFalse(Files.exists(raiz.resolve("bloqueos/202604.bloqueadas")));
        sinTemporales();
    }

    @Test
    @DisplayName("El mes se deduce de los nombres de bloqueos que reconoce la carga")
    void mesDeArchivoBloqueos() {
        assertEquals(YearMonth.of(2026, 1), RepositorioDatos.mesDeArchivoBloqueos("202601.bloqueadas"));
        assertEquals(YearMonth.of(2026, 1), RepositorioDatos.mesDeArchivoBloqueos("202601bloqueadas"));
        assertEquals(YearMonth.of(2026, 12), RepositorioDatos.mesDeArchivoBloqueos("202612.BLOQUEADAS.txt"));
        assertEquals(YearMonth.of(2026, 3), RepositorioDatos.mesDeArchivoBloqueos("bloqueo.2603.txt"));
        assertThrows(IllegalArgumentException.class, () -> RepositorioDatos.mesDeArchivoBloqueos("202613.bloqueadas"));
        assertThrows(IllegalArgumentException.class, () -> RepositorioDatos.mesDeArchivoBloqueos("ventas202601"));
        assertThrows(IllegalArgumentException.class, () -> RepositorioDatos.mesDeArchivoBloqueos(null));
    }

    @Test
    @DisplayName("El plan de mantenimiento va al directorio del ano de su primera fecha")
    void guardarMantenimiento() throws Exception {
        String plan = "20260901:TA05\n20261002:TM01\n";
        RepositorioDatos.ArchivoGuardado guardado =
                repositorio.guardarMantenimiento("mant.preventivo.09.10", archivo("plan.txt", plan));
        assertEquals(new RepositorioDatos.ArchivoGuardado("mantenimiento/2026/mant.preventivo.09.10", 2, false), guardado);
        assertEquals(plan, Files.readString(raiz.resolve("mantenimiento/2026/mant.preventivo.09.10")));
        assertTrue(repositorio.guardarMantenimiento("mant.preventivo.09.10", archivo("plan2.txt", plan)).reemplazado());

        RepositorioDatos.DatosEscenario datos = repositorio.cargar(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
        assertEquals(1, datos.mantenimientos().size());

        Path valido = archivo("valido.txt", plan);
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarMantenimiento("plan.txt", valido));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarMantenimiento("mant.preventivo.", valido));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.guardarMantenimiento("../mant.preventivo.01.02", valido));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.guardarMantenimiento("mant.preventivo.01.02", archivo("vacio.txt", "# nada\n")));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.guardarMantenimiento("mant.preventivo.01.02", archivo("malo.txt", "2026-01-01:TA01\n")));
        assertFalse(Files.exists(raiz.resolve("mantenimiento/2026/mant.preventivo.01.02")));
        sinTemporales();
    }
}
