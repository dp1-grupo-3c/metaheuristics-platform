package org.kindbox.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

class FlotaAjustadaTest {

    @TempDir
    Path raiz;

    private static Map<TipoUnidad, Integer> cantidades(int autos, int motos, int bicicletas) {
        Map<TipoUnidad, Integer> mapa = new EnumMap<>(TipoUnidad.class);
        mapa.put(TipoUnidad.AUTO, autos);
        mapa.put(TipoUnidad.MOTO, motos);
        mapa.put(TipoUnidad.BICICLETA, bicicletas);
        return mapa;
    }

    private static List<String> codigos(LectorFlota.FlotaLeida flota, TipoUnidad tipo) {
        return flota.unidades().stream().filter(u -> u.tipo() == tipo).map(UnidadTransporte::codigo).toList();
    }

    @Test
    @DisplayName("Reducir conserva las primeras unidades y los tipos ausentes no cambian")
    void reducirConservaLasPrimeras() {
        LectorFlota lector = new LectorFlota();
        LectorFlota.FlotaLeida ajustada = lector.conCantidades(lector.flotaPorDefecto(),
                Map.of(TipoUnidad.AUTO, 3, TipoUnidad.BICICLETA, 0));
        assertEquals(List.of("TA01", "TA02", "TA03"), codigos(ajustada, TipoUnidad.AUTO));
        assertEquals(15, ajustada.cantidadDeTipo(TipoUnidad.MOTO));
        assertEquals(0, ajustada.cantidadDeTipo(TipoUnidad.BICICLETA));
        assertEquals(18, ajustada.unidades().size());
    }

    @Test
    @DisplayName("Ampliar completa con los codigos TTNN libres y conserva los valores declarados")
    void ampliarUsaCodigosLibres() throws Exception {
        Path archivo = raiz.resolve("flota.txt");
        Files.writeString(archivo, "TA02\nTA05\nAUTO,2,30,45.0\nMOTO,1\n");
        LectorFlota lector = new LectorFlota();
        LectorFlota.FlotaLeida base = lector.leer(archivo);
        assertEquals(List.of("TA02", "TA05", "TA01", "TA03"), codigos(base, TipoUnidad.AUTO));

        LectorFlota.FlotaLeida ampliada = lector.conCantidades(base, Map.of(TipoUnidad.AUTO, 6));
        assertEquals(List.of("TA02", "TA05", "TA01", "TA03", "TA04", "TA06"), codigos(ampliada, TipoUnidad.AUTO));
        assertEquals(30, ampliada.capacidad(TipoUnidad.AUTO));
        assertEquals(45.0, ampliada.velocidad(TipoUnidad.AUTO));

        LectorFlota.FlotaLeida reducida = lector.conCantidades(base, Map.of(TipoUnidad.AUTO, 2));
        assertEquals(List.of("TA02", "TA05"), codigos(reducida, TipoUnidad.AUTO));
        assertEquals(List.of("TM01"), codigos(reducida, TipoUnidad.MOTO));
    }

    @Test
    @DisplayName("Rechaza cantidades fuera de 0..99, valores nulos y una flota vacia")
    void validaciones() {
        LectorFlota lector = new LectorFlota();
        LectorFlota.FlotaLeida base = lector.flotaPorDefecto();
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(base, Map.of(TipoUnidad.AUTO, 100)));
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(base, Map.of(TipoUnidad.MOTO, -1)));
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(base, cantidades(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(base, null));
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(null, Map.of()));
        Map<TipoUnidad, Integer> conNulo = new HashMap<>();
        conNulo.put(TipoUnidad.AUTO, null);
        assertThrows(IllegalArgumentException.class, () -> lector.conCantidades(base, conNulo));
        assertEquals(99, lector.conCantidades(base, cantidades(99, 0, 0)).unidades().size());
    }

    @Test
    @DisplayName("El repositorio aplica el ajuste al cargar, avisa y lo descarta con null")
    void ajusteEnElRepositorio() throws Exception {
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        assertEquals(cantidades(10, 15, 12), repositorio.cantidadesFlotaVigentes());

        repositorio.cantidadesFlota(cantidades(4, 0, 20));
        assertEquals(cantidades(4, 0, 20), repositorio.cantidadesFlotaVigentes());
        RepositorioDatos.DatosEscenario datos = repositorio.cargar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(24, datos.unidades().size());
        assertTrue(datos.avisos().stream().anyMatch(a -> a.startsWith("Flota ajustada")
                && a.contains("4 autos") && a.contains("0 motos") && a.contains("20 bicicletas")), datos.avisos().toString());

        repositorio.cantidadesFlota(null);
        assertEquals(cantidades(10, 15, 12), repositorio.cantidadesFlotaVigentes());
        RepositorioDatos.DatosEscenario sinAjuste = repositorio.cargar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(37, sinAjuste.unidades().size());
        assertTrue(sinAjuste.avisos().stream().noneMatch(a -> a.startsWith("Flota ajustada")));
    }

    @Test
    @DisplayName("Un ajuste invalido se rechaza y deja el anterior")
    void ajusteInvalidoNoCambiaNada() throws Exception {
        RepositorioDatos repositorio = new RepositorioDatos(raiz);
        Map<TipoUnidad, Integer> validas = cantidades(2, 2, 2);
        repositorio.cantidadesFlota(validas);
        assertThrows(IllegalArgumentException.class, () -> repositorio.cantidadesFlota(cantidades(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> repositorio.cantidadesFlota(Map.of(TipoUnidad.AUTO, 100)));
        assertEquals(validas, repositorio.cantidadesFlotaVigentes());
        assertEquals(6, repositorio.cargar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)).unidades().size());
    }
}
