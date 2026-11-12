package org.kindbox.core.metaheuristica.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Margen al plazo en ALNS y HGS: el escenario es el del colapso de la simulacion de 5 dias.
 * Una sola unidad en el central (27,14), un pedido normal B en (30,14) y un Prioritario 1 A en
 * (33,14), justo detras. Visitar B primero es el recorrido mas corto, pero deja a A con 10
 * minutos de margen, menos de los 24 (10 % de 4 h) que exige el objetivo. Antes del cambio
 * la busqueda elegia ese orden porque solo miraba el costo, y cualquier contratiempo en B
 * hacia vencer a A.
 */
class MargenAlPlazoTest {

    private static final long AHORA = 540;
    private static final int A = 0;
    private static final int B = 1;

    /** Fotografia con el limite de A fijado a 10 minutos despues de su llegada tras B. */
    private static InstanciaPlanificacion instancia() {
        long limiteA = llegadaDeADespuesDeB() + 10;
        return fotografia(limiteA - 240, 4);
    }

    private static InstanciaPlanificacion fotografia(long registroA, int plazoA) {
        return InstanciasDePrueba.fotografia(AHORA,
                List.of(InstanciasDePrueba.pedido(A, 33, 14, 1, registroA, plazoA),
                        InstanciasDePrueba.pedido(B, 30, 14, 1, AHORA, 36)),
                List.of(InstanciasDePrueba.unidadEn("TA01", 27, 14, AHORA)), 1440, InstanciasDePrueba.parametros());
    }

    /** Llegada de A en el orden B, A, medida con el decodificador sobre una fotografia holgada. */
    private static long llegadaDeADespuesDeB() {
        InstanciaPlanificacion holgada = fotografia(AHORA, 36);
        ProgramadorRuta programador = new ProgramadorRuta(holgada);
        int[] orden = {holgada.indiceDePedido(B), holgada.indiceDePedido(A)};
        assertTrue(programador.evaluar(0, orden, new int[] {1, 1}, 2) && programador.ultimaFactible());
        long[] llegadas = new long[2];
        programador.copiarLlegadasEntregas(llegadas);
        return llegadas[1];
    }

    @Test
    void laInsercionNoDejaAlUrgenteAlFiloDeSuPlazo() {
        InstanciaPlanificacion instancia = instancia();
        TareasAlns tareas = new TareasAlns(instancia);
        EstadoAlns estado = new EstadoAlns(instancia, tareas, new ProgramadorRuta(instancia));
        new MotorInsercion(instancia, tareas, ParametrosAlns.porDefecto())
                .reconstruir(estado, new Aleatorio(7), PresupuestoComputo.deIteraciones(1).arrancar(), 0, 0.0);

        assertEquals(0, estado.h());
        assertEquals(0, estado.pedidosSinMargen(), "A debe ir primero aunque el recorrido sea mas largo");
        assertEquals(instancia.indiceDePedido(A), tareas.pedido(estado.tareaEn(0, 0)));
    }

    @Test
    void rechazaUnPlanQueRetrasaUnPedidoPorDebajoDeSuMargen() {
        InstanciaPlanificacion instancia = instancia();
        TareasAlns tareas = new TareasAlns(instancia);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        int a = instancia.indiceDePedido(A);
        int b = instancia.indiceDePedido(B);
        EstadoAlns conMargen = new EstadoAlns(instancia, tareas, programador);
        assertTrue(conMargen.insertar(0, 0, a));
        assertTrue(conMargen.insertar(0, 1, b));
        EstadoAlns alFilo = new EstadoAlns(instancia, tareas, programador);
        assertTrue(alFilo.insertar(0, 0, b));
        assertTrue(alFilo.insertar(0, 1, a));

        assertEquals(0, conMargen.pedidosSinMargen());
        assertEquals(1, alFilo.pedidosSinMargen());
        assertTrue(conMargen.valor().mejorQue(alFilo.valor()), "aunque el plan al filo sea mas barato");
        assertTrue(alFilo.pierdeCompromisosDe(conMargen));
        assertFalse(conMargen.pierdeCompromisosDe(alFilo));
    }

    @Test
    void rechazaSoltarUnPedidoCercaDeSuPlazoAunqueNoVengaDelPlanVigente() {
        // A, Prioritario 1 registrado hace 3 h: le queda 1 h, dentro de su ventana de compromiso.
        InstanciaPlanificacion instancia = fotografia(AHORA - 180, 4);
        TareasAlns tareas = new TareasAlns(instancia);
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        EstadoAlns loAtiende = new EstadoAlns(instancia, tareas, programador);
        assertTrue(loAtiende.insertar(0, 0, instancia.indiceDePedido(A)));
        EstadoAlns loSuelta = new EstadoAlns(instancia, tareas, programador);

        assertTrue(loAtiende.pedidoComprometido(instancia.indiceDePedido(A)));
        assertTrue(loSuelta.pierdeCompromisosDe(loAtiende));

        // B vence en 36 h: soltarlo no rompe ningun compromiso.
        EstadoAlns atiendeB = new EstadoAlns(instancia, tareas, programador);
        assertTrue(atiendeB.insertar(0, 0, instancia.indiceDePedido(B)));
        assertFalse(loSuelta.pierdeCompromisosDe(atiendeB));
    }

    @Test
    void alnsYHgsEntreganAlUrgenteConMargen() {
        InstanciaPlanificacion instancia = instancia();
        for (String nombre : List.of("ALNS", "HGS")) {
            var plan = FabricaAlgoritmos.crear(nombre, 20262L)
                    .resolver(instancia, PresupuestoComputo.deIteraciones(20).arrancar());
            assertEquals(0, plan.solucion().valor().h(), nombre);
            assertEquals(0, plan.solucion().valor().pedidosSinMargen(), nombre + " no debe dejar a A al filo");
        }
    }
}
