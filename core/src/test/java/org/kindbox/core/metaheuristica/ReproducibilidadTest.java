package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;

/**
 * Reproducibilidad bit a bit de los dos algoritmos con presupuesto por iteraciones.
 *
 * <p>El apartado 10 del ISA fija una semilla por corrida para que cada ejecucion se pueda
 * repetir. Con presupuesto de reloj eso no se cumple, porque el numero de iteraciones y todas
 * las decisiones que dependen de la fraccion consumida varian con la carga de la maquina. Con
 * {@link PresupuestoComputo#deIteraciones(long)} si debe cumplirse, y aqui se exige en su
 * forma mas estricta: mismo H, mismo costo comparado por sus bits, mismo banco, mismas rutas
 * con las mismas paradas en el mismo orden y el mismo perfil de convergencia salvo el reloj.
 * Cada corrida repetida usa un algoritmo y una heuristica recien construidos, y una tercera
 * reutiliza el primer algoritmo para comprobar ademas que no arrastra estado entre
 * ejecuciones.</p>
 */
class ReproducibilidadTest {

    private static final long SEMILLA = 20260911L;
    private static final long ITERACIONES_HGS = 120L;
    private static final long ITERACIONES_ALNS = 300L;

    private static List<InstanciaPlanificacion> instancias() {
        return List.of(InstanciasDePrueba.instanciaVariada(14), InstanciasDePrueba.instanciaFlotaMixta(16));
    }

    private static Algoritmo nuevoHgs() {
        return new BusquedaGeneticaHibrida(new AhorrosClarkeWright(), ParametrosHgs.porDefecto(), SEMILLA);
    }

    private static Algoritmo nuevoAlns() {
        return new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA);
    }

    @Test
    @DisplayName("HGS con la misma semilla y presupuesto por iteraciones devuelve el mismo plan")
    void hgsReproducible() {
        comprobarReproducible(ReproducibilidadTest::nuevoHgs, ITERACIONES_HGS);
    }

    @Test
    @DisplayName("ALNS con la misma semilla y presupuesto por iteraciones devuelve el mismo plan")
    void alnsReproducible() {
        comprobarReproducible(ReproducibilidadTest::nuevoAlns, ITERACIONES_ALNS);
    }

    @Test
    @DisplayName("Resolver sin semilla equivale a resolver con la semilla del constructor")
    void resolverSinSemillaUsaLaDelConstructor() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(12);
        comprobarSemilla(nuevoHgs(), instancia, ITERACIONES_HGS);
        comprobarSemilla(nuevoAlns(), instancia, ITERACIONES_ALNS);
    }

    private static void comprobarReproducible(Supplier<Algoritmo> fabrica, long iteraciones) {
        for (InstanciaPlanificacion instancia : instancias()) {
            Algoritmo algoritmo = fabrica.get();
            ResultadoPlanificacion primera = algoritmo.resolver(instancia, presupuesto(iteraciones));
            ResultadoPlanificacion segunda = fabrica.get().resolver(instancia, presupuesto(iteraciones));
            ResultadoPlanificacion tercera = algoritmo.resolver(instancia, presupuesto(iteraciones));

            assertEquals(iteraciones, primera.iteraciones(), "el bucle principal debe agotar el presupuesto");
            assertIdenticos(primera, segunda);
            assertIdenticos(primera, tercera);
        }
    }

    private static void comprobarSemilla(Algoritmo algoritmo, InstanciaPlanificacion instancia, long iteraciones) {
        ResultadoPlanificacion sinSemilla = algoritmo.resolver(instancia, presupuesto(iteraciones));
        ResultadoPlanificacion conSemilla = algoritmo.resolver(instancia, presupuesto(iteraciones), SEMILLA);
        assertEquals(SEMILLA, sinSemilla.semilla());
        assertIdenticos(sinSemilla, conSemilla);

        ResultadoPlanificacion otra = algoritmo.resolver(instancia, presupuesto(iteraciones), 77L);
        assertEquals(77L, otra.semilla(), "el resultado informa la semilla de la ejecucion");
    }

    private static PresupuestoComputo presupuesto(long iteraciones) {
        return PresupuestoComputo.deIteracionesConPerfil(iteraciones).arrancar();
    }

    /** Exige igualdad exacta de todo lo que no es reloj de pared. */
    private static void assertIdenticos(ResultadoPlanificacion esperado, ResultadoPlanificacion obtenido) {
        final String algoritmo = esperado.algoritmo();
        assertEquals(esperado.iteraciones(), obtenido.iteraciones(), algoritmo + ": iteraciones");
        assertEquals(esperado.semilla(), obtenido.semilla(), algoritmo + ": semilla");
        // La capa adaptativa tambien tiene que repetirse: es estado de la busqueda, no un
        // adorno del reporte, y los pesos deciden que operador se elige en cada iteracion.
        assertEquals(esperado.pesosOperadores(), obtenido.pesosOperadores(), algoritmo + ": pesos de operadores");
        assertEquals(List.copyOf(esperado.pesosOperadores().keySet()),
                List.copyOf(obtenido.pesosOperadores().keySet()), algoritmo + ": orden de los pesos");

        Solucion a = esperado.solucion();
        Solucion b = obtenido.solucion();
        assertEquals(a.h(), b.h(), algoritmo + ": H");
        assertEquals(Double.doubleToLongBits(a.costo()), Double.doubleToLongBits(b.costo()),
                algoritmo + ": costo " + a.costo() + " contra " + b.costo());
        assertEquals(Double.doubleToLongBits(a.valor().penalizacion()),
                Double.doubleToLongBits(b.valor().penalizacion()), algoritmo + ": penalizacion");
        assertEquals(a.cantidadNoAtendida(), b.cantidadNoAtendida(), algoritmo + ": banco");

        List<Ruta> rutasA = a.rutas();
        List<Ruta> rutasB = b.rutas();
        assertEquals(rutasA.size(), rutasB.size(), algoritmo + ": numero de rutas");
        for (int r = 0; r < rutasA.size(); r++) {
            Ruta ra = rutasA.get(r);
            Ruta rb = rutasB.get(r);
            assertEquals(ra.codigoUnidad(), rb.codigoUnidad(), algoritmo + ": unidad de la ruta " + r);
            assertEquals(ra.minutoInicio(), rb.minutoInicio(), algoritmo + ": inicio de la ruta " + r);
            // Parada es un registro de primitivos, de modo que la igualdad de listas compara
            // cada parada campo a campo y en orden.
            assertEquals(ra.paradas(), rb.paradas(), algoritmo + ": paradas de la ruta " + r);
        }

        List<PerfilConvergencia.Medicion> perfilA = esperado.perfil().mediciones();
        List<PerfilConvergencia.Medicion> perfilB = obtenido.perfil().mediciones();
        assertEquals(perfilA.size(), perfilB.size(), algoritmo + ": hitos del perfil");
        for (int i = 0; i < perfilA.size(); i++) {
            assertEquals(perfilA.get(i).iteraciones(), perfilB.get(i).iteraciones(), algoritmo + ": hito " + i);
            assertEquals(perfilA.get(i).valor(), perfilB.get(i).valor(), algoritmo + ": valor del hito " + i);
        }
    }
}
