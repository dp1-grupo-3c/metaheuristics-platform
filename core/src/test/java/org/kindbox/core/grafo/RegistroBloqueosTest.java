package org.kindbox.core.grafo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;

/**
 * Registro de tramos bloqueados, restriccion dura 6 del apartado 2.6 del ISA.
 *
 * <p>Se comprueban las tres propiedades de las que depende el resto del sistema: que
 * bloquear una calle la bloquea en los <b>dos sentidos</b>, porque el requisito no funcional
 * (c) del enunciado dice que todas las calles son de doble sentido y la respuesta 7 exige la
 * vuelta en U; que {@code proximoCambio} devuelve el instante exacto del siguiente cambio,
 * porque de el cuelga la cola de eventos del simulador; y que un registro vacio se comporta
 * como un escenario sin bloqueos en lugar de fallar.</p>
 */
class RegistroBloqueosTest {

    private static int nodo(int x, int y) {
        return Ciudad.nodo(x, y);
    }

    /** Bloqueo de un solo tramo entre dos esquinas contiguas. */
    private static Bloqueo tramo(int x1, int y1, int x2, int y2, long desde, long hasta) {
        return new Bloqueo(new int[] {nodo(x1, y1), nodo(x2, y2)}, desde, hasta);
    }

    @Test
    @DisplayName("Bloquear una calle la deja intransitable en los dos sentidos")
    void bloqueoEnAmbosSentidos() {
        RegistroBloqueos registro = new RegistroBloqueos(List.of(tramo(10, 10, 11, 10, 100, 200)));
        int a = nodo(10, 10);
        int b = nodo(11, 10);

        assertTrue(registro.bloqueada(a, b, 150), "el sentido de ida debe estar bloqueado");
        assertTrue(registro.bloqueada(b, a, 150), "el sentido de vuelta debe estar bloqueado");

        boolean[] mascara = registro.mascaraBloqueada(150);
        assertTrue(mascara[RegistroBloqueos.aristaEntre(a, b)]);
        assertTrue(mascara[RegistroBloqueos.aristaEntre(b, a)]);
        assertTrue(mascara[RegistroBloqueos.aristaInversa(RegistroBloqueos.aristaEntre(a, b))]);
        // Las calles vecinas siguen abiertas: el bloqueo no aisla el nodo.
        assertFalse(registro.bloqueada(a, nodo(10, 11), 150));
        assertFalse(registro.bloqueada(b, nodo(12, 10), 150));
    }

    @Test
    @DisplayName("La ventana de vigencia es cerrada por la izquierda y abierta por la derecha")
    void ventanaDeVigencia() {
        RegistroBloqueos registro = new RegistroBloqueos(List.of(tramo(10, 10, 11, 10, 100, 200)));
        int a = nodo(10, 10);
        int b = nodo(11, 10);
        assertFalse(registro.bloqueada(a, b, 99), "antes de la activacion la calle esta abierta");
        assertTrue(registro.bloqueada(a, b, 100), "el instante de activacion ya bloquea");
        assertTrue(registro.bloqueada(a, b, 199));
        assertFalse(registro.bloqueada(a, b, 200), "el instante de desactivacion ya no bloquea");
        assertFalse(registro.bloqueada(a, b, 5000));
    }

    @Test
    @DisplayName("Una poligonal de varios tramos se descompone en aristas de un kilometro")
    void poligonalDeVariosTramos() {
        // (10,10) - (11,10) - (12,10) - (12,11): tres tramos encadenados.
        Bloqueo bloqueo = new Bloqueo(
                new int[] {nodo(10, 10), nodo(11, 10), nodo(12, 10), nodo(12, 11)}, 0, 1000);
        RegistroBloqueos registro = new RegistroBloqueos(List.of(bloqueo));
        assertEquals(3, bloqueo.cantidadTramos());

        for (int[] par : new int[][] {{10, 10, 11, 10}, {11, 10, 12, 10}, {12, 10, 12, 11}}) {
            int origen = nodo(par[0], par[1]);
            int destino = nodo(par[2], par[3]);
            assertTrue(registro.bloqueada(origen, destino, 500), "falta el tramo de ida");
            assertTrue(registro.bloqueada(destino, origen, 500), "falta el tramo de vuelta");
        }
        assertFalse(registro.bloqueada(nodo(12, 11), nodo(12, 12), 500),
                "la poligonal no debe extenderse mas alla de su ultimo nodo");
    }

