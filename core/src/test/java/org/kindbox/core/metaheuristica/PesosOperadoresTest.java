package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.alns.ParametrosAlns;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Solucion;

/**
 * Exposicion de los pesos de la capa adaptativa en el resultado, apartado 7.3.3 del ISA.
 *
 * <p>El apartado afirma que los pesos vigentes se exponen para los reportes del apartado 12,
 * porque su evolucion informa sobre que operadores trabajan en cada regimen de presupuesto. La
 * capa vive dentro de la ejecucion y sin este campo del resultado se perdia al terminarla.
 * Aqui se exige que ALNS informe un peso por operador, con las mismas claves y en el mismo
 * orden en toda ejecucion, que HGS informe un mapa vacio porque no tiene capa adaptativa y que
 * el mapa del resultado sea inmutable y no comparta estado con quien lo construyo.</p>
 */
class PesosOperadoresTest {

    private static final long SEMILLA = 20260911L;
    private static final long ITERACIONES = 350L;

    /** Operadores del apartado 7.3.2, en el orden en que la busqueda los declara. */
    private static final List<String> OPERADORES = List.of(
            "remocion-aleatoria",
            "remocion-del-peor",
            "remocion-por-afinidad",
            "remocion-cadenas-adyacentes",
            "remocion-por-criticidad",
            "remocion-de-ruta-completa",
            "insercion-voraz",
            "insercion-por-arrepentimiento",
            "insercion-voraz-con-parpadeo");

    private static ResultadoPlanificacion correrAlns(InstanciaPlanificacion instancia) {
        return new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), SEMILLA)
                .resolver(instancia, PresupuestoComputo.deIteraciones(ITERACIONES).arrancar());
    }

    @Test
    @DisplayName("ALNS informa el peso final de cada operador, con orden estable y repetible")
    void alnsInformaLosPesosDeLaCapaAdaptativa() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(16);
        ResultadoPlanificacion resultado = correrAlns(instancia);
        Map<String, Double> pesos = resultado.pesosOperadores();

        assertEquals(OPERADORES, List.copyOf(pesos.keySet()), "claves y orden de los pesos");

        double minimo = ParametrosAlns.porDefecto().pesoMinimoOperador();
        boolean algunoCambio = false;
        for (Map.Entry<String, Double> peso : pesos.entrySet()) {
            assertTrue(peso.getValue() >= minimo, peso.getKey() + " por debajo del peso minimo: " + peso.getValue());
            algunoCambio |= peso.getValue() != 1.0;
        }
        // Con 350 iteraciones y segmentos de 100 se cerraron tres segmentos: los pesos ya no
        // pueden ser todos el uno con que arranca la capa.
        assertTrue(algunoCambio, "la capa adaptativa no actualizo ningun peso: " + pesos);

        assertEquals(pesos, correrAlns(instancia).pesosOperadores(), "misma semilla, mismos pesos");
        assertThrows(UnsupportedOperationException.class, () -> pesos.put("otro-operador", 1.0));
    }

    @Test
    @DisplayName("Sin capa adaptativa el mapa va vacio, y el resultado copia el que recibe")
    void sinCapaAdaptativaElMapaVaVacio() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaFlotaMixta(16);
        ResultadoPlanificacion hgs = new BusquedaGeneticaHibrida(new AhorrosClarkeWright())
                .resolver(instancia, PresupuestoComputo.deIteraciones(40L).arrancar());
        assertTrue(hgs.pesosOperadores().isEmpty(), "HGS no tiene capa adaptativa");

        // Una fotografia sin pedidos sale por la via que no llega a construir la capa.
        InstanciaPlanificacion vacia = InstanciasDePrueba.fotografia(InstanciasDePrueba.INICIO_MANANA,
                List.of(), List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());
        assertTrue(correrAlns(vacia).pesosOperadores().isEmpty(), "sin pedidos no hay busqueda ni pesos");

        Map<String, Double> fuente = new LinkedHashMap<>();
        fuente.put("remocion-aleatoria", 2.0);
        Solucion solucion = Solucion.vacia(vacia);
        ResultadoPlanificacion resultado =
                new ResultadoPlanificacion("X", solucion, null, 0L, 0L, 0L, fuente);
        fuente.put("insercion-voraz", 3.0);
        assertEquals(Map.of("remocion-aleatoria", 2.0), resultado.pesosOperadores(), "copia defensiva");
        assertTrue(new ResultadoPlanificacion("X", solucion, null, 0L, 0L, 0L, null)
                .pesosOperadores().isEmpty(), "un mapa nulo se toma vacio");
    }
}
