package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;

/**
 * Segundo modo de arranque del apartado 7.3.5 del ISA visto desde el algoritmo: partir del
 * plan vigente en lugar de la heuristica constructiva.
 *
 * <p>Se exige que la busqueda adaptativa lo declare y lo implemente, que arrancar desde un
 * plan no devuelva jamas algo peor que ese plan, que la corrida siga siendo repetible con la
 * misma semilla y el mismo plan, y que un plan que no aporta nada, vacio o nulo, deje la
 * ejecucion exactamente igual que el arranque ordinario. Se exige tambien que la busqueda
 * genetica hibrida declare que no lo admite y que su implementacion por defecto ignore el plan
 * en lugar de fallar, que es lo que el motor de simulacion necesita para no tener que
 * distinguir algoritmos.</p>
 */
class ArranqueDesdePlanVigenteTest {

    private static final long SEMILLA = 20260911L;
    private static final long ITERACIONES_ALNS = 250L;
    private static final long ITERACIONES_HGS = 60L;

    private static BusquedaAdaptativaVecindadAmplia nuevoAlns() {
        return new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA);
    }

    private static PresupuestoComputo presupuesto(long iteraciones) {
        return PresupuestoComputo.deIteraciones(iteraciones).arrancar();
    }

    @Test
    @DisplayName("ALNS arranca del plan vigente, no lo empeora y repite el resultado")
    void alnsArrancaDelPlanVigente() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(16);
        assertTrue(nuevoAlns().admiteArranqueDesdePlanVigente());

        Solucion plan = nuevoAlns().resolver(instancia, presupuesto(ITERACIONES_ALNS)).solucion();
        ResultadoPlanificacion desde =
                nuevoAlns().resolverDesde(instancia, presupuesto(ITERACIONES_ALNS), plan);

        assertTrue(desde.solucion().h() <= plan.h(),
                "H " + desde.solucion().h() + " peor que la del plan de partida " + plan.h());
        assertFalse(plan.valor().mejorQue(desde.solucion().valor()),
                "arrancar del plan devolvio algo peor que el propio plan");

        ResultadoPlanificacion otra =
                nuevoAlns().resolverDesde(instancia, presupuesto(ITERACIONES_ALNS), plan);
        assertEquals(desde.solucion().h(), otra.solucion().h(), "H repetible");
        assertEquals(Double.doubleToLongBits(desde.solucion().costo()),
                Double.doubleToLongBits(otra.solucion().costo()), "costo repetible");
        assertEquals(desde.solucion().cantidadNoAtendida(), otra.solucion().cantidadNoAtendida(), "banco repetible");
        assertEquals(SEMILLA, desde.semilla(), "sin semilla explicita manda la del constructor");

        ResultadoPlanificacion conSemilla =
                nuevoAlns().resolverDesde(instancia, presupuesto(ITERACIONES_ALNS), 77L, plan);
        assertEquals(77L, conSemilla.semilla(), "el resultado informa la semilla de la ejecucion");
    }

    @Test
    @DisplayName("Un plan sin entregas o nulo deja el arranque ordinario intacto")
    void unPlanSinEntregasEquivaleAlArranqueOrdinario() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaVariada(14);
        ResultadoPlanificacion ordinaria = nuevoAlns().resolver(instancia, presupuesto(ITERACIONES_ALNS));

        for (Solucion plan : new Solucion[] {null, Solucion.vacia(instancia)}) {
            ResultadoPlanificacion desde = nuevoAlns().resolverDesde(instancia, presupuesto(ITERACIONES_ALNS), plan);
            assertEquals(ordinaria.solucion().h(), desde.solucion().h(), "H con plan " + plan);
            assertEquals(Double.doubleToLongBits(ordinaria.solucion().costo()),
                    Double.doubleToLongBits(desde.solucion().costo()), "costo con plan " + plan);
            assertEquals(ordinaria.iteraciones(), desde.iteraciones(), "iteraciones con plan " + plan);
        }
    }

    @Test
    @DisplayName("HGS no admite el arranque desde el plan vigente y lo ignora sin fallar")
    void hgsIgnoraElPlanVigente() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(16);
        BusquedaGeneticaHibrida hgs = new BusquedaGeneticaHibrida(new AhorrosClarkeWright());
        assertFalse(hgs.admiteArranqueDesdePlanVigente());

        Solucion plan = nuevoAlns().resolver(instancia, presupuesto(ITERACIONES_ALNS)).solucion();
        ResultadoPlanificacion conPlan =
                hgs.resolverDesde(instancia, presupuesto(ITERACIONES_HGS), SEMILLA, plan);
        ResultadoPlanificacion sinPlan = hgs.resolver(instancia, presupuesto(ITERACIONES_HGS), SEMILLA);

        assertEquals(sinPlan.solucion().h(), conPlan.solucion().h(), "H");
        assertEquals(Double.doubleToLongBits(sinPlan.solucion().costo()),
                Double.doubleToLongBits(conPlan.solucion().costo()), "costo");
        assertEquals(sinPlan.iteraciones(), conPlan.iteraciones(), "iteraciones");
    }
}