    @Test
    @DisplayName("Un tramo de varios kilometros en el mismo eje se descompone en sus aristas unitarias")
    void tramoLargoEnUnSoloEje() {
        RegistroBloqueos registro = new RegistroBloqueos(List.of(tramo(20, 30, 23, 30, 0, 100)));
        for (int x = 20; x < 23; x++) {
            assertTrue(registro.bloqueada(nodo(x, 30), nodo(x + 1, 30), 50),
                    "falta la arista unitaria que arranca en x = " + x);
            assertTrue(registro.bloqueada(nodo(x + 1, 30), nodo(x, 30), 50));
        }
        assertFalse(registro.bloqueada(nodo(23, 30), nodo(24, 30), 50));
    }

    @Test
    @DisplayName("proximoCambio devuelve el siguiente instante de activacion o desactivacion")
    void proximoCambio() {
        RegistroBloqueos registro = new RegistroBloqueos(List.of(
                tramo(10, 10, 11, 10, 100, 200),
                tramo(30, 30, 31, 30, 150, 400)));

        assertEquals(100, registro.proximoCambio(0));
        assertEquals(100, registro.proximoCambio(99));
        assertEquals(150, registro.proximoCambio(100), "el cambio buscado es estrictamente posterior");
        assertEquals(200, registro.proximoCambio(150));
        assertEquals(200, registro.proximoCambio(199));
        assertEquals(400, registro.proximoCambio(200));
        assertEquals(400, registro.proximoCambio(399));
        assertEquals(Long.MAX_VALUE, registro.proximoCambio(400), "ya no queda ningun cambio");
        assertEquals(Long.MAX_VALUE, registro.proximoCambio(100000));
    }

    @Test
    @DisplayName("La mascara vigente se recalcula al cruzar un cambio y no arrastra marcas viejas")
    void laMascaraNoArrastraMarcas() {
        int a = nodo(10, 10);
        int b = nodo(11, 10);
        int c = nodo(30, 30);
        int d = nodo(31, 30);
        RegistroBloqueos registro = new RegistroBloqueos(List.of(
                tramo(10, 10, 11, 10, 100, 200),
                tramo(30, 30, 31, 30, 150, 400)));

        // Se consulta en orden creciente y luego se retrocede, que es lo que ejercita la
        // cache de la mascara en los dos sentidos.
        assertFalse(registro.bloqueada(a, b, 50));
        assertTrue(registro.bloqueada(a, b, 120));
        assertFalse(registro.bloqueada(c, d, 120));
        assertTrue(registro.bloqueada(a, b, 180));
        assertTrue(registro.bloqueada(c, d, 180));
        assertFalse(registro.bloqueada(a, b, 300), "el primer bloqueo ya expiro");
        assertTrue(registro.bloqueada(c, d, 300));
        assertFalse(registro.bloqueada(c, d, 500), "los dos bloqueos expiraron");
        assertFalse(registro.bloqueada(a, b, 500));
        // Se vuelve atras: la mascara debe reconstruirse, no quedarse con lo ultimo visto.
        assertTrue(registro.bloqueada(a, b, 120));
        assertFalse(registro.bloqueada(c, d, 120));
    }

    @Test
    @DisplayName("Dos bloqueos que comparten una calle la liberan solo cuando expiran los dos")
    void bloqueosSuperpuestos() {
        RegistroBloqueos registro = new RegistroBloqueos(List.of(
                tramo(40, 20, 41, 20, 0, 100),
                tramo(40, 20, 41, 20, 50, 300)));
        int a = nodo(40, 20);
        int b = nodo(41, 20);
        assertTrue(registro.bloqueada(a, b, 10));
        assertTrue(registro.bloqueada(a, b, 75), "los dos bloqueos comparten la arista");
        assertTrue(registro.bloqueada(a, b, 150), "sigue bloqueada por el segundo");
        assertFalse(registro.bloqueada(a, b, 300), "expiraron los dos");
        assertFalse(registro.bloqueada(b, a, 300));
    }

