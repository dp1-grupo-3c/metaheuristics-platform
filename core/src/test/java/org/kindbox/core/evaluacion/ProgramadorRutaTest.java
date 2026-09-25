package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.TipoParada;

/**
 * Decodificador de secuencias de visita, apartados 6.3.2 y 7.3.2 del ISA.
 *
 * <p>Se comprueban las tres propiedades de las que dependen los dos algoritmos: que el
 * desfase suma tanto el incumplimiento de plazo como el exceso sobre el cierre del turno,
 * que es la unica forma de que la subpoblacion infactible de HGS tenga a donde descender;
 * que la pausa de alimentacion, cuando se inserta, cae dentro de la ventana admisible de la
 * restriccion dura 4 del apartado 2.6; y que la unidad nunca carga ni entrega mas de lo que
 * la ruta necesita, porque el inventario de los almacenes intermedios es un recurso
 * compartido por todo el plan.</p>
 *
 * <p>Los instantes esperados estan calculados a mano. La unidad TA01 es un auto a 40 Km/h,
 * de modo que 6 Km son 9 minutos, y el acondicionamiento en el cliente dura una hora.</p>
 */
class ProgramadorRutaTest {

    /** 07:00 del primer dia, arranque del turno de manana. */
    private static final long ARRANQUE = InstanciasDePrueba.INICIO_MANANA;
    /** 15:00 del primer dia, cierre del turno de manana. */
    private static final long CIERRE_TURNO = InstanciasDePrueba.FIN_MANANA;

