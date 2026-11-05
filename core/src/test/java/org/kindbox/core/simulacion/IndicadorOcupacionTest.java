package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;

class IndicadorOcupacionTest {

    private static final ParametrosOperacion.Instantanea POR_DEFECTO = new ParametrosOperacion().instantanea();

    private static VistaUnidad unidad(TipoUnidad tipo, int carga) {
        return new VistaUnidad("T" + carga, tipo, EstadoUnidad.EN_RUTA, 0, 0, carga, tipo.capacidad(),
                ColorSemaforo.deOcupacion(carga, tipo.capacidad(), POR_DEFECTO), new int[0], new int[0],
                -1, -1, 0L, List.of(), 0);
    }

    private static VistaAlmacen intermedio(int id, int disponible) {
        return new VistaAlmacen(id, "A" + id, 0, 0, false, disponible, 1000, ColorSemaforo.deInventario(disponible, POR_DEFECTO));
    }

    private static final VistaAlmacen CENTRAL = new VistaAlmacen(0, "Central", 0, 0, true, -1, -1, ColorSemaforo.VERDE);

    @Test
    @DisplayName("La ocupacion de la flota suma carga y capacidad de todas las unidades")
    void ocupacionDeLaFlota() {
        // 24 + 8 + 4 = 36 de capacidad.
        IndicadorOcupacion vacia = IndicadorOcupacion.deFlota(List.of(unidad(TipoUnidad.AUTO, 0),
                unidad(TipoUnidad.MOTO, 0), unidad(TipoUnidad.BICICLETA, 0)), POR_DEFECTO);
        assertEquals(new IndicadorOcupacion(0, 36, 0.0, ColorSemaforo.VACIO), vacia);

        IndicadorOcupacion media = IndicadorOcupacion.deFlota(List.of(unidad(TipoUnidad.AUTO, 12),
                unidad(TipoUnidad.MOTO, 6), unidad(TipoUnidad.BICICLETA, 0)), POR_DEFECTO);
        assertEquals(new IndicadorOcupacion(18, 36, 50.0, ColorSemaforo.AMBAR), media);

        IndicadorOcupacion llena = IndicadorOcupacion.deFlota(List.of(unidad(TipoUnidad.AUTO, 24),
                unidad(TipoUnidad.MOTO, 8), unidad(TipoUnidad.BICICLETA, 1)), POR_DEFECTO);
        assertEquals(91.7, llena.porcentaje(), "33 de 36 con un decimal");
        assertEquals(ColorSemaforo.ROJO, llena.color());

        assertEquals(new IndicadorOcupacion(0, 0, 0.0, ColorSemaforo.VACIO),
                IndicadorOcupacion.deFlota(List.of(), POR_DEFECTO), "sin flota");
    }

    @Test
    @DisplayName("La ocupacion de los almacenes ignora el central y escala los umbrales por almacen")
    void ocupacionDeLosAlmacenes() {
        // Dos intermedios: verde desde 1000 en total y ambar desde 500.
        assertEquals(new IndicadorOcupacion(2000, 2000, 100.0, ColorSemaforo.VERDE),
                IndicadorOcupacion.deAlmacenes(List.of(CENTRAL, intermedio(1, 1000), intermedio(2, 1000)), POR_DEFECTO));
        assertEquals(new IndicadorOcupacion(1000, 2000, 50.0, ColorSemaforo.VERDE),
                IndicadorOcupacion.deAlmacenes(List.of(CENTRAL, intermedio(1, 1000), intermedio(2, 0)), POR_DEFECTO));
        assertEquals(new IndicadorOcupacion(999, 2000, 50.0, ColorSemaforo.AMBAR),
                IndicadorOcupacion.deAlmacenes(List.of(CENTRAL, intermedio(1, 999), intermedio(2, 0)), POR_DEFECTO));
        assertEquals(ColorSemaforo.ROJO,
                IndicadorOcupacion.deAlmacenes(List.of(intermedio(1, 250), intermedio(2, 249)), POR_DEFECTO).color());
        assertEquals(new IndicadorOcupacion(0, 2000, 0.0, ColorSemaforo.VACIO),
                IndicadorOcupacion.deAlmacenes(List.of(CENTRAL, intermedio(1, 0), intermedio(2, 0)), POR_DEFECTO));
        assertEquals(new IndicadorOcupacion(0, 0, 0.0, ColorSemaforo.VACIO),
                IndicadorOcupacion.deAlmacenes(List.of(CENTRAL), POR_DEFECTO), "solo el central");
    }

    @Test
    @DisplayName("El color del conjunto sigue los umbrales vigentes")
    void elColorSigueLosUmbrales() {
        ParametrosOperacion parametros = new ParametrosOperacion();
        parametros.umbralesSemaforo(100, 200);
        parametros.umbralesOcupacion(10, 20);
        ParametrosOperacion.Instantanea p = parametros.instantanea();
        assertEquals(ColorSemaforo.VERDE,
                IndicadorOcupacion.deAlmacenes(List.of(intermedio(1, 200), intermedio(2, 200)), p).color());
        assertEquals(ColorSemaforo.ROJO,
                IndicadorOcupacion.deFlota(List.of(unidad(TipoUnidad.AUTO, 6)), p).color(), "6 de 24 es 25 %");
    }
}
