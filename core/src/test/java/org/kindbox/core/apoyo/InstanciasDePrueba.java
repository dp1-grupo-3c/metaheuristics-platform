package org.kindbox.core.apoyo;

import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.grafo.MatrizDistanciasReticula;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Utilidades compartidas por las pruebas unitarias para armar fotografias de planificacion
 * pequenas y revisables a mano.
 *
 * <p>Sigue el mismo camino que {@code FabricaInstancias} del modulo de experimentacion: los
 * tres almacenes del apartado 2 del contexto de dominio, la matriz de distancias reticular
 * real, sin bloqueos, y el constructor incremental de {@link InstanciaPlanificacion}. Las
 * pruebas no simulan la matriz con un doble de prueba a proposito, porque las distancias
 * Manhattan sobre la reticula son parte del comportamiento que se quiere comprobar.</p>
 */
public final class InstanciasDePrueba {

    /** Instante de arranque del turno de manana, 07:00 del primer dia. */
    public static final long INICIO_MANANA = 420L;
    /** Instante de cierre del turno de manana, 15:00 del primer dia. */
    public static final long FIN_MANANA = 900L;

    private InstanciasDePrueba() {
    }

    /** Parametros de operacion con todos los valores por defecto del enunciado. */
    public static ParametrosOperacion parametros() {
        return new ParametrosOperacion();
    }

    /** Pedido con destino en {@code (x,y)} y los datos indicados. */
    public static Pedido pedido(int id, int x, int y, int cantidad, long minutoRegistro, int plazoHoras) {
        return new Pedido(id, "c" + (1000 + id), Ciudad.nodo(x, y), cantidad, minutoRegistro, plazoHoras);
    }

    /** Unidad situada en el almacen central y disponible desde el instante indicado. */
    public static UnidadTransporte unidad(String codigo, long minutoDisponible) {
        UnidadTransporte unidad = UnidadTransporte.de(codigo, Almacen.crearCentral().nodo());
        unidad.minutoDisponibleDesde(minutoDisponible);
        return unidad;
    }

    /** Unidad situada en un nodo concreto y disponible desde el instante indicado. */
    public static UnidadTransporte unidadEn(String codigo, int x, int y, long minutoDisponible) {
        UnidadTransporte unidad = UnidadTransporte.de(codigo, Ciudad.nodo(x, y));
        unidad.minutoDisponibleDesde(minutoDisponible);
        return unidad;
    }

    /**
     * Fotografia con los tres almacenes, la matriz reticular sin bloqueos y el horizonte de
     * cada unidad fijado de forma explicita.
     *
     * @param minutoActual  instante de la fotografia
     * @param pedidos       pedidos pendientes, con su cantidad completa por entregar
     * @param unidades      unidades disponibles
     * @param finHorizonte  cierre del horizonte de todas las unidades, o {@code -1} para el
     *                      cierre del turno en curso
     * @param parametros    parametros de operacion vigentes
     */
    public static InstanciaPlanificacion fotografia(long minutoActual, List<Pedido> pedidos,
                                                    List<UnidadTransporte> unidades, long finHorizonte,
                                                    ParametrosOperacion parametros) {
        List<Almacen> almacenes = Almacen.todos();
        int[] nodos = new int[almacenes.size() + pedidos.size() + unidades.size()];
        int k = 0;
        for (Almacen a : almacenes) {
            nodos[k++] = a.nodo();
        }
        for (Pedido p : pedidos) {
            nodos[k++] = p.nodoDestino();
        }
        for (UnidadTransporte u : unidades) {
            nodos[k++] = u.nodo();
        }
        MatrizDistanciasReticula matriz = MatrizDistanciasReticula.construir(nodos, null);

        InstanciaPlanificacion.Constructor constructor = InstanciaPlanificacion.constructor()
                .minutoActual(minutoActual)
                .parametros(parametros.instantanea())
                .matriz(matriz);
        for (Almacen a : almacenes) {
            constructor.almacen(a, a.central() ? Integer.MAX_VALUE : a.capacidad());
        }
        for (Pedido p : pedidos) {
            constructor.pedido(p, p.cantidad());
        }
        for (UnidadTransporte u : unidades) {
            constructor.unidad(u, finHorizonte);
        }
        return constructor.construir();
    }

    /**
     * Fotografia con un juego de pedidos repartidos por la ciudad, plazos mezclados y una
     * sola unidad. Es la instancia que consume la prueba de equivalencia entre la
     * evaluacion incremental y la completa del apartado 14 del ISA.
     *
     * @param cantidadPedidos numero de pedidos a generar
     */
    public static InstanciaPlanificacion instanciaVariada(int cantidadPedidos) {
        return fotografia(INICIO_MANANA, pedidosVariados(cantidadPedidos), List.of(unidad("TA01", INICIO_MANANA)),
                FIN_MANANA, parametros());
    }

    /**
     * Fotografia con el mismo juego de pedidos de {@link #instanciaVariada(int)} y una flota
     * mixta de autos, motos y una bicicleta, repartida entre el almacen central y dos puntos
     * de la ciudad. Con varias unidades de tipos distintos entran en juego el vector de tipos
     * y la reasignacion de unidades de la busqueda genetica hibrida y los operadores de ruta
     * completa de ALNS, que la instancia de una sola unidad no ejercita. La consumen las
     * pruebas de reproducibilidad del presupuesto por iteraciones.
     *
     * @param cantidadPedidos numero de pedidos a generar
     */
    public static InstanciaPlanificacion instanciaFlotaMixta(int cantidadPedidos) {
        List<UnidadTransporte> unidades = List.of(
                unidad("TA01", INICIO_MANANA),
                unidad("TM01", INICIO_MANANA),
                unidadEn("TM02", 40, 30, INICIO_MANANA),
                unidadEn("TB01", 20, 20, INICIO_MANANA + 30L));
        return fotografia(INICIO_MANANA, pedidosVariados(cantidadPedidos), unidades, FIN_MANANA, parametros());
    }

    /** Pedidos repartidos por la ciudad con plazos mezclados, siempre los mismos para el mismo numero. */
    private static List<Pedido> pedidosVariados(int cantidadPedidos) {
        // Coordenadas y plazos elegidos a mano para que la instancia sea reproducible y para
        // que convivan pedidos holgados con pedidos que ninguna secuencia alcanza a cumplir,
        // de modo que la evaluacion recorra tanto el camino con espera como el de desfase.
        int[] equis = {4, 62, 27, 14, 55, 33, 8, 48, 20, 66, 2, 39, 58, 11, 30, 45};
        int[] yes = {45, 6, 33, 12, 41, 22, 3, 48, 18, 27, 9, 36, 15, 50, 44, 30};
        int[] plazos = {4, 36, 8, 12, 18, 4, 36, 8, 12, 18, 4, 36, 8, 12, 18, 4};
        List<Pedido> pedidos = new ArrayList<>(cantidadPedidos);
        for (int i = 0; i < cantidadPedidos; i++) {
            int j = i % equis.length;
            int cantidad = 1 + (i % 5);
            long registro = 60L * (i % 7);
            pedidos.add(pedido(i, equis[j], yes[j], cantidad, registro, plazos[j]));
        }
        return pedidos;
    }
}
