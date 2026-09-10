package org.kindbox.core.modelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reticula de la ciudad del apartado 1 del contexto de dominio: 70 Km sobre el eje X por
 * 50 Km sobre el eje Y, nodos cada kilometro y origen en el extremo inferior izquierdo.
 *
 * <p>El empaquetado {@code id = y * ANCHO_NODOS + x} es la base de todos los arreglos
 * primitivos del planificador (apartado 13 del ISA): si el empaquetado y el desempaquetado
 * no fuesen inversos exactos, todas las distancias del sistema apuntarian a esquinas
 * equivocadas sin que nada fallase de forma visible.</p>
 */
class CiudadTest {

    @Test
    @DisplayName("La reticula tiene 71 por 51 nodos, es decir 3 621 esquinas")
    void dimensionesDeLaReticula() {
        assertEquals(70, Ciudad.LARGO_KM);
        assertEquals(50, Ciudad.ANCHO_KM);
        assertEquals(71, Ciudad.ANCHO_NODOS);
        assertEquals(51, Ciudad.ALTO_NODOS);
        assertEquals(3621, Ciudad.TOTAL_NODOS);
    }

    @Test
    @DisplayName("El empaquetado y el desempaquetado son inversos exactos en toda la reticula")
    void empaquetadoYDesempaquetado() {
        boolean[] visto = new boolean[Ciudad.TOTAL_NODOS];
        for (int y = 0; y < Ciudad.ALTO_NODOS; y++) {
            for (int x = 0; x < Ciudad.ANCHO_NODOS; x++) {
                int nodo = Ciudad.nodo(x, y);
                assertTrue(nodo >= 0 && nodo < Ciudad.TOTAL_NODOS,
                        "el nodo de (" + x + "," + y + ") cae fuera del rango: " + nodo);
                assertFalse(visto[nodo], "dos coordenadas distintas comparten el nodo " + nodo);
                visto[nodo] = true;
                assertEquals(x, Ciudad.x(nodo), "coordenada X de (" + x + "," + y + ")");
                assertEquals(y, Ciudad.y(nodo), "coordenada Y de (" + x + "," + y + ")");
            }
        }
        for (int nodo = 0; nodo < Ciudad.TOTAL_NODOS; nodo++) {
            assertTrue(visto[nodo], "ninguna coordenada produce el nodo " + nodo);
        }
    }

    @Test
    @DisplayName("Las cuatro esquinas de la ciudad ocupan los nodos extremos")
    void esquinasDeLaCiudad() {
        assertEquals(0, Ciudad.nodo(0, 0));
        assertEquals(70, Ciudad.nodo(70, 0));
        assertEquals(50 * 71, Ciudad.nodo(0, 50));
        assertEquals(Ciudad.TOTAL_NODOS - 1, Ciudad.nodo(70, 50));
    }

    @Test
    @DisplayName("Los limites de la reticula se respetan en ambos ejes")
    void limitesDeLaReticula() {
        assertTrue(Ciudad.dentro(0, 0));
        assertTrue(Ciudad.dentro(70, 50));
        assertFalse(Ciudad.dentro(71, 0), "x = 71 esta fuera: la ciudad llega hasta el kilometro 70");
        assertFalse(Ciudad.dentro(0, 51), "y = 51 esta fuera: la ciudad llega hasta el kilometro 50");
        assertFalse(Ciudad.dentro(-1, 0));
        assertFalse(Ciudad.dentro(0, -1));
        assertThrows(IllegalArgumentException.class, () -> Ciudad.nodo(71, 0));
        assertThrows(IllegalArgumentException.class, () -> Ciudad.nodo(0, 51));
        assertThrows(IllegalArgumentException.class, () -> Ciudad.nodo(-1, 10));
    }

    @Test
    @DisplayName("La distancia Manhattan mide los kilometros de la reticula sin diagonales")
    void distanciaManhattan() {
        int origen = Ciudad.nodo(0, 0);
        int opuesto = Ciudad.nodo(70, 50);
        assertEquals(0, Ciudad.distanciaManhattan(origen, origen));
        assertEquals(120, Ciudad.distanciaManhattan(origen, opuesto));
        assertEquals(120, Ciudad.distanciaManhattan(opuesto, origen), "la distancia debe ser simetrica");
        // Del almacen central (27,14) al intermedio este (57,27): 30 al este y 13 al norte.
        assertEquals(43, Ciudad.distanciaManhattan(Ciudad.nodo(27, 14), Ciudad.nodo(57, 27)));
        // Del almacen central al intermedio nor-oeste (12,38): 15 al oeste y 24 al norte.
        assertEquals(39, Ciudad.distanciaManhattan(Ciudad.nodo(27, 14), Ciudad.nodo(12, 38)));
        // Nodos contiguos: exactamente un kilometro, sin atajos diagonales.
        assertEquals(1, Ciudad.distanciaManhattan(Ciudad.nodo(10, 10), Ciudad.nodo(11, 10)));
        assertEquals(1, Ciudad.distanciaManhattan(Ciudad.nodo(10, 10), Ciudad.nodo(10, 11)));
        assertEquals(2, Ciudad.distanciaManhattan(Ciudad.nodo(10, 10), Ciudad.nodo(11, 11)));
    }

    @Test
    @DisplayName("La distancia Manhattan cumple la desigualdad triangular")
    void desigualdadTriangular() {
        int[] muestra = {Ciudad.nodo(0, 0), Ciudad.nodo(27, 14), Ciudad.nodo(12, 38),
                Ciudad.nodo(57, 27), Ciudad.nodo(70, 50), Ciudad.nodo(35, 25), Ciudad.nodo(3, 47)};
        for (int a : muestra) {
            for (int b : muestra) {
                for (int c : muestra) {
                    assertTrue(Ciudad.distanciaManhattan(a, c)
                                    <= Ciudad.distanciaManhattan(a, b) + Ciudad.distanciaManhattan(b, c),
                            "la desigualdad triangular falla entre " + Ciudad.texto(a) + ", "
                                    + Ciudad.texto(b) + " y " + Ciudad.texto(c));
                }
            }
        }
    }

    @Test
    @DisplayName("La representacion legible del nodo devuelve sus coordenadas")
    void representacionLegible() {
        assertEquals("(27,14)", Ciudad.texto(Ciudad.nodo(27, 14)));
        assertEquals("(0,0)", Ciudad.texto(Ciudad.nodo(0, 0)));
        assertEquals("(70,50)", Ciudad.texto(Ciudad.nodo(70, 50)));
    }
}
