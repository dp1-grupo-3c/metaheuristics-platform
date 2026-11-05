package org.kindbox.core.configuracion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.kindbox.core.configuracion.ParametrosMundo.AlmacenParam;
import org.kindbox.core.configuracion.ParametrosMundo.Mapa;
import org.kindbox.core.configuracion.ParametrosMundo.Plazos;
import org.kindbox.core.configuracion.ParametrosMundo.ReglaReincorporacion;
import org.kindbox.core.configuracion.ParametrosMundo.Semaforo;
import org.kindbox.core.configuracion.ParametrosMundo.TipoAveriaParam;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.Turno;

class ParametrosMundoTest {

    private final ParametrosMundo enunciado = ParametrosMundo.delEnunciado();

    @Test
    void losValoresDelEnunciadoCoincidenConLasConstantesDelNucleo() {
        assertEquals(new Mapa(Ciudad.LARGO_KM, Ciudad.ANCHO_KM), enunciado.mapa());
        assertEquals(Almacen.todos().size(), enunciado.almacenes().size());
        for (Almacen a : Almacen.todos()) {
            AlmacenParam p = enunciado.almacenes().stream().filter(x -> x.id() == a.id()).findFirst().orElseThrow();
            assertEquals(a.nodo(), Ciudad.nodo(p.x(), p.y()), a.nombre());
            assertEquals(a.central(), p.central());
            if (!a.central()) {
                assertEquals(a.capacidad(), p.capacidad());
            }
        }
        for (TipoAveria t : TipoAveria.values()) {
            TipoAveriaParam p = enunciado.averias().get(t.codigo() - 1);
            assertEquals(t.minutosEnElLugar(), p.minutosEnElLugar());
            assertEquals(t.trasladaAlmacenCentral(), p.trasladaAlmacenCentral());
        }
        assertEquals(37, enunciado.flota().stream().mapToInt(ParametrosMundo.TipoUnidadParam::cantidad).sum(),
                "debe coincidir con data_dp1/flota.txt");
    }

    @Test
    void rechazaUnAlmacenFueraDelMapaAlAchicarlo() {
        ParametrosMundoInvalidos error = assertThrows(ParametrosMundoInvalidos.class, () ->
                new ParametrosMundo(new Mapa(40, 30), enunciado.almacenes(), enunciado.flota(),
                        enunciado.averias(), enunciado.semaforo(), enunciado.turnos(), enunciado.plazos(),
                        enunciado.tiempos()));
        // Nor-Oeste (12,38) y Este (57,27) quedan fuera; el central (27,14) queda dentro.
        assertEquals(2, error.errores().stream().filter(e -> e.contains("fuera del mapa")).count(),
                error.errores().toString());
    }

    @Test
    void reuneTodosLosErroresEnUnaSolaPasada() {
        List<AlmacenParam> almacenes = new ArrayList<>(enunciado.almacenes());
        almacenes.set(1, new AlmacenParam(1, "Sin capacidad", 12, 38, false, null));
        List<TipoAveriaParam> averias = List.of(
                new TipoAveriaParam(1, 70, 120, false, ReglaReincorporacion.TRAS_MINUTOS, null, null, null),
                new TipoAveriaParam(2, 25, 240, true, ReglaReincorporacion.FIN_TURNO_SIGUIENTE, null, null, null),
                new TipoAveriaParam(3, 10, 240, true, ReglaReincorporacion.DIAS_Y_TURNO, null, 2, Turno.TARDE));
        ParametrosMundoInvalidos error = assertThrows(ParametrosMundoInvalidos.class, () ->
                new ParametrosMundo(enunciado.mapa(), almacenes, enunciado.flota(), averias,
                        new Semaforo(25, 50), enunciado.turnos(), new Plazos(36, List.of(4, 40)),
                        enunciado.tiempos()));
        List<String> e = error.errores();
        assertTrue(e.stream().anyMatch(s -> s.startsWith("almacenes[1].capacidad")), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.startsWith("averias[0].minutosIndisponible")), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.contains("suman 105")), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.startsWith("semaforo")), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.startsWith("plazos.priorizadosHoras")), e.toString());
    }

    @Test
    void exigeExactamenteUnAlmacenCentral() {
        List<AlmacenParam> almacenes = new ArrayList<>(enunciado.almacenes());
        almacenes.set(1, new AlmacenParam(1, "Otro central", 12, 38, true, null));
        ParametrosMundoInvalidos error = assertThrows(ParametrosMundoInvalidos.class, () ->
                new ParametrosMundo(enunciado.mapa(), almacenes, enunciado.flota(), enunciado.averias(),
                        enunciado.semaforo(), enunciado.turnos(), enunciado.plazos(), enunciado.tiempos()));
        assertTrue(error.errores().stream().anyMatch(s -> s.contains("exactamente un almacen central")));
    }
}