    /** Fotografia con un solo auto en el almacen central y los pedidos indicados. */
    private static InstanciaPlanificacion conAuto(List<Pedido> pedidos, long finHorizonte) {
        return InstanciasDePrueba.fotografia(ARRANQUE, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), finHorizonte,
                InstanciasDePrueba.parametros());
    }

    @Test
    void noRepitePausaEnLaMismaJornadaPeroLaRequiereEnLaSiguiente() {
        var unidad = InstanciasDePrueba.unidad("TA01", 540);
        unidad.inicioTurnoAlimentacion(Turno.inicioDelTurno(540));
        var pedidos = List.of(InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36));
        var foto = InstanciasDePrueba.fotografia(540, pedidos, List.of(unidad), 900,
                InstanciasDePrueba.parametros());
        var ruta = new ProgramadorRuta(foto).programar(0, new int[] {0}, new int[] {5}, 1, false);
        assertTrue(ruta.factible());
        assertTrue(ruta.ruta().paradas().stream().noneMatch(p -> p.tipo() == TipoParada.ALIMENTACION));
        var siguiente = InstanciasDePrueba.fotografia(960, pedidos, List.of(unidad), 1380,
                InstanciasDePrueba.parametros());
        var nueva = new ProgramadorRuta(siguiente).programar(0, new int[] {0}, new int[] {5}, 1, false);
        assertTrue(nueva.factible());
        assertEquals(1, nueva.ruta().paradas().stream().filter(p -> p.tipo() == TipoParada.ALIMENTACION).count());
        assertTrue(foto.unidadAlimentada(0), "la fotografia anterior es inmutable");
    }

    @Test
    void urgenciaAnticipaCierreSinCambiarElPlazoContractual() {
        var pedido = InstanciasDePrueba.pedido(0, 27, 20, 5, 395, 8);
        var foto = conAuto(List.of(pedido), CIERRE_TURNO);
        assertEquals(875, foto.pedidoMinutoLimite(0));
        assertEquals(840, foto.pedidoMinutoLimiteEfectivo(0));
    }

    @Test
    @DisplayName("El desfase recoge los minutos de incumplimiento del plazo del pedido")
    void desfasePorIncumplimientoDePlazo() {
        // Limite a las 07:05 y llegada a las 07:09: cuatro minutos de incumplimiento.
        Pedido apurado = InstanciasDePrueba.pedido(0, 27, 20, 5, 365, 1);
        assertEquals(425, apurado.minutoLimite());
        InstanciaPlanificacion instancia = conAuto(List.of(apurado), CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);

        assertNotNull(programacion.ruta());
        assertEquals(4, programacion.desfaseMinutos(), "los cuatro minutos de retraso son el desfase");
        assertFalse(programacion.factible(), "una ruta con desfase no es factible");
        assertTrue(programacion.minutoFin() <= CIERRE_TURNO, "la ruta si cabe en el turno");
    }

    @Test
    @DisplayName("El desfase recoge tambien los minutos en que la ruta se pasa del cierre del turno")
    void desfasePorExcesoSobreElCierreDelTurno() {
        // Plazo holgado, de modo que el unico desfase posible es el exceso de turno.
        Pedido holgado = InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36);
        // Horizonte artificial a las 07:40: la ruta termina a las 08:09, veintinueve minutos
        // mas tarde, y no cabe ninguna pausa.
        InstanciaPlanificacion instancia = conAuto(List.of(holgado), 460);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);

        assertNotNull(programacion.ruta());
        assertEquals(489, programacion.minutoFin(), "la entrega cierra a las 08:09");
        assertEquals(29, programacion.desfaseMinutos(), "el exceso sobre el cierre del turno es el desfase");
        assertFalse(programacion.factible());
    }

    @Test
    @DisplayName("El desfase suma el incumplimiento de plazo y el exceso de turno")
    void desfaseAcumulaAmbasViolaciones() {
        Pedido apurado = InstanciasDePrueba.pedido(0, 27, 20, 5, 365, 1);
        InstanciaPlanificacion instancia = conAuto(List.of(apurado), 460);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);

        assertEquals(4 + 29, programacion.desfaseMinutos(),
                "cuatro minutos de plazo incumplido mas veintinueve de exceso de turno");
    }

    @Test
    @DisplayName("La pausa de alimentacion cae dentro de la ventana admisible cuando se inserta")
    void laPausaCaeEnLaVentanaAdmisible() {
        ParametrosOperacion parametros = InstanciasDePrueba.parametros();
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36),
                InstanciasDePrueba.pedido(1, 30, 20, 5, 0, 36),
                InstanciasDePrueba.pedido(2, 30, 25, 5, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE_TURNO, parametros);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0, 1, 2}, new int[] {5, 5, 5}, 3, true);

        assertTrue(programacion.factible(), "la ruta cabe holgada en el turno");
        assertTrue(programador.ultimaIncluyePausa(), "con holgura la pausa debe insertarse");
        Ruta ruta = programacion.ruta();
        assertTrue(ruta.incluyeAlimentacion());

        Parada pausa = null;
        for (Parada parada : ruta.paradas()) {
            if (parada.tipo() == TipoParada.ALIMENTACION) {
                pausa = parada;
            }
        }
        assertNotNull(pausa, "la ruta declara pausa pero no la materializa");

        long inicioTurno = Turno.inicioDelTurno(ARRANQUE);
        int separacion = parametros.minutosSeparacionCambioTurno();
        int duracion = parametros.minutosAlimentacion();
        assertEquals(duracion, pausa.duracionServicio(), "la pausa dura una hora continua");
        assertTrue(pausa.minutoLlegada() >= inicioTurno + separacion,
                "la pausa arranca a las " + pausa.minutoLlegada() + " y el turno abrio a las " + inicioTurno);
        assertTrue(pausa.minutoSalida() <= inicioTurno + Turno.DURACION_MIN - separacion,
                "la pausa cierra a las " + pausa.minutoSalida() + " y el turno cierra a las "
                        + (inicioTurno + Turno.DURACION_MIN));
        // Sigue siendo una ruta coherente: las paradas van en orden y todo cabe en el turno.
        long anterior = ruta.minutoInicio();
        for (Parada parada : ruta.paradas()) {
            assertTrue(parada.minutoLlegada() >= anterior,
                    "la parada " + parada + " llega antes de que termine la anterior");
            assertTrue(parada.minutoSalida() >= parada.minutoLlegada());
            anterior = parada.minutoSalida();
        }
        assertTrue(ruta.minutoFin() <= CIERRE_TURNO, "la ruta con pausa sigue cerrando dentro del turno");
    }

    @Test
    @DisplayName("Sin sitio para la pausa la ruta se programa sin ella y no se marca infactible por eso")
    void sinHolguraLaRutaSeProgramaSinPausa() {
        // Horizonte a las 08:20: la ruta cierra a las 08:09 y ninguna posicion de la pausa
        // cabe sin pasarse, porque la ventana de alimentacion no abre hasta las 08:00.
        Pedido holgado = InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36);
        InstanciaPlanificacion instancia = conAuto(List.of(holgado), 500);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);

        assertFalse(programador.ultimaIncluyePausa(), "no cabe ninguna posicion admisible para la pausa");
        assertFalse(programacion.ruta().incluyeAlimentacion());
        assertTrue(programacion.factible(),
                "la pausa es exigible por jornada y no por ruta: su ausencia no hace infactible el tramo");
        assertEquals(0, programacion.desfaseMinutos());
        assertEquals(489, programacion.minutoFin());
    }

    @Test
    @DisplayName("La unidad no carga mas de lo que resta por entregar en la ruta")
    void noCargaMasDeLoPendiente() {
        Pedido pedido = InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36);
        InstanciaPlanificacion instancia = conAuto(List.of(pedido), CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);

        int cargado = 0;
        int entregado = 0;
        for (Parada parada : programacion.ruta().paradas()) {
            if (parada.tipo() == TipoParada.ABASTECIMIENTO) {
                cargado += parada.cantidad();
            } else if (parada.tipo() == TipoParada.ENTREGA) {
                entregado += parada.cantidad();
            }
        }
        assertEquals(5, cargado, "el auto tiene capacidad 24 pero la ruta solo necesita 5 paquetes");
        assertEquals(5, entregado, "no se entrega mas de lo pendiente del pedido");
        assertEquals(5, programacion.unidadesEntregadas());
        assertEquals(1, programacion.atendidos());
    }

    @Test
    @DisplayName("Con entregas parciales lo cargado iguala a lo entregado y nada excede la capacidad")
    void entregasParcialesNoDesbordanNiSobrecargan() {
        // Treinta paquetes no caben de una vez en un auto de 24: hacen falta dos visitas.
        Pedido grande = InstanciasDePrueba.pedido(0, 27, 20, 30, 0, 36);
        InstanciaPlanificacion instancia = conAuto(List.of(grande), CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0, 0}, new int[] {15, 15}, 2, true);

        assertTrue(programacion.factible());
        int capacidad = instancia.unidadCapacidad(0);
        int cargado = 0;
        int entregado = 0;
        for (Parada parada : programacion.ruta().paradas()) {
            assertTrue(parada.cantidad() <= capacidad,
                    "ninguna parada puede mover mas de la capacidad de la unidad: " + parada);
            if (parada.tipo() == TipoParada.ABASTECIMIENTO) {
                cargado += parada.cantidad();
            } else if (parada.tipo() == TipoParada.ENTREGA) {
                entregado += parada.cantidad();
            }
        }
        assertEquals(30, entregado, "las dos entregas parciales completan el pedido");
        assertEquals(entregado, cargado,
                "arrancando de vacio, lo cargado en los almacenes debe igualar a lo entregado");
        assertEquals(30, programacion.unidadesEntregadas());
    }

    @Test
    @DisplayName("Una visita que supera la capacidad de la unidad no admite programacion")
    void visitaMayorQueLaCapacidad() {
        Pedido grande = InstanciasDePrueba.pedido(0, 27, 20, 30, 0, 36);
        InstanciaPlanificacion instancia = conAuto(List.of(grande), CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[] {0}, new int[] {30}, 1, true);

        assertSame(Programacion.INFACTIBLE, programacion);
        assertTrue(programacion.sinProgramacion());
        assertFalse(programador.evaluar(0, new int[] {0}, new int[] {30}, 1),
                "evaluar debe rechazar la misma secuencia");
    }

    @Test
    @DisplayName("El abastecimiento descuenta del inventario intermedio solo cuando se pide consumirlo")
    void consumoDelInventarioIntermedio() {
        // Unidad y pedido junto al almacen intermedio nor-oeste, de modo que el desvio
        // minimo lo gane ese almacen y no el central.
        Pedido cercano = InstanciasDePrueba.pedido(0, 12, 40, 5, 0, 36);
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(cercano),
                List.of(InstanciasDePrueba.unidadEn("TA01", 12, 39, ARRANQUE)), CIERRE_TURNO,
                InstanciasDePrueba.parametros());
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        int inicial = programador.inventarioDisponible(1);

        programador.programar(0, new int[] {0}, new int[] {5}, 1, false);
        assertEquals(inicial, programador.inventarioDisponible(1),
                "tantear un movimiento no puede ensuciar el inventario compartido");

        Programacion definitiva = programador.programar(0, new int[] {0}, new int[] {5}, 1, true);
        assertEquals(inicial - 5, programador.inventarioDisponible(1),
                "el plan confirmado si descuenta del almacen intermedio");

        boolean cargoEnIntermedio = false;
        for (Parada parada : definitiva.ruta().paradas()) {
            if (parada.tipo() == TipoParada.ABASTECIMIENTO) {
                    cargoEnIntermedio |= parada.idAlmacen() == instancia.almacenId(1);
            }
        }
        assertTrue(cargoEnIntermedio, "el almacen elegido debe ser el intermedio nor-oeste");

        programador.reiniciarInventarios();
        assertEquals(inicial, programador.inventarioDisponible(1),
                "reiniciar debe devolver el inventario al valor de la instancia");
    }

    @Test
    @DisplayName("evaluar y programar producen exactamente los mismos numeros")
    void evaluarYProgramarCoinciden() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36),
                InstanciasDePrueba.pedido(1, 30, 20, 4, 0, 12),
                InstanciasDePrueba.pedido(2, 30, 25, 3, 0, 8));
        InstanciaPlanificacion instancia = conAuto(pedidos, CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        int[] secuencia = {2, 0, 1};
        int[] cantidades = {3, 5, 4};
        assertTrue(programador.evaluar(0, secuencia, cantidades, 3));
        int kilometros = programador.ultimosKilometros();
        double costo = programador.ultimoCosto();
        int desfase = programador.ultimoDesfase();
        long fin = programador.ultimoMinutoFin();

        Programacion programacion = programador.programar(0, secuencia, cantidades, 3, false);
        assertEquals(kilometros, programacion.kilometros());
        assertEquals(costo, programacion.costo());
        assertEquals(desfase, programacion.desfaseMinutos());
        assertEquals(fin, programacion.minutoFin());
        assertEquals(kilometros, programacion.ruta().kilometros(),
                "los kilometros de la ruta materializada deben ser los mismos");
    }

    @Test
    @DisplayName("Una secuencia vacia produce una ruta vacia y factible")
    void secuenciaVacia() {
        InstanciaPlanificacion instancia = conAuto(
                List.of(InstanciasDePrueba.pedido(0, 27, 20, 5, 0, 36)), CIERRE_TURNO);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        Programacion programacion = programador.programar(0, new int[0], new int[0], 0, true);

        assertTrue(programacion.factible());
        assertEquals(0, programacion.kilometros());
        assertEquals(0.0, programacion.costo());
        assertEquals(0, programacion.unidadesEntregadas());
        assertTrue(programacion.ruta().paradas().isEmpty());
        assertEquals(ARRANQUE, programacion.minutoFin());
    }

    @Test
    @DisplayName("La cota inferior de kilometros nunca supera los kilometros reales de la ruta")
    void cotaInferiorDeKilometros() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 5, 45, 5, 0, 36),
                InstanciasDePrueba.pedido(1, 60, 8, 4, 0, 36));
        InstanciaPlanificacion instancia = conAuto(pedidos, 4000);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);

        int[] secuencia = {0, 1};
        int cota = programador.cotaInferiorKilometros(0, secuencia, 2);
        Programacion programacion = programador.programar(0, secuencia, new int[] {5, 4}, 2, true);

        assertTrue(cota <= programacion.kilometros(),
                "la cota " + cota + " supera los kilometros reales " + programacion.kilometros());
        assertEquals(0, programador.cotaInferiorKilometros(0, secuencia, 0));
    }
}
