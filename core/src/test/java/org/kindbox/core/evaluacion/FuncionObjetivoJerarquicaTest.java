package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Funcion objetivo jerarquica de dos niveles, apartado 2.5 del ISA.
 *
 * <p>La propiedad decisiva es que el orden es <b>lexicografico de verdad</b>: una solucion
 * con {@code H} menor gana siempre, por caro que sea su plan, porque la politica de la
 * empresa es que todos los productos lleguen dentro del plazo fijado y las ventanas son
 * duras. Se comprueba con costos separados por seis ordenes de magnitud, que es mucho mas
 * de lo que cualquier plan real puede diferir, y ademas sobre planes construidos con el
 * decodificador real y no solo sobre el registro de valores.</p>
 */
class FuncionObjetivoJerarquicaTest {

    private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();

    /** Ruta artificial de una unidad que entrega las cantidades indicadas y recorre los kilometros dados. */
    private static Ruta ruta(String codigo, TipoUnidad tipo, int kilometros, int[] pedidos, int[] cantidades) {
        List<Parada> paradas = new ArrayList<>();
        int nodo = Ciudad.nodo(30, 20);
        for (int i = 0; i < pedidos.length; i++) {
            // Todos los kilometros se cargan en la primera parada; el reparto no cambia el costo.
            int km = i == 0 ? kilometros : 0;
            paradas.add(Parada.entrega(nodo, pedidos[i], cantidades[i], 500 + i, 560 + i, km));
        }
        if (pedidos.length == 0) {
            paradas.add(Parada.abastecimiento(nodo, 0, 0, 500, 500, kilometros));
        }
        return new Ruta(codigo, tipo, Ciudad.nodo(27, 14), 420, paradas);
    }

    private static Solucion solucion(InstanciaPlanificacion instancia, List<Ruta> rutas,
                                     Map<Integer, Integer> banco, ValorObjetivo valor) {
        return new Solucion(rutas, banco, valor);
    }

    @Test
    void laUrgenciaTieneOrdenTransitivoInclusoConErroresDeRedondeo() {
        var valores = new ArrayList<>(List.of(new ValorObjetivo(1, 0.0, 3, 0),
                new ValorObjetivo(1, 0.75e-9, 2, 0), new ValorObjetivo(1, 1.5e-9, 1, 0)));
        valores.sort(null);
        for (int i = 0; i < valores.size(); i++) {
            for (int j = i + 1; j < valores.size(); j++) {
                assertTrue(valores.get(i).compareTo(valores.get(j)) <= 0);
            }
        }
        assertEquals(0, new ValorObjetivo(1, 0.02, 8, 0)
                .compareTo(new ValorObjetivo(1, 0.02 + 1e-15, 8, 0)));
    }

    @Test
    @DisplayName("Una solucion con H menor gana siempre, sea cual sea su costo")
    void elOrdenEsLexicografico() {
        ValorObjetivo caraPeroCompleta = new ValorObjetivo(0, 1_000_000_000.0, 0.0);
        ValorObjetivo baratisimaPeroIncompleta = new ValorObjetivo(1, 0.0, 0.0);

        assertTrue(caraPeroCompleta.mejorQue(baratisimaPeroIncompleta),
                "el nivel 1 domina: mil millones de soles no valen un pedido sin entregar");
        assertFalse(baratisimaPeroIncompleta.mejorQue(caraPeroCompleta));
        assertTrue(caraPeroCompleta.compareTo(baratisimaPeroIncompleta) < 0);

        // Un pedido menos en el banco siempre se prefiere, aunque la penalizacion blanda sea enorme.
        ValorObjetivo conPenalizacion = new ValorObjetivo(0, 0.0, 1_000_000_000.0);
        assertTrue(conPenalizacion.mejorQue(new ValorObjetivo(1, 0.0, 0.0)));

        // Dentro del mismo nivel 1 manda el costo penalizado.
        assertTrue(new ValorObjetivo(2, 10.0, 0.0).mejorQue(new ValorObjetivo(2, 11.0, 0.0)));
        assertTrue(new ValorObjetivo(2, 10.0, 0.0).mejorQue(new ValorObjetivo(2, 10.0, 0.5)));
        assertEquals(0, new ValorObjetivo(2, 10.0, 0.5).compareTo(new ValorObjetivo(2, 10.5, 0.0)),
                "el costo penalizado es la suma del costo y los terminos blandos");
    }

