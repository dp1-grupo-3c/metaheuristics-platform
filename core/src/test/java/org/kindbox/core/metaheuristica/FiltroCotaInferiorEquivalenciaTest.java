package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.alns.ParametrosAlns;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;

/**
 * Equivalencia del filtro de cota inferior del apartado 10 del ISA.
 *
 * <p>El filtro consulta la concatenacion de resumenes de {@code DatosSecuencia} antes de
 * llamar al decodificador y descarta lo que la cota ya condena. Solo es admisible si descarta
 * exclusivamente movimientos que el decodificador habria rechazado, y la forma de exigirlo es
 * la mas estricta que permite el presupuesto por iteraciones: con la misma semilla y el mismo
 * numero de iteraciones, cada algoritmo con el filtro y sin el tiene que devolver el mismo
 * plan, comparado por los bits del costo y parada a parada, y el mismo perfil de convergencia.
 * Cualquier poda indebida cambiaria alguna decision de la busqueda y, a partir de ahi, la
 * sucesion entera de numeros aleatorios.</p>
 *
 * <p>Una prueba de equivalencia que no poda nada no demuestra nada, de modo que se exige
 * ademas que el contador de podas sea positivo en alguna instancia. Las instancias de plazos
 * ajustados estan hechas para eso: casi toda insercion llega tarde a algun pedido.</p>
 */
class FiltroCotaInferiorEquivalenciaTest {

    private static final long SEMILLA = 20260911L;
    private static final long ITERACIONES_HGS = 40L;
    private static final long ITERACIONES_ALNS = 250L;

    private static List<InstanciaPlanificacion> instancias() {
        return List.of(
                InstanciasDePrueba.instanciaVariada(14),
                InstanciasDePrueba.instanciaFlotaMixta(16),
                InstanciasDePrueba.instanciaPlazosAjustados(18),
                InstanciasDePrueba.instanciaPlazosAjustados(26));
    }

    @Test
    @DisplayName("HGS devuelve el mismo plan con el filtro de cota inferior y sin el")
    void hgsEquivalente() {
        long maximoPodas = 0L;
        for (InstanciaPlanificacion instancia : instancias()) {
            BusquedaGeneticaHibrida conFiltro = new BusquedaGeneticaHibrida(new AhorrosClarkeWright(),
                    ParametrosHgs.porDefecto().filtroCotaInferior(true), SEMILLA);
            BusquedaGeneticaHibrida sinFiltro = new BusquedaGeneticaHibrida(new AhorrosClarkeWright(),
                    ParametrosHgs.porDefecto().filtroCotaInferior(false), SEMILLA);

            ResultadoPlanificacion con = conFiltro.resolver(instancia, presupuesto(ITERACIONES_HGS));
            ResultadoPlanificacion sin = sinFiltro.resolver(instancia, presupuesto(ITERACIONES_HGS));

            assertEquals(ITERACIONES_HGS, con.iteraciones(), "el bucle principal debe agotar el presupuesto");
            assertIdenticos(sin, con, instancia);
            assertEquals(0L, sinFiltro.podasCotaInferior(), "sin filtro no puede haber podas");
            maximoPodas = Math.max(maximoPodas, conFiltro.podasCotaInferior());
        }
        assertTrue(maximoPodas > 0L, "el filtro debe podar en alguna instancia para que la prueba diga algo");
    }

    @Test
    @DisplayName("ALNS devuelve el mismo plan con el filtro de cota inferior y sin el")
    void alnsEquivalente() {
        long maximoPodas = 0L;
        for (InstanciaPlanificacion instancia : instancias()) {
            BusquedaAdaptativaVecindadAmplia conFiltro = new BusquedaAdaptativaVecindadAmplia(
                    new AhorrosClarkeWright(), ParametrosAlns.porDefecto().filtroCotaInferior(true), SEMILLA);
            BusquedaAdaptativaVecindadAmplia sinFiltro = new BusquedaAdaptativaVecindadAmplia(
                    new AhorrosClarkeWright(), ParametrosAlns.porDefecto().filtroCotaInferior(false), SEMILLA);

            ResultadoPlanificacion con = conFiltro.resolver(instancia, presupuesto(ITERACIONES_ALNS));
            ResultadoPlanificacion sin = sinFiltro.resolver(instancia, presupuesto(ITERACIONES_ALNS));

            assertEquals(ITERACIONES_ALNS, con.iteraciones(), "el bucle principal debe agotar el presupuesto");
            assertIdenticos(sin, con, instancia);
            assertEquals(0L, sinFiltro.podasCotaInferior(), "sin filtro no puede haber podas");
            maximoPodas = Math.max(maximoPodas, conFiltro.podasCotaInferior());
        }
        assertTrue(maximoPodas > 0L, "el filtro debe podar en alguna instancia para que la prueba diga algo");
    }

    private static PresupuestoComputo presupuesto(long iteraciones) {
        return PresupuestoComputo.deIteracionesConPerfil(iteraciones).arrancar();
    }

    /** Exige igualdad exacta de todo lo que no es reloj de pared. */
    private static void assertIdenticos(ResultadoPlanificacion esperado, ResultadoPlanificacion obtenido,
                                        InstanciaPlanificacion instancia) {
        final String contexto = esperado.algoritmo() + " con " + instancia.cantidadPedidos() + " pedidos y "
                + instancia.cantidadUnidades() + " unidades: ";
        assertEquals(esperado.iteraciones(), obtenido.iteraciones(), contexto + "iteraciones");

        Solucion a = esperado.solucion();
        Solucion b = obtenido.solucion();
        assertEquals(a.h(), b.h(), contexto + "H");
        assertEquals(Double.doubleToLongBits(a.costo()), Double.doubleToLongBits(b.costo()),
                contexto + "costo " + a.costo() + " contra " + b.costo());
        assertEquals(Double.doubleToLongBits(a.valor().penalizacion()),
                Double.doubleToLongBits(b.valor().penalizacion()), contexto + "penalizacion");
        assertEquals(a.cantidadNoAtendida(), b.cantidadNoAtendida(), contexto + "banco");

        List<Ruta> rutasA = a.rutas();
        List<Ruta> rutasB = b.rutas();
        assertEquals(rutasA.size(), rutasB.size(), contexto + "numero de rutas");
        for (int r = 0; r < rutasA.size(); r++) {
            assertEquals(rutasA.get(r).codigoUnidad(), rutasB.get(r).codigoUnidad(), contexto + "unidad " + r);
            // Parada es un registro de primitivos: la igualdad de listas compara campo a campo.
            assertEquals(rutasA.get(r).paradas(), rutasB.get(r).paradas(), contexto + "paradas de la ruta " + r);
        }

        List<PerfilConvergencia.Medicion> perfilA = esperado.perfil().mediciones();
        List<PerfilConvergencia.Medicion> perfilB = obtenido.perfil().mediciones();
        assertEquals(perfilA.size(), perfilB.size(), contexto + "hitos del perfil");
        for (int i = 0; i < perfilA.size(); i++) {
            assertEquals(perfilA.get(i).iteraciones(), perfilB.get(i).iteraciones(), contexto + "hito " + i);
            assertEquals(perfilA.get(i).valor(), perfilB.get(i).valor(), contexto + "valor del hito " + i);
        }
    }
}
