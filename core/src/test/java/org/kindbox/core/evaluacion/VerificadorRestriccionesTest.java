package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.grafo.MatrizDistanciasReticula;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Deteccion de las siete restricciones duras del apartado 2.6 del ISA, una por una.
 *
 * <p>El verificador es el juez de la verificacion de validez del apartado 12.4: si dejara
 * pasar una infraccion, toda la comparacion entre los dos algoritmos quedaria sin respaldo.
 * Por eso la prueba no le entrega planes de los algoritmos, que se espera que sean correctos,
 * sino rutas <b>construidas a mano</b> con un defecto conocido cada una, y exige que reporte
 * ese defecto y ninguno mas. Los nueve valores de {@link Infraccion} tienen su caso, y la
 * prueba falla si alguno se queda sin el.</p>
 *
 * <p>Las rutas declaran instantes y kilometros que el verificador no da por buenos: los
 * recalcula con la matriz de distancias y con la velocidad vigente del tipo de unidad. Un
 * caso comprueba justamente eso, que una llegada declarada antes de lo que la velocidad
 * permite no vuelve factible una entrega fuera de plazo. Otro contrasta el verificador de
 * planificacion, que juzga los bloqueos con la informacion del instante de la fotografia, con
 * el de ejecucion de {@link VerificadorRestricciones#deEjecucion}, que los juzga en el
 * instante real de paso.</p>
 */
class VerificadorRestriccionesTest {

    private static final long ARRANQUE = InstanciasDePrueba.INICIO_MANANA;
    private static final long CIERRE = InstanciasDePrueba.FIN_MANANA;

    /** Pedido de plazo holgado: su instante limite queda a 36 horas del inicio del escenario. */
    private static final int HOLGADO = 0;
    /** Segundo pedido de plazo holgado, para las rutas con mas de una entrega. */
    private static final int SEGUNDO = 1;
    /** Pedido de cuatro horas registrado a medianoche: toda entrega del turno de manana llega tarde. */
    private static final int VENCIDO = 2;
    /** Unidades disponibles en cada almacen intermedio de la fotografia con inventario escaso. */
    private static final int INVENTARIO_ESCASO = 2;

    /** Instante en que el auto alcanza el destino del pedido vencido saliendo del central. */
    private static final long LLEGADA_REAL_AL_VENCIDO = 509L;

    private final InstanciaPlanificacion instancia = fotografia(InstanciasDePrueba.INVENTARIO_PLENO, "TA01");
    private final VerificadorRestricciones verificador = new VerificadorRestricciones();

    /** Ruta con un defecto conocido, con la fotografia y el verificador que la juzgan. */
    private record Caso(String nombre, Infraccion esperada, InstanciaPlanificacion instancia,
                        VerificadorRestricciones verificador, Ruta ruta) {
    }

    // ------------------------------------------------------- una por infraccion

    @Test
    @DisplayName("Cada restriccion del apartado 2.6 tiene una ruta que la infringe y el verificador la senala")
    void cadaInfraccionSeDetectaPorSeparado() {
        EnumSet<Infraccion> cubiertas = EnumSet.noneOf(Infraccion.class);
        for (Caso caso : casos()) {
            ResultadoVerificacion resultado = caso.verificador().verificarRuta(caso.instancia(), caso.ruta());
            assertFalse(resultado.factible(), caso.nombre() + ": la ruta tendria que resultar infactible");
            assertEquals(Set.of(caso.esperada()), resultado.tipos(),
                    caso.nombre() + " reporta otras infracciones: " + resultado);
            cubiertas.add(caso.esperada());
        }
        assertEquals(EnumSet.allOf(Infraccion.class), cubiertas,
                "hay restricciones del apartado 2.6 sin caso de prueba");
    }

    @Test
    @DisplayName("Una ruta correcta no reporta ninguna infraccion")
    void rutaCorrectaNoReportaNada() {
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        Programacion programacion = programador.programar(0, new int[] {HOLGADO, SEGUNDO},
                new int[] {4, 6}, 2, true);
        assertTrue(programacion.factible(), "el decodificador no encontro ruta factible: " + programacion);

        ResultadoVerificacion resultado = verificador.verificarRuta(instancia, programacion.ruta());
        assertTrue(resultado.factible(), "la ruta del decodificador no pasa el verificador: " + resultado);
        assertEquals(List.of(), resultado.detalles());
        assertEquals(Set.of(), resultado.tipos());
    }

    // ------------------------------------------------- variantes de la pausa

    @Test
    @DisplayName("La pausa de alimentacion se revisa en duracion, en ubicacion y en numero")
    void lasTresFormasDeInvalidarLaPausa() {
        int nodo = instancia.unidadNodo(0);
        // Media hora no es la hora continua que exige la restriccion 4.
        assertEquals(Set.of(Infraccion.ALIMENTACION_INVALIDA),
                verificador.verificarRuta(instancia, ruta(instancia,
                        Parada.alimentacion(nodo, 500L, 530L))).tipos(),
                "una pausa de media hora tiene que rechazarse");
        // Arranca a menos de una hora del cambio de turno de las 07:00.
        assertEquals(Set.of(Infraccion.ALIMENTACION_INVALIDA),
                verificador.verificarRuta(instancia, ruta(instancia,
                        Parada.alimentacion(nodo, 430L, 490L))).tipos(),
                "una pausa pegada al cambio de turno tiene que rechazarse");
        // Dos pausas en la misma jornada, ambas correctas por separado.
        assertEquals(Set.of(Infraccion.ALIMENTACION_INVALIDA),
                verificador.verificarRuta(instancia, ruta(instancia,
                        Parada.alimentacion(nodo, 500L, 560L),
                        Parada.alimentacion(nodo, 600L, 660L))).tipos(),
                "la jornada admite una sola pausa");
        // Una sola pausa de una hora dentro de la ventana no es infraccion.
        assertTrue(verificador.verificarRuta(instancia, ruta(instancia,
                Parada.alimentacion(nodo, 500L, 560L))).factible(),
                "una pausa correcta no puede reportarse");
    }

    // ------------------------------------------------------ instantes y bloqueos

    @Test
    @DisplayName("Una llegada declarada antes de lo que permite la velocidad no salva una entrega fuera de plazo")
    void laLlegadaDeclaradaNoAdelantaALaVelocidad() {
        Ruta ruta = ruta(instancia,
                abastecer(instancia, 2, ARRANQUE),
                entregar(instancia, VENCIDO, 2, ARRANQUE + 1L, ARRANQUE + 61L));

        ResultadoVerificacion resultado = verificador.verificarRuta(instancia, ruta);
        assertEquals(Set.of(Infraccion.PLAZO_INCUMPLIDO), resultado.tipos());
        assertTrue(resultado.detalles().get(0).mensaje().contains(String.valueOf(LLEGADA_REAL_AL_VENCIDO)),
                "el verificador debe reportar la llegada recalculada y no la declarada: " + resultado);
    }

    @Test
    @DisplayName("Un bloqueo que se activa despues de la fotografia solo lo ve el verificador de ejecucion")
    void elBloqueoSobrevenidoLoVeSoloElVerificadorDeEjecucion() {
        RegistroBloqueos bloqueos = new RegistroBloqueos(List.of(
                new Bloqueo(caminoAlPedido(instancia, HOLGADO), ARRANQUE + 1L, 10_000L)));
        Ruta ruta = ruta(instancia,
                abastecer(instancia, 4, ARRANQUE),
                entregar(instancia, HOLGADO, 4, 500L, 560L));

        assertTrue(new VerificadorRestricciones(bloqueos).verificarRuta(instancia, ruta).factible(),
                "el planificador no podia saber que la calle se bloquearia despues de la fotografia");
        assertEquals(Set.of(Infraccion.TRAMO_BLOQUEADO),
                VerificadorRestricciones.deEjecucion(bloqueos).verificarRuta(instancia, ruta).tipos(),
                "el verificador de ejecucion tiene que senalar el tramo que se bloquea antes del paso");
    }

    @Test
    @DisplayName("Sin registro de bloqueos la restriccion de tramos bloqueados se omite y las otras se comprueban")
    void sinRegistroDeBloqueosSeOmiteEsaRestriccion() {
        Ruta ruta = ruta(instancia,
                abastecer(instancia, 4, ARRANQUE),
                entregar(instancia, HOLGADO, 4, 500L, 510L));

        ResultadoVerificacion resultado = new VerificadorRestricciones().verificarRuta(instancia, ruta);
        assertEquals(Set.of(Infraccion.SERVICIO_NO_CONTABILIZADO), resultado.tipos());
    }

    // -------------------------------------------------------- plan completo

    @Test
    @DisplayName("Sobre el plan completo las entregas de todas las rutas se suman antes de juzgar el exceso")
    void elExcesoDeEntregasSeMideSobreElPlanCompleto() {
        InstanciaPlanificacion dosUnidades = fotografia(InstanciasDePrueba.INVENTARIO_PLENO, "TA01", "TM01");
        Ruta primera = new Ruta("TA01", TipoUnidad.AUTO, dosUnidades.unidadNodo(0), ARRANQUE, List.of(
                abastecer(dosUnidades, 4, ARRANQUE),
                entregar(dosUnidades, HOLGADO, 4, 600L, 660L)));
        Ruta segunda = new Ruta("TM01", TipoUnidad.MOTO, dosUnidades.unidadNodo(1), ARRANQUE, List.of(
                abastecer(dosUnidades, 4, ARRANQUE),
                entregar(dosUnidades, HOLGADO, 4, 600L, 660L)));

        // Cada ruta por separado entrega justo lo pendiente y no infringe nada.
        assertTrue(verificador.verificarRuta(dosUnidades, primera).factible());
        assertTrue(verificador.verificarRuta(dosUnidades, segunda).factible());

        Solucion plan = new Solucion(List.of(primera, segunda), Map.of(), ValorObjetivo.cero());
        ResultadoVerificacion resultado = verificador.verificar(dosUnidades, plan);
        assertEquals(Set.of(Infraccion.ENTREGA_EXCEDIDA), resultado.tipos(),
                "las dos rutas juntas entregan el doble de lo pendiente");
        assertEquals(1, resultado.detalles().size(), "el exceso se reporta una vez por pedido");
    }

    @Test
    @DisplayName("El inventario de un almacen intermedio es un recurso compartido por todas las rutas")
    void elInventarioIntermedioSeComparteEntreRutas() {
        InstanciaPlanificacion escasa = fotografia(INVENTARIO_ESCASO, "TA01", "TM01");
        Ruta primera = new Ruta("TA01", TipoUnidad.AUTO, escasa.unidadNodo(0), ARRANQUE,
                List.of(abastecerEnIntermedio(escasa, INVENTARIO_ESCASO, 500L)));
        Ruta segunda = new Ruta("TM01", TipoUnidad.MOTO, escasa.unidadNodo(1), ARRANQUE,
                List.of(abastecerEnIntermedio(escasa, INVENTARIO_ESCASO, 500L)));

        assertTrue(verificador.verificarRuta(escasa, primera).factible(),
                "la primera ruta se lleva justo el inventario disponible");
        Solucion plan = new Solucion(List.of(primera, segunda), Map.of(), ValorObjetivo.cero());
        assertEquals(Set.of(Infraccion.INVENTARIO_INSUFICIENTE), verificador.verificar(escasa, plan).tipos(),
                "la segunda ruta ya no encuentra inventario");
    }

    // ------------------------------------------------------------------ casos

    /** Un caso por cada valor de {@link Infraccion}, cada uno con un solo defecto. */
    private List<Caso> casos() {
        InstanciaPlanificacion escasa = fotografia(INVENTARIO_ESCASO, "TA01");
        InstanciaPlanificacion aislada = conDestinoAislado();
        VerificadorRestricciones conBloqueo = new VerificadorRestricciones(new RegistroBloqueos(List.of(
                new Bloqueo(caminoAlPedido(instancia, HOLGADO), 0L, 10_000L))));

        return List.of(
                new Caso("plazo incumplido", Infraccion.PLAZO_INCUMPLIDO, instancia, verificador,
                        ruta(instancia,
                                abastecer(instancia, 2, ARRANQUE),
                                entregar(instancia, VENCIDO, 2, 520L, 580L))),
                new Caso("capacidad excedida", Infraccion.CAPACIDAD_EXCEDIDA, instancia, verificador,
                        ruta(instancia, abastecer(instancia, TipoUnidad.AUTO.capacidad() + 6, ARRANQUE))),
                new Caso("turno excedido", Infraccion.TURNO_EXCEDIDO, instancia, verificador,
                        ruta(instancia,
                                abastecer(instancia, 4, ARRANQUE),
                                entregar(instancia, HOLGADO, 4, 850L, 910L))),
                new Caso("alimentacion fuera de ventana", Infraccion.ALIMENTACION_INVALIDA, instancia, verificador,
                        ruta(instancia, Parada.alimentacion(instancia.unidadNodo(0), 430L, 490L))),
                new Caso("inventario insuficiente", Infraccion.INVENTARIO_INSUFICIENTE, escasa, verificador,
                        ruta(escasa, abastecerEnIntermedio(escasa, INVENTARIO_ESCASO + 3, 500L))),
                new Caso("tramo bloqueado", Infraccion.TRAMO_BLOQUEADO, instancia, conBloqueo,
                        ruta(instancia,
                                abastecer(instancia, 4, ARRANQUE),
                                entregar(instancia, HOLGADO, 4, 500L, 560L))),
                new Caso("destino inalcanzable", Infraccion.TRAMO_BLOQUEADO, aislada, verificador,
                        ruta(aislada,
                                abastecer(aislada, 4, ARRANQUE),
                                entregar(aislada, 0, 4, 500L, 560L))),
                new Caso("servicio no contabilizado", Infraccion.SERVICIO_NO_CONTABILIZADO, instancia, verificador,
                        ruta(instancia,
                                abastecer(instancia, 4, ARRANQUE),
                                entregar(instancia, HOLGADO, 4, 500L, 510L))),
                new Caso("entrega excedida", Infraccion.ENTREGA_EXCEDIDA, instancia, verificador,
                        ruta(instancia,
                                abastecer(instancia, 10, ARRANQUE),
                                entregar(instancia, HOLGADO, 10, 500L, 560L))),
                new Caso("carga inconsistente", Infraccion.CARGA_INCONSISTENTE, instancia, verificador,
                        ruta(instancia, entregar(instancia, HOLGADO, 4, 500L, 560L))));
    }

    // ------------------------------------------------------------------ apoyo

    /** Los tres pedidos de trabajo, siempre en el mismo orden y con los mismos indices. */
    private static List<Pedido> pedidos() {
        return List.of(
                InstanciasDePrueba.pedido(0, 30, 20, 4, 0, 36),
                InstanciasDePrueba.pedido(1, 40, 30, 6, 0, 36),
                InstanciasDePrueba.pedido(2, 60, 40, 2, 0, 4));
    }

    /** Fotografia con los tres pedidos, el inventario indicado y las unidades que se nombren. */
    private static InstanciaPlanificacion fotografia(int inventarioIntermedio, String... codigos) {
        List<UnidadTransporte> unidades = new java.util.ArrayList<>(codigos.length);
        for (String codigo : codigos) {
            unidades.add(InstanciasDePrueba.unidad(codigo, ARRANQUE));
        }
        return InstanciasDePrueba.fotografia(ARRANQUE, pedidos(), unidades, CIERRE,
                InstanciasDePrueba.parametros(), inventarioIntermedio);
    }

    /** Ruta del primer auto de la fotografia con las paradas indicadas. */
    private static Ruta ruta(InstanciaPlanificacion foto, Parada... paradas) {
        return new Ruta(foto.unidadCodigo(0), foto.unidadTipo(0), foto.unidadNodo(0), ARRANQUE, List.of(paradas));
    }

    /** Carga en el almacen central, que es donde arranca la unidad y no supone desplazamiento. */
    private static Parada abastecer(InstanciaPlanificacion foto, int cantidad, long minuto) {
        int central = indiceDeAlmacen(foto, true);
        return Parada.abastecimiento(foto.almacenNodo(central), foto.almacenId(central), cantidad,
                minuto, minuto, 0);
    }

    /** Carga en el primer almacen intermedio, que si tiene inventario limitado. */
    private static Parada abastecerEnIntermedio(InstanciaPlanificacion foto, int cantidad, long minuto) {
        int intermedio = indiceDeAlmacen(foto, false);
        return Parada.abastecimiento(foto.almacenNodo(intermedio), foto.almacenId(intermedio), cantidad,
                minuto, minuto, 0);
    }

    /** Entrega de un pedido de la fotografia. Los kilometros los recalcula el verificador. */
    private static Parada entregar(InstanciaPlanificacion foto, int pedido, int cantidad,
                                   long llegada, long salida) {
        return Parada.entrega(foto.pedidoNodo(pedido), foto.pedidoId(pedido), cantidad, llegada, salida, 0);
    }

    /** Indice del almacen central o del primer intermedio de la fotografia. */
    private static int indiceDeAlmacen(InstanciaPlanificacion foto, boolean central) {
        for (int a = 0; a < foto.cantidadAlmacenes(); a++) {
            if (foto.almacenEsCentral(a) == central) {
                return a;
            }
        }
        throw new IllegalStateException("la fotografia no tiene el almacen que la prueba necesita");
    }

    /** Camino minimo del almacen central al destino del pedido, nodo a nodo. */
    private static int[] caminoAlPedido(InstanciaPlanificacion foto, int pedido) {
        int[] camino = foto.matriz().camino(foto.puntoAlmacen(indiceDeAlmacen(foto, true)),
                foto.puntoPedido(pedido));
        assertTrue(camino.length >= 2, "el camino al destinatario tiene que tener al menos un tramo");
        return camino;
    }

    /**
     * Fotografia en la que las cuatro calles que llegan al destino del pedido estan
     * bloqueadas, de modo que la matriz lo declara inalcanzable. Es el otro camino por el que
     * se levanta la restriccion 6: no ya una calle bloqueada del trayecto, sino la ausencia de
     * trayecto.
     */
    private static InstanciaPlanificacion conDestinoAislado() {
        Pedido pedido = pedidos().get(0);
        UnidadTransporte unidad = InstanciasDePrueba.unidad("TA01", ARRANQUE);
        List<Almacen> almacenes = Almacen.todos();

        int[] nodos = new int[almacenes.size() + 2];
        int k = 0;
        for (Almacen a : almacenes) {
            nodos[k++] = a.nodo();
        }
        nodos[k++] = pedido.nodoDestino();
        nodos[k] = unidad.nodo();

        boolean[] mascara = new boolean[RegistroBloqueos.TOTAL_ARISTAS];
        for (int direccion = 0; direccion < RegistroBloqueos.DIRECCIONES; direccion++) {
            int vecino = RegistroBloqueos.nodoVecino(pedido.nodoDestino(), direccion);
            if (vecino < 0) {
                continue;
            }
            mascara[RegistroBloqueos.arista(pedido.nodoDestino(), direccion)] = true;
            mascara[RegistroBloqueos.arista(vecino, RegistroBloqueos.direccionOpuesta(direccion))] = true;
        }

        MatrizDistanciasReticula matriz = MatrizDistanciasReticula.construir(nodos, mascara);
        InstanciaPlanificacion.Constructor constructor = InstanciaPlanificacion.constructor()
                .minutoActual(ARRANQUE)
                .parametros(InstanciasDePrueba.parametros().instantanea())
                .matriz(matriz);
        for (Almacen a : almacenes) {
            constructor.almacen(a, a.central() ? Integer.MAX_VALUE : a.capacidad());
        }
        constructor.pedido(pedido, pedido.cantidad());
        constructor.unidad(unidad, CIERRE);
        return constructor.construir();
    }
}