    @Test
    @DisplayName("El orden lexicografico se mantiene sobre planes evaluados de verdad")
    void ordenLexicograficoSobrePlanesReales() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(
                InstanciasDePrueba.INICIO_MANANA, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());

        // Plan completo pero carisimo: 5 000 Km de auto son 40 000 soles.
        Solucion completa = solucion(instancia,
                List.of(ruta("TA01", TipoUnidad.AUTO, 5000, new int[] {0, 1}, new int[] {4, 6})),
                Map.of(), ValorObjetivo.cero());
        // Plan baratisimo que deja un pedido a medias: un solo kilometro de bicicleta.
        Solucion incompleta = solucion(instancia,
                List.of(ruta("TB01", TipoUnidad.BICICLETA, 1, new int[] {0}, new int[] {4})),
                Map.of(1, 6), ValorObjetivo.cero());

        ValorObjetivo valorCompleta = objetivo.evaluar(instancia, completa);
        ValorObjetivo valorIncompleta = objetivo.evaluar(instancia, incompleta);

        assertEquals(0, valorCompleta.h(), "el plan completo no deja ningun pedido pendiente");
        assertEquals(1, valorIncompleta.h(), "el plan barato deja un pedido sin atender");
        assertEquals(5000 * 8.00, valorCompleta.costo(), 1e-9);
        assertEquals(1 * 3.00, valorIncompleta.costo(), 1e-9);
        assertTrue(valorCompleta.mejorQue(valorIncompleta),
                "un plan de 40 000 soles con H = 0 gana a uno de 3 soles con H = 1");
    }

    @Test
    @DisplayName("H cuenta los pedidos con cualquier remanente, no las unidades sin entregar")
    void hCuentaPedidosConRemanente() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 10, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36),
                InstanciasDePrueba.pedido(2, 20, 10, 3, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(
                InstanciasDePrueba.INICIO_MANANA, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());

        // El pedido 0 se entrega en dos partes que lo completan; del 1 falta un solo paquete;
        // el 2 no se toca. H debe valer 2.
        Solucion plan = solucion(instancia,
                List.of(ruta("TA01", TipoUnidad.AUTO, 100,
                        new int[] {0, 0, 1}, new int[] {6, 4, 5})),
                Map.of(1, 1, 2, 3), ValorObjetivo.cero());

        ValorObjetivo valor = objetivo.evaluar(instancia, plan);
        assertEquals(2, valor.h(), "solo el pedido 0 queda completo");
        assertEquals(100 * 8.00, valor.costo(), 1e-9);
    }

    @Test
    @DisplayName("El costo suma por tipo de unidad y no depende del orden de las rutas")
    void elCostoNoDependeDelOrdenDeLasRutas() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(
                InstanciasDePrueba.INICIO_MANANA, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());

        Ruta enAuto = ruta("TA01", TipoUnidad.AUTO, 37, new int[] {0}, new int[] {4});
        Ruta enMoto = ruta("TM01", TipoUnidad.MOTO, 23, new int[] {1}, new int[] {6});
        Ruta enBicicleta = ruta("TB01", TipoUnidad.BICICLETA, 11, new int[0], new int[0]);

        ValorObjetivo unOrden = objetivo.evaluar(instancia,
                solucion(instancia, List.of(enAuto, enMoto, enBicicleta), Map.of(), ValorObjetivo.cero()));
        ValorObjetivo otroOrden = objetivo.evaluar(instancia,
                solucion(instancia, List.of(enBicicleta, enMoto, enAuto), Map.of(), ValorObjetivo.cero()));

        double esperado = 37 * 8.00 + 23 * 6.00 + 11 * 3.00;
        assertEquals(esperado, unOrden.costo(), 0.0);
        assertEquals(unOrden.costo(), otroOrden.costo(), 0.0,
                "el apartado 12.4 exige identico valor objetivo ante la misma solucion");
        assertEquals(unOrden.h(), otroOrden.h());
        assertEquals(esperado, objetivo.costoRuta(enAuto) + objetivo.costoRuta(enMoto)
                + objetivo.costoRuta(enBicicleta), 1e-9);
    }

    @Test
    @DisplayName("La desviacion del plan vigente cuenta los pedidos que cambian de unidad")
    void desviacionDelPlanVigente() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(
                InstanciasDePrueba.INICIO_MANANA, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA),
                        InstanciasDePrueba.unidad("TM01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());

        InstanciaPlanificacion conPlanVigente = conAsignacionVigente(pedidos);

        Solucion respetaElPlan = solucion(conPlanVigente,
                List.of(ruta("TA01", TipoUnidad.AUTO, 10, new int[] {0}, new int[] {4}),
                        ruta("TM01", TipoUnidad.MOTO, 10, new int[] {1}, new int[] {6})),
                Map.of(), ValorObjetivo.cero());
        Solucion intercambiaLasUnidades = solucion(conPlanVigente,
                List.of(ruta("TA01", TipoUnidad.AUTO, 10, new int[] {1}, new int[] {6}),
                        ruta("TM01", TipoUnidad.MOTO, 10, new int[] {0}, new int[] {4})),
                Map.of(), ValorObjetivo.cero());

        assertEquals(0, objetivo.desviacionDelPlanVigente(conPlanVigente, respetaElPlan));
        assertEquals(2, objetivo.desviacionDelPlanVigente(conPlanVigente, intercambiaLasUnidades));
        assertEquals(0, objetivo.desviacionDelPlanVigente(instancia, respetaElPlan),
                "sin plan vigente no hay nada de lo que desviarse");

        ValorObjetivo estable = objetivo.evaluar(conPlanVigente, respetaElPlan);
        ValorObjetivo inestable = objetivo.evaluar(conPlanVigente, intercambiaLasUnidades);
        assertEquals(0.0, estable.penalizacion(), 1e-9);
        assertEquals(2 * FuncionObjetivo.PESO_ESTABILIDAD, inestable.penalizacion(), 1e-9);
        assertTrue(estable.mejorQue(inestable), "a igual costo, el plan estable desempata a favor");
    }

    /** Fotografia identica a la anterior pero con un plan vigente que asigna cada pedido a una unidad. */
    private static InstanciaPlanificacion conAsignacionVigente(List<Pedido> pedidos) {
        InstanciaPlanificacion base = InstanciasDePrueba.fotografia(
                InstanciasDePrueba.INICIO_MANANA, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA),
                        InstanciasDePrueba.unidad("TM01", InstanciasDePrueba.INICIO_MANANA)),
                InstanciasDePrueba.FIN_MANANA, InstanciasDePrueba.parametros());
        return InstanciaPlanificacion.constructor()
                .minutoActual(base.minutoActual())
                .parametros(base.parametros())
                .matriz(base.matriz())
                .almacen(Almacen.crearCentral(), Integer.MAX_VALUE)
                .almacen(Almacen.intermedioNorOeste(), Almacen.CAPACIDAD_INTERMEDIO)
                .almacen(Almacen.intermedioEste(), Almacen.CAPACIDAD_INTERMEDIO)
                .pedido(pedidos.get(0), pedidos.get(0).cantidad())
                .pedido(pedidos.get(1), pedidos.get(1).cantidad())
                .unidad(InstanciasDePrueba.unidad("TA01", InstanciasDePrueba.INICIO_MANANA),
                        InstanciasDePrueba.FIN_MANANA)
                .unidad(InstanciasDePrueba.unidad("TM01", InstanciasDePrueba.INICIO_MANANA),
                        InstanciasDePrueba.FIN_MANANA)
                .asignacionVigente(0, "TA01")
                .asignacionVigente(1, "TM01")
                .construir();
    }
}
