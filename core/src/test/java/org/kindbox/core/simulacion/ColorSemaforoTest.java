package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;

/**
 * Semaforo del requisito no funcional (d) del enunciado, cuyos rangos deben ser parametros
 * configurables.
 *
 * <p>Fija los bordes de los dos usos. El de inventario compara unidades del producto P contra
 * los umbrales tal cual. El de holgura los lee como proporciones de la capacidad de un almacen
 * intermedio: con los valores por defecto, 500 y 250 sobre 1000, el corte verde cae en la mitad
 * del plazo comprometido y el ambar en la cuarta parte.</p>
 *
 * <p>El caso que motivo esta prueba: el corte verde se calculaba con un {@code Math.max(1.0, ..)}
 * que lo dejaba siempre en 1.0, de modo que un pedido solo salia verde en el minuto exacto de su
 * registro, cuando la holgura todavia valia el plazo entero, y pasaba a ambar un minuto despues.
 * Aqui se comprueba que un pedido con nueve decimos de su plazo por delante es verde.</p>
 */
class ColorSemaforoTest {

    /** Plazo estandar del enunciado, 36 horas en minutos. */
    private static final long PLAZO = 36L * 60L;

    private static ParametrosOperacion.Instantanea conUmbrales(int ambar, int verde) {
        ParametrosOperacion parametros = new ParametrosOperacion();
        parametros.umbralesSemaforo(ambar, verde);
        return parametros.instantanea();
    }

    private static ParametrosOperacion.Instantanea porDefecto() {
        return new ParametrosOperacion().instantanea();
    }

    @Test
    @DisplayName("Con los umbrales por defecto el corte verde es la mitad del plazo y el ambar la cuarta parte")
    void cortesPorDefecto() {
        ParametrosOperacion.Instantanea p = porDefecto();
        assertEquals(500, p.umbralSemaforoVerde(), "umbral verde por defecto");
        assertEquals(250, p.umbralSemaforoAmbar(), "umbral ambar por defecto");
        assertEquals(1000, Almacen.CAPACIDAD_INTERMEDIO, "capacidad de referencia del semaforo");

        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deHolgura(PLAZO, PLAZO, p),
                "un pedido recien registrado, con el plazo entero por delante, es verde");
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deHolgura(PLAZO * 9 / 10, PLAZO, p),
                "con nueve decimos del plazo por delante el pedido sigue siendo verde");
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deHolgura(PLAZO / 2, PLAZO, p),
                "la mitad exacta del plazo es el borde inferior del verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deHolgura(PLAZO / 2 - 1, PLAZO, p),
                "un minuto por debajo de la mitad ya es ambar");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deHolgura(PLAZO / 4, PLAZO, p),
                "la cuarta parte del plazo es el borde inferior del ambar");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(PLAZO / 4 - 1, PLAZO, p),
                "un minuto por debajo de la cuarta parte ya es rojo");
    }

    @Test
    @DisplayName("Sin holgura o sin plazo el color es rojo")
    void sinHolguraEsRojo() {
        ParametrosOperacion.Instantanea p = porDefecto();
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(0L, PLAZO, p), "holgura agotada");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(-120L, PLAZO, p), "plazo ya vencido");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(60L, 0L, p), "plazo nulo");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(60L, -1L, p), "plazo negativo");
    }

    @Test
    @DisplayName("Los cortes de la holgura siguen a los umbrales configurados")
    void cortesConfigurables() {
        // 800 y 100 sobre 1000: verde a partir de ocho decimos del plazo, ambar a partir de uno.
        ParametrosOperacion.Instantanea p = conUmbrales(100, 800);
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deHolgura(PLAZO * 8 / 10, PLAZO, p), "borde verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deHolgura(PLAZO * 8 / 10 - 1, PLAZO, p),
                "justo por debajo del borde verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deHolgura(PLAZO / 10, PLAZO, p), "borde ambar");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(PLAZO / 10 - 1, PLAZO, p),
                "justo por debajo del borde ambar");
    }

    @Test
    @DisplayName("Unos umbrales por encima de la capacidad no invierten el orden de los colores")
    void umbralesPorEncimaDeLaCapacidad() {
        // El corte verde se acota al plazo entero y el ambar nunca lo supera.
        ParametrosOperacion.Instantanea p = conUmbrales(900, 2000);
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deHolgura(PLAZO, PLAZO, p),
                "solo el plazo entero llega a verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deHolgura(PLAZO * 95 / 100, PLAZO, p),
                "por debajo del plazo entero y por encima de nueve decimos, ambar");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deHolgura(PLAZO * 85 / 100, PLAZO, p),
                "por debajo de nueve decimos, rojo");
    }

    @Test
    @DisplayName("El color del inventario compara las unidades disponibles contra los umbrales")
    void bordesDelInventario() {
        ParametrosOperacion.Instantanea p = porDefecto();
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deInventario(1000, p), "almacen lleno");
        assertEquals(ColorSemaforo.VERDE, ColorSemaforo.deInventario(500, p), "borde inferior del verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deInventario(499, p), "un paquete por debajo del verde");
        assertEquals(ColorSemaforo.AMBAR, ColorSemaforo.deInventario(250, p), "borde inferior del ambar");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deInventario(249, p), "un paquete por debajo del ambar");
        assertEquals(ColorSemaforo.ROJO, ColorSemaforo.deInventario(0, p), "almacen vacio");
    }
}
