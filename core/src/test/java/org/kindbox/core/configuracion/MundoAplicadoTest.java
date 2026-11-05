package org.kindbox.core.configuracion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kindbox.core.grafo.OraculoDistancias;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;

class MundoAplicadoTest {

    private static ParametrosMundo mundo(int largo, int ancho) {
        ParametrosMundo enunciado = ParametrosMundo.delEnunciado();
        return enunciado.conMapaYAlmacenes(new ParametrosMundo.Mapa(largo, ancho), List.of(
                new ParametrosMundo.AlmacenParam(0, "Almacen Central", 5, 5, true, null),
                new ParametrosMundo.AlmacenParam(1, "Almacen Intermedio Nor-Oeste", largo, ancho, false, 300),
                new ParametrosMundo.AlmacenParam(2, "Almacen Intermedio Este", 0, 0, false, 200)));
    }

    @AfterEach
    void restablecer() {
        ParametrosMundo.delEnunciado().aplicarAlNucleo();
    }

    @Test
    void elMapaCambiaLaCodificacionDeLosNodosYElGrafo() {
        mundo(100, 80).aplicarAlNucleo();
        assertEquals(101 * 81, Ciudad.TOTAL_NODOS);
        assertEquals(101 * 81 * RegistroBloqueos.DIRECCIONES, RegistroBloqueos.totalAristas());
        int esquina = Ciudad.nodo(100, 80);
        assertEquals(100, Ciudad.x(esquina));
        assertEquals(80, Ciudad.y(esquina));

        // La busqueda alcanza nodos que no existen en el mapa del enunciado.
        OraculoDistancias oraculo = new OraculoDistancias();
        int[] distancia = new int[Ciudad.TOTAL_NODOS];
        int[] predecesor = new int[Ciudad.TOTAL_NODOS];
        oraculo.distanciasDesde(Ciudad.nodo(0, 0), null, distancia, predecesor);
        assertEquals(180, distancia[esquina]);
    }

    @Test
    void losBloqueosSeDimensionanConElMapaVigente() {
        mundo(100, 80).aplicarAlNucleo();
        RegistroBloqueos registro = new RegistroBloqueos(List.of(
                new Bloqueo(new int[] {Ciudad.nodo(90, 70), Ciudad.nodo(91, 70)}, 0, 60)));
        assertEquals(RegistroBloqueos.totalAristas(), registro.mascaraBloqueada(10).length);
        assertEquals(RegistroBloqueos.totalAristas(), RegistroBloqueos.vacio().mascaraBloqueada(10).length);
    }

    @Test
    void lasCorridasTomanLosAlmacenesConfigurados() {
        mundo(20, 10).aplicarAlNucleo();
        List<Almacen> red = Almacen.todos();
        assertEquals(Ciudad.nodo(5, 5), Almacen.crearCentral().nodo());
        assertEquals(Ciudad.nodo(20, 10), red.get(1).nodo());
        assertEquals(300, red.get(1).capacidad());
        assertEquals(200, red.get(2).capacidad());
        assertThrows(IllegalArgumentException.class, () -> Ciudad.nodo(21, 5));
    }

    @Test
    void elEnunciadoNoDependeDeLoConfigurado() {
        ParametrosMundo enunciado = ParametrosMundo.delEnunciado();
        mundo(20, 10).aplicarAlNucleo();
        assertEquals(enunciado, ParametrosMundo.delEnunciado());
        ParametrosMundo.delEnunciado().aplicarAlNucleo();
        assertEquals(70, Ciudad.LARGO_KM);
        assertEquals(Ciudad.nodo(27, 14), Almacen.crearCentral().nodo());
    }
}
