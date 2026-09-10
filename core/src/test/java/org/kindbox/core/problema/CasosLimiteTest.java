package org.kindbox.core.problema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Regresion de los casos limite que el planificador puede encontrar en produccion y que un
 * juego de datos tipico no ejercita nunca.
 *
 * <p>Son cuatro: una fotografia sin pedidos, que es lo que ve el planificador de madrugada;
 * una fotografia sin unidades disponibles, que ocurre cuando el mantenimiento preventivo y
 * las averias coinciden; un pedido mayor que la capacidad de un auto, que el apartado 5 del
 * contexto de dominio obliga a resolver con entregas parciales; y una flota compuesta solo
 * por bicicletas, que es el caso de menor capacidad posible. Ninguno debe hacer fallar al
 * planificador ni devolver valores incoherentes.</p>
 */
class CasosLimiteTest {

    private static final long ARRANQUE = InstanciasDePrueba.INICIO_MANANA;
    private static final long CIERRE = InstanciasDePrueba.FIN_MANANA;

    private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();

    @Test
    @DisplayName("Instancia sin pedidos: el plan vacio es optimo y no hay nada que programar")
    void instanciaSinPedidos() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE,
                InstanciasDePrueba.parametros());

        assertEquals(0, instancia.cantidadPedidos());
        assertEquals(0, instancia.demandaTotal());
        assertEquals(1, instancia.cantidadUnidades());
        assertEquals(TipoUnidad.AUTO.capacidad(), instancia.capacidadFlota());
        assertEquals(-1, instancia.indiceDePedido(0), "ningun pedido puede resolverse a un indice");

        Solucion vacia = Solucion.vacia(instancia);
        assertEquals(0, vacia.h());
        assertTrue(vacia.cantidadNoAtendida().isEmpty());
        ValorObjetivo valor = objetivo.evaluar(instancia, vacia);
        assertEquals(0, valor.h());
        assertEquals(0.0, valor.costo(), 0.0);
        assertTrue(valor.sinPedidosPendientes());

        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        Programacion programacion = programador.programar(0, new int[0], new int[0], 0, true);
        assertTrue(programacion.factible());
        assertEquals(0, programacion.kilometros());
        assertTrue(programacion.ruta().paradas().isEmpty());
    }

    @Test
    @DisplayName("Instancia sin unidades: todos los pedidos quedan en el banco y H los cuenta")
    void instanciaSinUnidades() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos,
                List.of(), CIERRE, InstanciasDePrueba.parametros());

        assertEquals(0, instancia.cantidadUnidades());
        assertEquals(0, instancia.capacidadFlota());
        assertEquals(2, instancia.cantidadPedidos());
        assertEquals(10, instancia.demandaTotal());
        assertEquals(-1, instancia.indiceDeUnidad("TA01"));

        Solucion vacia = Solucion.vacia(instancia);
        assertEquals(2, vacia.h(), "sin unidades no se puede atender ningun pedido");
        assertEquals(4, vacia.cantidadNoAtendida().get(0));
        assertEquals(6, vacia.cantidadNoAtendida().get(1));

        ValorObjetivo valor = objetivo.evaluar(instancia, vacia);
        assertEquals(2, valor.h());
        assertEquals(0.0, valor.costo(), 0.0, "sin rutas no se recorre ningun kilometro");
        assertEquals(0, vacia.kilometros());
        assertEquals(0, vacia.unidadesEntregadas());
        assertTrue(vacia.rutasConEntregas().isEmpty());
    }

    @Test
    @DisplayName("Pedido mayor que la capacidad del auto: solo se resuelve con entregas parciales")
    void pedidoMayorQueLaCapacidadDelAuto() {
        int capacidad = TipoUnidad.AUTO.capacidad();
        assertEquals(24, capacidad, "la capacidad del auto es la del enunciado");
        Pedido enorme = InstanciasDePrueba.pedido(0, 27, 20, capacidad + 6, 0, 36);
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(enorme),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE,
                InstanciasDePrueba.parametros());
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        assertSame(Programacion.INFACTIBLE,
                programador.programar(0, new int[] {0}, new int[] {capacidad + 6}, 1, true),
                "una sola visita no puede mover mas que la capacidad de la unidad");

        Programacion partida = programador.programar(0, new int[] {0, 0},
                new int[] {capacidad, 6}, 2, true);
        assertTrue(partida.factible(), "dos entregas parciales si resuelven el pedido");
        assertEquals(capacidad + 6, partida.unidadesEntregadas());
        assertEquals(2, partida.atendidos(), "cada entrega parcial consume su propia visita");

        // Con una sola entrega parcial el pedido queda con remanente y cuenta para H.
        Programacion incompleta = programador.programar(0, new int[] {0}, new int[] {capacidad}, 1, false);
        Solucion plan = new Solucion(List.of(incompleta.ruta()),
                Map.of(0, 6), ValorObjetivo.cero());
        assertEquals(1, objetivo.evaluar(instancia, plan).h(),
                "un remanente de seis paquetes deja el pedido sin atender");
    }

    @Test
    @DisplayName("Flota de solo bicicletas: capacidad cuatro por unidad y todo se parte en entregas pequenas")
    void flotaDeSoloBicicletas() {
        List<UnidadTransporte> flota = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            flota.add(InstanciasDePrueba.unidad(String.format("TB%02d", i), ARRANQUE));
        }
        Pedido pedido = InstanciasDePrueba.pedido(0, 27, 16, 5, 0, 36);
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(pedido),
                flota, CIERRE, InstanciasDePrueba.parametros());

        assertEquals(3, instancia.cantidadUnidades());
        assertEquals(3 * TipoUnidad.BICICLETA.capacidad(), instancia.capacidadFlota());
        for (int i = 0; i < instancia.cantidadUnidades(); i++) {
            assertEquals(TipoUnidad.BICICLETA, instancia.unidadTipo(i));
            assertEquals(4, instancia.unidadCapacidad(i));
        }

        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        assertSame(Programacion.INFACTIBLE, programador.programar(0, new int[] {0}, new int[] {5}, 1, true),
                "cinco paquetes no caben en una bicicleta de capacidad cuatro");

        Programacion partida = programador.programar(0, new int[] {0, 0}, new int[] {4, 1}, 2, true);
        assertTrue(partida.factible());
        assertEquals(5, partida.unidadesEntregadas());
        assertEquals(6, partida.kilometros(), "ida y vuelta al almacen central mas la segunda entrega");
        assertEquals(6 * TipoUnidad.BICICLETA.costoPorKm(), partida.costo(), 1e-9);

        // La demanda total de la instancia cabe en la flota, pero no en una sola unidad.
        assertTrue(instancia.demandaTotal() <= instancia.capacidadFlota());
        assertTrue(instancia.demandaTotal() > instancia.unidadCapacidad(0));
    }

    @Test
    @DisplayName("El constructor de la fotografia exige lo minimo y descarta pedidos ya completos")
    void constructorDeLaFotografia() {
        assertThrows(IllegalStateException.class,
                () -> InstanciaPlanificacion.constructor().construir(),
                "sin parametros no se puede construir una fotografia");
        assertThrows(IllegalStateException.class,
                () -> InstanciaPlanificacion.constructor()
                        .parametros(InstanciasDePrueba.parametros().instantanea())
                        .construir(),
                "sin matriz de distancias tampoco");

        // Un pedido sin remanente pendiente no entra en la fotografia: ya esta atendido.
        InstanciaPlanificacion base = InstanciasDePrueba.fotografia(ARRANQUE,
                List.of(InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36)),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE,
                InstanciasDePrueba.parametros());
        InstanciaPlanificacion sinPendiente = InstanciaPlanificacion.constructor()
                .minutoActual(ARRANQUE)
                .parametros(base.parametros())
                .matriz(base.matriz())
                .almacen(Almacen.crearCentral(), Integer.MAX_VALUE)
                .pedido(InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36), 0)
                .unidad(InstanciasDePrueba.unidad("TA01", ARRANQUE), CIERRE)
                .construir();
        assertEquals(0, sinPendiente.cantidadPedidos());
        assertEquals(0, sinPendiente.demandaTotal());
    }
}
