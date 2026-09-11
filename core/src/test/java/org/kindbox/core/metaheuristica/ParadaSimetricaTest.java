package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.alns.ParametrosAlns;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Simetria del criterio de parada de los dos algoritmos, apartados 6.3.4 y 7.3.4 del ISA.
 *
 * <p>La comparacion del apartado 12 solo es valida si los dos algoritmos reciben el mismo
 * computo. La busqueda genetica hibrida se detenia por defecto tras 20 000 generaciones sin
 * mejora y la busqueda adaptativa no se detenia nunca por estancamiento, de modo que en una
 * instancia facil uno podia devolver el reloj sin gastar y el otro no. Aqui se exige que el
 * estancamiento venga desactivado en los dos, que con el desactivado los dos agoten el
 * presupuesto hasta la ultima iteracion, que activado detenga a los dos antes y que la fabrica
 * lo exponga con una clave por algoritmo.</p>
 */
class ParadaSimetricaTest {

    private static final long SEMILLA = 20260911L;
    private static final long ITERACIONES_HGS = 200L;
    private static final long ITERACIONES_ALNS = 300L;
    private static final long TOPE_ESTANCAMIENTO = 5L;

    private static InstanciaPlanificacion instancia() {
        return InstanciasDePrueba.instanciaFlotaMixta(16);
    }

    private static ResultadoPlanificacion correrHgs(long topeSinMejora) {
        ParametrosHgs parametros = ParametrosHgs.porDefecto().maximoGeneracionesSinMejora(topeSinMejora);
        return new BusquedaGeneticaHibrida(new AhorrosClarkeWright(), parametros, SEMILLA)
                .resolver(instancia(), PresupuestoComputo.deIteraciones(ITERACIONES_HGS).arrancar());
    }

    private static ResultadoPlanificacion correrAlns(long topeSinMejora) {
        ParametrosAlns parametros = ParametrosAlns.porDefecto().maximoIteracionesSinMejora(topeSinMejora);
        return new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), parametros, SEMILLA)
                .resolver(instancia(), PresupuestoComputo.deIteraciones(ITERACIONES_ALNS).arrancar());
    }

    @Test
    @DisplayName("El estancamiento viene desactivado en los dos y solo gobierna el presupuesto")
    void desactivadoPorDefectoEnLosDos() {
        assertEquals(0L, ParametrosHgs.porDefecto().maximoGeneracionesSinMejora(), "HGS por defecto");
        assertEquals(0L, ParametrosAlns.porDefecto().maximoIteracionesSinMejora(), "ALNS por defecto");

        assertEquals(ITERACIONES_HGS, correrHgs(0L).iteraciones(), "HGS debe agotar el presupuesto");
        assertEquals(ITERACIONES_ALNS, correrAlns(0L).iteraciones(), "ALNS debe agotar el presupuesto");
    }

    @Test
    @DisplayName("Activado, el estancamiento detiene a los dos antes de agotar el presupuesto")
    void activadoDetieneALosDos() {
        long generaciones = correrHgs(TOPE_ESTANCAMIENTO).iteraciones();
        assertTrue(generaciones >= TOPE_ESTANCAMIENTO && generaciones < ITERACIONES_HGS,
                "HGS se detuvo en la generacion " + generaciones);

        long iteraciones = correrAlns(TOPE_ESTANCAMIENTO).iteraciones();
        assertTrue(iteraciones >= TOPE_ESTANCAMIENTO && iteraciones < ITERACIONES_ALNS,
                "ALNS se detuvo en la iteracion " + iteraciones);
    }

    @Test
    @DisplayName("Cada algoritmo expone su tope por una clave de la fabrica, y el cero lo desactiva")
    void laFabricaExponeElTopeEnLosDos() {
        Properties ajustes = new Properties();
        ajustes.setProperty("hgs.maximoGeneracionesSinMejora", "7");
        ajustes.setProperty("alns.maximoIteracionesSinMejora", "9");
        assertEquals(7L, assertInstanceOf(BusquedaGeneticaHibrida.class,
                FabricaAlgoritmos.crear("HGS", 1L, ajustes)).parametros().maximoGeneracionesSinMejora());
        assertEquals(9L, assertInstanceOf(BusquedaAdaptativaVecindadAmplia.class,
                FabricaAlgoritmos.crear("ALNS", 1L, ajustes)).parametros().maximoIteracionesSinMejora());

        Properties ceros = new Properties();
        ceros.setProperty("hgs.maximoGeneracionesSinMejora", "0");
        ceros.setProperty("alns.maximoIteracionesSinMejora", "0");
        assertEquals(0L, assertInstanceOf(BusquedaGeneticaHibrida.class,
                FabricaAlgoritmos.crear("HGS", 1L, ceros)).parametros().maximoGeneracionesSinMejora());
        assertEquals(0L, assertInstanceOf(BusquedaAdaptativaVecindadAmplia.class,
                FabricaAlgoritmos.crear("ALNS", 1L, ceros)).parametros().maximoIteracionesSinMejora());

        assertThrows(IllegalArgumentException.class,
                () -> ParametrosHgs.porDefecto().maximoGeneracionesSinMejora(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> ParametrosAlns.porDefecto().maximoIteracionesSinMejora(-1L));
    }
}