    @Test
    @DisplayName("vigentes devuelve solo los bloqueos activos en el instante")
    void bloqueosVigentes() {
        Bloqueo primero = tramo(10, 10, 11, 10, 100, 200);
        Bloqueo segundo = tramo(30, 30, 31, 30, 150, 400);
        RegistroBloqueos registro = new RegistroBloqueos(List.of(primero, segundo));

        assertTrue(registro.vigentes(50).isEmpty());
        assertEquals(List.of(primero), registro.vigentes(120));
        assertEquals(List.of(primero, segundo), registro.vigentes(180));
        assertEquals(List.of(segundo), registro.vigentes(250));
        assertTrue(registro.vigentes(400).isEmpty());
        assertEquals(2, registro.cantidadBloqueos());
        assertEquals(2, registro.todos().size());
    }

    @Test
    @DisplayName("Un registro vacio funciona y no bloquea nada")
    void registroVacio() {
        RegistroBloqueos vacio = RegistroBloqueos.vacio();
        assertTrue(vacio.sinBloqueos());
        assertEquals(0, vacio.cantidadBloqueos());
        assertTrue(vacio.todos().isEmpty());
        assertTrue(vacio.vigentes(0).isEmpty());
        assertTrue(vacio.vigentes(999999).isEmpty());
        assertEquals(Long.MAX_VALUE, vacio.proximoCambio(0));
        assertEquals(Long.MAX_VALUE, vacio.proximoCambio(Long.MAX_VALUE / 2));
        assertFalse(vacio.bloqueada(nodo(10, 10), nodo(11, 10), 500));

        boolean[] mascara = vacio.mascaraBloqueada(1234);
        assertEquals(RegistroBloqueos.TOTAL_ARISTAS, mascara.length);
        for (boolean bloqueada : mascara) {
            assertFalse(bloqueada, "la mascara de un registro vacio no puede tener ninguna arista marcada");
        }

        // La construccion explicita con una lista vacia se comporta igual.
        RegistroBloqueos deListaVacia = new RegistroBloqueos(List.of());
        assertTrue(deListaVacia.sinBloqueos());
        assertEquals(Long.MAX_VALUE, deListaVacia.proximoCambio(0));
    }

    @Test
    @DisplayName("La codificacion de aristas es reversible y respeta los bordes de la ciudad")
    void codificacionDeAristas() {
        int centro = nodo(35, 25);
        for (int direccion = 0; direccion < RegistroBloqueos.DIRECCIONES; direccion++) {
            int arista = RegistroBloqueos.arista(centro, direccion);
            assertEquals(centro, RegistroBloqueos.nodoDeArista(arista));
            assertEquals(direccion, RegistroBloqueos.direccionDeArista(arista));
            int vecino = RegistroBloqueos.nodoVecino(centro, direccion);
            assertEquals(1, Ciudad.distanciaManhattan(centro, vecino));
            assertEquals(arista, RegistroBloqueos.aristaEntre(centro, vecino));
            assertEquals(RegistroBloqueos.aristaEntre(vecino, centro), RegistroBloqueos.aristaInversa(arista));
        }
        // En el borde no hay vecino y por tanto tampoco arista inversa.
        assertEquals(-1, RegistroBloqueos.nodoVecino(nodo(70, 50), RegistroBloqueos.ESTE));
        assertEquals(-1, RegistroBloqueos.nodoVecino(nodo(0, 0), RegistroBloqueos.SUR));
        assertEquals(-1, RegistroBloqueos.aristaInversa(
                RegistroBloqueos.arista(nodo(70, 10), RegistroBloqueos.ESTE)));
        // Dos nodos que no son contiguos no definen ninguna arista.
        assertEquals(-1, RegistroBloqueos.aristaEntre(nodo(10, 10), nodo(12, 10)));
        assertEquals(-1, RegistroBloqueos.direccionEntre(nodo(10, 10), nodo(11, 11)));
    }

    @Test
    @DisplayName("Se rechaza un tramo diagonal y una consulta entre nodos no contiguos")
    void entradasInvalidas() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegistroBloqueos(List.of(tramo(10, 10, 11, 11, 0, 100))),
                "la ciudad no tiene diagonales");
        assertThrows(IllegalArgumentException.class, () -> new RegistroBloqueos(null));

        RegistroBloqueos registro = new RegistroBloqueos(List.of(tramo(10, 10, 11, 10, 0, 100)));
        assertThrows(IllegalArgumentException.class,
                () -> registro.bloqueada(nodo(10, 10), nodo(20, 20), 50));
    }
}
