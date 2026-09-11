package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Conteo de unidades activas por tipo que pinta la barra superior del prototipo.
 *
 * <p>Activa quiere decir en operacion. El conteo excluia la unidad disponible en un almacen y
 * la averiada, pero contaba como activa la que esta en mantenimiento preventivo, que el
 * enunciado retira de la flota durante todo el dia programado y que por tanto no esta
 * trabajando. Esta prueba fija las tres exclusiones y las cuatro situaciones que si cuentan.</p>
 */
class InstantaneaSimulacionTest {

    private static VistaUnidad unidad(String codigo, TipoUnidad tipo, EstadoUnidad estado, int tipoAveria) {
        return new VistaUnidad(codigo, tipo, estado, 0, 0, 0, tipo.capacidad(), new int[0], new int[0],
                -1, -1, 0L, List.of(), tipoAveria);
    }

    private static InstantaneaSimulacion con(List<VistaUnidad> unidades) {
        MetricasSimulacion metricas = new MetricasSimulacion(0, 0, 0, 0, 0, 0, 0.0,
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(), 0L, 0L);
        return new InstantaneaSimulacion(0L, LocalDateTime.of(2026, 9, 1, 0, 0), EstadoCorrida.EN_CURSO,
                0L, unidades, List.of(), List.of(), 0, metricas);
    }

    @Test
    @DisplayName("La unidad en mantenimiento no cuenta como activa")
    void elMantenimientoNoCuenta() {
        InstantaneaSimulacion foto = con(List.of(
                unidad("TA01", TipoUnidad.AUTO, EstadoUnidad.EN_RUTA, 0),
                unidad("TA02", TipoUnidad.AUTO, EstadoUnidad.EN_MANTENIMIENTO, 0),
                unidad("TA03", TipoUnidad.AUTO, EstadoUnidad.DISPONIBLE, 0),
                unidad("TA04", TipoUnidad.AUTO, EstadoUnidad.AVERIADA, 2)));

        assertEquals(1, foto.unidadesActivasDeTipo(TipoUnidad.AUTO),
                "solo la unidad en ruta esta trabajando");
        assertEquals(0, foto.unidadesActivasDeTipo(TipoUnidad.MOTO), "no hay motos en la fotografia");
    }

    @Test
    @DisplayName("Cuentan las cuatro situaciones de operacion y solo ellas")
    void soloCuentanLasSituacionesDeOperacion() {
        List<VistaUnidad> unidades = new ArrayList<>();
        for (EstadoUnidad estado : EstadoUnidad.values()) {
            unidades.add(unidad("TM" + estado.ordinal(), TipoUnidad.MOTO, estado, 0));
        }
        InstantaneaSimulacion foto = con(unidades);

        // De los siete estados quedan fuera DISPONIBLE, AVERIADA y EN_MANTENIMIENTO.
        assertEquals(EstadoUnidad.values().length - 3, foto.unidadesActivasDeTipo(TipoUnidad.MOTO),
                "activas esperadas: en ruta, entregando, en alimentacion y abasteciendo");
    }

    @Test
    @DisplayName("Una unidad con averia declarada no cuenta aunque su estado diga otra cosa")
    void laAveriaMandaSobreElEstado() {
        InstantaneaSimulacion foto = con(List.of(
                unidad("TB01", TipoUnidad.BICICLETA, EstadoUnidad.EN_RUTA, 3),
                unidad("TB02", TipoUnidad.BICICLETA, EstadoUnidad.EN_RUTA, 0)));

        assertEquals(1, foto.unidadesActivasDeTipo(TipoUnidad.BICICLETA),
                "la unidad con codigo de averia esta inmovilizada");
    }

    @Test
    @DisplayName("El conteo separa los tipos y no mezcla la flota")
    void elConteoSeparaLosTipos() {
        InstantaneaSimulacion foto = con(List.of(
                unidad("TA01", TipoUnidad.AUTO, EstadoUnidad.EN_RUTA, 0),
                unidad("TM01", TipoUnidad.MOTO, EstadoUnidad.ENTREGANDO, 0),
                unidad("TM02", TipoUnidad.MOTO, EstadoUnidad.EN_MANTENIMIENTO, 0),
                unidad("TB01", TipoUnidad.BICICLETA, EstadoUnidad.ABASTECIENDO, 0),
                unidad("TB02", TipoUnidad.BICICLETA, EstadoUnidad.EN_ALIMENTACION, 0)));

        assertEquals(1, foto.unidadesActivasDeTipo(TipoUnidad.AUTO), "autos activos");
        assertEquals(1, foto.unidadesActivasDeTipo(TipoUnidad.MOTO), "motos activas");
        assertEquals(2, foto.unidadesActivasDeTipo(TipoUnidad.BICICLETA), "bicicletas activas");
    }
}
