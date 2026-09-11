package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.evaluacion.ResultadoVerificacion;
import org.kindbox.core.evaluacion.VerificadorRestricciones;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Verificaciones de validez del apartado 12.4 del ISA sobre los dos algoritmos, como pruebas
 * automaticas.
 *
 * <p>El apartado enuncia cuatro comprobaciones. Tres de ellas se resuelven aqui sin depender
 * de {@code data} ni del reloj: <b>factibilidad</b>, es decir que toda solucion devuelta pase
 * {@link VerificadorRestricciones} sin una sola infraccion; <b>equivalencia del modelo</b>, es
 * decir que el {@link ValorObjetivo} que la solucion declara coincida hasta el ultimo bit con
 * el que recalcula {@link FuncionObjetivoJerarquica}, que es la misma implementacion para los
 * dos algoritmos; y <b>monotonia</b> del perfil de convergencia, es decir que la mejor
 * solucion conocida nunca empeore en el orden lexicografico. La cuarta, el respeto del
 * presupuesto de reloj, no cabe en una prueba unitaria estable y se mide en el banco de
 * pruebas del modulo de experimentacion.</p>
 *
 * <p>Todas las corridas usan {@link PresupuestoComputo#deIteracionesConPerfil(long)}: con un
 * presupuesto por iteraciones el resultado no depende de la maquina ni de su carga, de modo
 * que la prueba es reproducible y termina en decimas de segundo. Se anaden los cuatro casos
 * limite del apartado 5 del contexto de dominio, que un juego de datos tipico no ejercita, y
 * la comparacion contra la heuristica constructiva comun en el nivel 1 del objetivo: ninguna
 * busqueda puede devolver mas pedidos sin atender que el plan del que arranca.</p>
 */
class ValidezDeAlgoritmosTest {

    private static final long SEMILLA = 20260911L;
    /** Generaciones de la busqueda genetica hibrida en cada corrida de la prueba. */
    private static final long GENERACIONES_HGS = 40L;
    /** Iteraciones de la busqueda adaptativa de vecindad amplia en cada corrida de la prueba. */
    private static final long ITERACIONES_ALNS = 250L;

    private static final long ARRANQUE = InstanciasDePrueba.INICIO_MANANA;
    private static final long CIERRE = InstanciasDePrueba.FIN_MANANA;

    private final FuncionObjetivoJerarquica objetivo = new FuncionObjetivoJerarquica();

    /** Fotografia de trabajo con el nombre que aparece en los mensajes de fallo. */
    private record Fotografia(String nombre, InstanciaPlanificacion instancia) {
    }

    /** Corrida de un algoritmo sobre una fotografia. */
    private record Corrida(String algoritmo, Fotografia fotografia, ResultadoPlanificacion resultado) {

        String donde() {
            return algoritmo + " sobre " + fotografia.nombre();
        }
    }

    // ------------------------------------------------------ verificaciones 12.4

    @Test
    @DisplayName("Validez 12.4 factibilidad: toda solucion devuelta pasa el verificador sin infracciones")
    void validez124Factibilidad() {
        VerificadorRestricciones verificador = new VerificadorRestricciones();
        for (Corrida corrida : todasLasCorridas()) {
            Solucion solucion = corrida.resultado().solucion();
            ResultadoVerificacion verificacion = verificador.verificar(corrida.fotografia().instancia(), solucion);
            assertTrue(verificacion.factible(), corrida.donde() + " devuelve un plan con infracciones: "
                    + verificacion);
        }
    }

    @Test
    @DisplayName("Validez 12.4 equivalencia del modelo: el valor declarado es el que recalcula la funcion objetivo")
    void validez124EquivalenciaDelModelo() {
        for (Corrida corrida : todasLasCorridas()) {
            InstanciaPlanificacion instancia = corrida.fotografia().instancia();
            Solucion solucion = corrida.resultado().solucion();
            ValorObjetivo recalculado = objetivo.evaluar(instancia, solucion);
            assertIdentico(solucion.valor(), recalculado, corrida.donde());
            // La funcion objetivo no puede depender del orden de recorrido de ninguna
            // coleccion: dos evaluaciones de la misma solucion dan el mismo ultimo bit.
            assertIdentico(recalculado, objetivo.evaluar(instancia, solucion),
                    corrida.donde() + " reevaluado");
            assertEquals(solucion.h(), solucion.cantidadNoAtendida().size(),
                    corrida.donde() + ": H no coincide con el tamano del banco");
            assertEquals(recalculado.h(), solucion.h(),
                    corrida.donde() + ": el banco no coincide con los pedidos con remanente");
        }
    }

    @Test
    @DisplayName("Validez 12.4 monotonia: el perfil de convergencia nunca empeora entre dos hitos")
    void validez124MonotoniaDelPerfil() {
        for (Corrida corrida : todasLasCorridas()) {
            PerfilConvergencia perfil = corrida.resultado().perfil();
            assertNotNull(perfil, corrida.donde() + " no registro perfil de convergencia");
            assertEquals(PerfilConvergencia.HITOS.length, perfil.mediciones().size(),
                    corrida.donde() + ": faltan hitos en el perfil");
            assertTrue(perfil.monotona(), corrida.donde() + ": el perfil empeora entre dos hitos, "
                    + perfil.mediciones());
            ValorObjetivo ultimo = perfil.mediciones().get(perfil.mediciones().size() - 1).valor();
            assertTrue(!ultimo.mejorQue(corrida.resultado().solucion().valor()),
                    corrida.donde() + ": el ultimo hito supera a la solucion devuelta");
        }
    }

    @Test
    @DisplayName("Ninguna busqueda devuelve mas pedidos sin atender que la heuristica constructiva comun")
    void ambosMejoranOIgualanALaConstructivaEnElNivelUno() {
        for (Fotografia fotografia : fotografias()) {
            InstanciaPlanificacion instancia = fotografia.instancia();
            Solucion constructiva = construir(instancia);
            for (Corrida corrida : corridas(fotografia)) {
                int h = corrida.resultado().solucion().h();
                assertTrue(h <= constructiva.h(), corrida.donde() + ": H sube de " + constructiva.h()
                        + " a " + h + " respecto de la heuristica constructiva");
            }
        }
    }

    // ------------------------------------------------------------ casos limite

    @Test
    @DisplayName("Caso limite sin pedidos: el plan vacio es factible y su valor es cero")
    void casoLimiteSinPedidos() {
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE, InstanciasDePrueba.parametros());
        for (Corrida corrida : corridas(new Fotografia("sinPedidos", instancia))) {
            Solucion solucion = corrida.resultado().solucion();
            assertEquals(0, solucion.h(), corrida.donde());
            assertEquals(0.0, solucion.costo(), 0.0, corrida.donde());
            assertTrue(solucion.rutasConEntregas().isEmpty(), corrida.donde() + " inventa rutas sin pedidos");
            assertFactibleYCoherente(corrida);
        }
    }

    @Test
    @DisplayName("Caso limite sin unidades: todos los pedidos quedan en el banco y ninguno se pierde")
    void casoLimiteSinUnidades() {
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos, List.of(),
                CIERRE, InstanciasDePrueba.parametros());
        for (Corrida corrida : corridas(new Fotografia("sinUnidades", instancia))) {
            Solucion solucion = corrida.resultado().solucion();
            assertEquals(2, solucion.h(), corrida.donde() + " no puede atender nada sin unidades");
            assertEquals(4, solucion.cantidadNoAtendida().get(0), corrida.donde());
            assertEquals(6, solucion.cantidadNoAtendida().get(1), corrida.donde());
            assertEquals(0, solucion.unidadesEntregadas(), corrida.donde());
            assertFactibleYCoherente(corrida);
        }
    }

    @Test
    @DisplayName("Caso limite de un pedido mayor que la capacidad del auto: se resuelve con entregas partidas")
    void casoLimitePedidoMayorQueLaCapacidadDelAuto() {
        int capacidad = TipoUnidad.AUTO.capacidad();
        Pedido enorme = InstanciasDePrueba.pedido(0, 30, 20, capacidad + 6, 0, 36);
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, List.of(enorme),
                List.of(InstanciasDePrueba.unidad("TA01", ARRANQUE)), CIERRE, InstanciasDePrueba.parametros());
        for (Corrida corrida : corridas(new Fotografia("pedidoEnorme", instancia))) {
            Solucion solucion = corrida.resultado().solucion();
            assertEquals(0, solucion.h(), corrida.donde() + " deja sin atender un pedido que cabe partido");
            assertEquals(capacidad + 6, solucion.unidadesEntregadas(), corrida.donde());
            assertTrue(entregas(solucion) >= 2, corrida.donde()
                    + ": un pedido mayor que la capacidad exige al menos dos visitas");
            assertFactibleYCoherente(corrida);
        }
    }

    @Test
    @DisplayName("Caso limite de flota de solo bicicletas: la capacidad de cuatro obliga a partir las entregas")
    void casoLimiteFlotaDeSoloBicicletas() {
        List<UnidadTransporte> flota = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            flota.add(InstanciasDePrueba.unidad(String.format("TB%02d", i), ARRANQUE));
        }
        List<Pedido> pedidos = List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 5, 0, 36),
                InstanciasDePrueba.pedido(1, 24, 18, 3, 0, 36),
                InstanciasDePrueba.pedido(2, 31, 10, 4, 0, 36));
        InstanciaPlanificacion instancia = InstanciasDePrueba.fotografia(ARRANQUE, pedidos, flota,
                CIERRE, InstanciasDePrueba.parametros());
        Solucion constructiva = construir(instancia);
        for (Corrida corrida : corridas(new Fotografia("soloBicicletas", instancia))) {
            Solucion solucion = corrida.resultado().solucion();
            assertFactibleYCoherente(corrida);
            assertTrue(solucion.h() <= constructiva.h(), corrida.donde());
            for (Ruta ruta : solucion.rutas()) {
                assertEquals(TipoUnidad.BICICLETA, ruta.tipoUnidad(), corrida.donde()
                        + ": la flota solo tiene bicicletas");
            }
        }
    }

    // ------------------------------------------------------------------ apoyo

    /** Las tres fotografias de trabajo, todas sin bloqueos y revisables a mano. */
    private static List<Fotografia> fotografias() {
        return List.of(
                new Fotografia("variada(14)", InstanciasDePrueba.instanciaVariada(14)),
                new Fotografia("flotaMixta(16)", InstanciasDePrueba.instanciaFlotaMixta(16)),
                new Fotografia("plazosAjustados(18)", InstanciasDePrueba.instanciaPlazosAjustados(18)));
    }

    /** Corridas de los dos algoritmos sobre todas las fotografias de trabajo. */
    private static List<Corrida> todasLasCorridas() {
        List<Corrida> todas = new ArrayList<>();
        for (Fotografia fotografia : fotografias()) {
            todas.addAll(corridas(fotografia));
        }
        return todas;
    }

    /**
     * Corridas de los dos algoritmos sobre una fotografia, cada uno con su propia heuristica
     * constructiva, que reutiliza arreglos de trabajo y no se puede compartir.
     */
    private static List<Corrida> corridas(Fotografia fotografia) {
        BusquedaGeneticaHibrida hgs = new BusquedaGeneticaHibrida(new AhorrosClarkeWright(),
                ParametrosHgs.porDefecto(), SEMILLA);
        BusquedaAdaptativaVecindadAmplia alns = new BusquedaAdaptativaVecindadAmplia(
                new AhorrosClarkeWright(), SEMILLA);
        return List.of(
                new Corrida(BusquedaGeneticaHibrida.NOMBRE, fotografia, hgs.resolver(fotografia.instancia(),
                        PresupuestoComputo.deIteracionesConPerfil(GENERACIONES_HGS).arrancar())),
                new Corrida(BusquedaAdaptativaVecindadAmplia.NOMBRE, fotografia, alns.resolver(
                        fotografia.instancia(),
                        PresupuestoComputo.deIteracionesConPerfil(ITERACIONES_ALNS).arrancar())));
    }

    /** Plan de la heuristica constructiva comun sobre la fotografia. */
    private static Solucion construir(InstanciaPlanificacion instancia) {
        return new AhorrosClarkeWright().construir(instancia, new ProgramadorRuta(instancia),
                new Aleatorio(SEMILLA));
    }

    /** Comprueba de una vez la factibilidad y la equivalencia del modelo de una corrida. */
    private void assertFactibleYCoherente(Corrida corrida) {
        InstanciaPlanificacion instancia = corrida.fotografia().instancia();
        Solucion solucion = corrida.resultado().solucion();
        ResultadoVerificacion verificacion = new VerificadorRestricciones().verificar(instancia, solucion);
        assertTrue(verificacion.factible(), corrida.donde() + ": " + verificacion);
        assertIdentico(solucion.valor(), objetivo.evaluar(instancia, solucion), corrida.donde());
    }

    /** Igualdad exacta de dos valores objetivo, bit a bit en los dos terminos reales. */
    private static void assertIdentico(ValorObjetivo declarado, ValorObjetivo recalculado, String donde) {
        assertEquals(recalculado.h(), declarado.h(), donde + ": H declarada distinta de la recalculada");
        assertEquals(Double.doubleToLongBits(recalculado.costo()), Double.doubleToLongBits(declarado.costo()),
                donde + ": costo declarado " + declarado.costo() + " y recalculado " + recalculado.costo());
        assertEquals(Double.doubleToLongBits(recalculado.penalizacion()),
                Double.doubleToLongBits(declarado.penalizacion()),
                donde + ": penalizacion declarada distinta de la recalculada");
    }

    /** Numero de paradas de entrega del plan. */
    private static int entregas(Solucion solucion) {
        int n = 0;
        for (Ruta ruta : solucion.rutas()) {
            n += ruta.cantidadEntregas();
        }
        return n;
    }
}
