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
import java.time.LocalDateTime;
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
    void agregarBloqueoCreaElArchivoDelMesYLoReutiliza() throws Exception {
        List<RepositorioDatos.ArchivoGuardado> primero = repositorio.agregarBloqueo(new int[] {12, 20, 13, 20},
                LocalDateTime.of(2026, 9, 2, 10, 30), LocalDateTime.of(2026, 9, 2, 18, 0));
        assertEquals(1, primero.size());
        assertEquals("bloqueos/202609.bloqueadas", primero.get(0).ruta());
        assertEquals(1, primero.get(0).registros());
        assertFalse(primero.get(0).reemplazado());

        // Un archivo sin salto de linea final no debe pegar el registro nuevo al anterior.
        Path archivo = raiz.resolve("bloqueos/202609.bloqueadas");
        Files.writeString(archivo, Files.readString(archivo).stripTrailing());
        List<RepositorioDatos.ArchivoGuardado> segundo = repositorio.agregarBloqueo(new int[] {1, 1, 1, 5, 4, 5},
                LocalDateTime.of(2026, 9, 3, 0, 0), LocalDateTime.of(2026, 9, 4, 0, 0));
        assertEquals(2, segundo.get(0).registros());
        assertTrue(segundo.get(0).reemplazado());
        assertEquals(List.of("02d10h30m-02d18h00m:12,20,13,20", "03d00h00m-04d00h00m:1,1,1,5,4,5"),
                Files.readAllLines(archivo));
    }

    @Test
    void agregarBloqueoParteElRegistroAlCruzarDeMes() throws Exception {
        List<RepositorioDatos.ArchivoGuardado> guardados = repositorio.agregarBloqueo(new int[] {12, 20, 13, 20},
                LocalDateTime.of(2026, 9, 30, 22, 0), LocalDateTime.of(2026, 10, 1, 2, 0));
        assertEquals(2, guardados.size());
        assertEquals(List.of("30d22h00m-30d23h59m:12,20,13,20"),
                Files.readAllLines(raiz.resolve("bloqueos/202609.bloqueadas")));
        assertEquals(List.of("01d00h00m-01d02h00m:12,20,13,20"),
                Files.readAllLines(raiz.resolve("bloqueos/202610.bloqueadas")));

        // Terminar justo a medianoche del dia 1 no deja un registro vacio en el mes siguiente.
        assertEquals(1, repositorio.agregarBloqueo(new int[] {1, 1, 2, 1},
                LocalDateTime.of(2026, 11, 30, 22, 0), LocalDateTime.of(2026, 12, 1, 0, 0)).size());
        assertFalse(Files.exists(raiz.resolve("bloqueos/202612.bloqueadas")));
    }

    @Test
    @DisplayName("Los registros de bloqueo se listan con su linea, su vigencia y su poligonal")
    void listarRegistrosDeBloqueo() throws Exception {
        assertTrue(repositorio.registrosBloqueo(YearMonth.of(2026, 9)).isEmpty());
        enRaiz("bloqueos/202609.bloqueadas", "# comentario\n02d10h30m-02d18h00m:12,20,13,20\n\n03d00h00m-04d00h00m:1,1,1,5,4,5\n");

        List<RepositorioDatos.RegistroBloqueo> registros = repositorio.registrosBloqueo(YearMonth.of(2026, 9));

        assertEquals(2, registros.size());
        RepositorioDatos.RegistroBloqueo primero = registros.get(0);
        assertEquals(2, primero.linea());
        assertEquals("bloqueos/202609.bloqueadas", primero.archivo());
        assertEquals("02d10h30m-02d18h00m:12,20,13,20", primero.registro());
        assertEquals(LocalDateTime.of(2026, 9, 2, 10, 30), primero.inicio());
        assertEquals(LocalDateTime.of(2026, 9, 2, 18, 0), primero.fin());
        assertEquals(List.of(12, 20, 13, 20), primero.coordenadas());
        assertEquals(4, registros.get(1).linea());
        assertEquals(List.of(1, 1, 1, 5, 4, 5), registros.get(1).coordenadas());
    }

    @Test
    @DisplayName("Quitar un bloqueo borra solo su linea y exige que no haya cambiado")
    void quitarBloqueo() throws Exception {
        Path archivo = enRaiz("bloqueos/202609.bloqueadas",
                "# comentario\n02d10h30m-02d18h00m:12,20,13,20\n03d00h00m-04d00h00m:1,1,1,5,4,5\n");
        YearMonth septiembre = YearMonth.of(2026, 9);

        assertThrows(IllegalStateException.class, () -> repositorio.quitarBloqueo(septiembre, 2, "otro registro"));
        assertThrows(java.util.NoSuchElementException.class, () -> repositorio.quitarBloqueo(septiembre, 1, null));
        assertThrows(java.util.NoSuchElementException.class, () -> repositorio.quitarBloqueo(septiembre, 9, null));
        assertThrows(java.util.NoSuchElementException.class,
                () -> repositorio.quitarBloqueo(YearMonth.of(2026, 10), 1, null));
        assertEquals(3, Files.readAllLines(archivo).size());

        RepositorioDatos.ArchivoGuardado guardado =
                repositorio.quitarBloqueo(septiembre, 2, "02d10h30m-02d18h00m:12,20,13,20");

        assertEquals(1, guardado.registros());
        assertEquals(List.of("# comentario", "03d00h00m-04d00h00m:1,1,1,5,4,5"), Files.readAllLines(archivo));
        repositorio.quitarBloqueo(septiembre, 2, null);
        assertEquals(List.of("# comentario"), Files.readAllLines(archivo));
        sinTemporales();
    }

    @Test
    @DisplayName("Reemplazar un bloqueo cambia su linea, aunque el nuevo caiga en otro mes")
    void reemplazarBloqueo() throws Exception {
        Path septiembre = enRaiz("bloqueos/202609.bloqueadas",
                "02d10h30m-02d18h00m:12,20,13,20\n03d00h00m-04d00h00m:1,1,1,5,4,5\n");
        YearMonth mes = YearMonth.of(2026, 9);

        List<RepositorioDatos.ArchivoGuardado> mismoMes = repositorio.reemplazarBloqueo(mes, 1,
                "02d10h30m-02d18h00m:12,20,13,20", new int[] {5, 5, 5, 8}, LocalDateTime.of(2026, 9, 5, 7, 0),
                LocalDateTime.of(2026, 9, 5, 9, 15));
        assertEquals(1, mismoMes.size());
        assertEquals(List.of("03d00h00m-04d00h00m:1,1,1,5,4,5", "05d07h00m-05d09h15m:5,5,5,8"),
                Files.readAllLines(septiembre));

        List<RepositorioDatos.ArchivoGuardado> otroMes = repositorio.reemplazarBloqueo(mes, 1, null,
                new int[] {1, 1, 2, 1}, LocalDateTime.of(2026, 10, 3, 0, 0), LocalDateTime.of(2026, 10, 3, 6, 0));
        assertEquals(2, otroMes.size());
        assertEquals(List.of("05d07h00m-05d09h15m:5,5,5,8"), Files.readAllLines(septiembre));
        assertEquals(List.of("03d00h00m-03d06h00m:1,1,2,1"),
                Files.readAllLines(raiz.resolve("bloqueos/202610.bloqueadas")));

        // Un bloqueo nuevo invalido no toca el archivo.
        assertThrows(IllegalArgumentException.class, () -> repositorio.reemplazarBloqueo(mes, 1, null,
                new int[] {1, 1, 2, 2}, LocalDateTime.of(2026, 9, 5, 7, 0), LocalDateTime.of(2026, 9, 5, 8, 0)));
        assertEquals(List.of("05d07h00m-05d09h15m:5,5,5,8"), Files.readAllLines(septiembre));
        sinTemporales();
    }

    @Test
    void agregarBloqueoInvalidoNoEscribeNada() {
        LocalDateTime inicio = LocalDateTime.of(2026, 9, 30, 22, 0);
        LocalDateTime fin = LocalDateTime.of(2026, 10, 1, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> repositorio.agregarBloqueo(new int[] {1, 1, 2, 2}, inicio, fin));
        assertThrows(IllegalArgumentException.class, () -> repositorio.agregarBloqueo(new int[] {1, 1, 500, 1}, inicio, fin));
        assertThrows(IllegalArgumentException.class, () -> repositorio.agregarBloqueo(new int[] {1, 1, 2, 1}, fin, inicio));
        assertFalse(Files.exists(raiz.resolve("bloqueos")));
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
