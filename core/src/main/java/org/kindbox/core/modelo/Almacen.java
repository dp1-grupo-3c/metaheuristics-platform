package org.kindbox.core.modelo;

/**
 * Almacen de la empresa. Segun el enunciado y la respuesta 4 del cuestionario existen
 * un almacen central con inventario ilimitado en {@code (27,14)} y dos almacenes
 * intermedios de 1 000 unidades: el nor-oeste en {@code (12,38)} y el este en {@code (57,27)}.
 *
 * <p>Los intermedios se recargan a capacidad plena cada 24 horas a las 23:59:59, con
 * recarga instantanea. Una unidad no puede abastecerse en un almacen cuyo inventario
 * disponible sea insuficiente (restriccion dura del apartado 2.6 del ISA).</p>
 *
 * @param id         identificador interno
 * @param nombre     nombre para presentacion
 * @param nodo       nodo de la reticula donde se ubica
 * @param central    indica si es el almacen central de inventario ilimitado
 * @param capacidad  capacidad maxima en unidades del producto P
 */
public record Almacen(int id, String nombre, int nodo, boolean central, int capacidad) {

    /** Capacidad maxima de cada almacen intermedio. */
    public static final int CAPACIDAD_INTERMEDIO = 1000;
    /** Minuto del dia en que se produce la recarga diaria (23:59:59, redondeado al minuto 1439). */
    public static final int MINUTO_RECARGA_DIARIA = 1439;

    // Red configurada; null mientras rija la del enunciado. Cada corrida la toma al arrancar.
    private static volatile java.util.List<Almacen> configurados;

    /** Almacen central vigente, de inventario ilimitado. */
    public static Almacen crearCentral() {
        return todos().stream().filter(Almacen::central).findFirst().orElseThrow();
    }

    /** Almacen central del enunciado. */
    private static Almacen centralDelEnunciado() {
        return new Almacen(0, "Almacen Central", Ciudad.nodo(27, 14), true, Integer.MAX_VALUE);
    }

    /** Almacen intermedio nor-oeste. */
    public static Almacen intermedioNorOeste() {
        return new Almacen(1, "Almacen Intermedio Nor-Oeste", Ciudad.nodo(12, 38), false, CAPACIDAD_INTERMEDIO);
    }

    /** Almacen intermedio este. */
    public static Almacen intermedioEste() {
        return new Almacen(2, "Almacen Intermedio Este", Ciudad.nodo(57, 27), false, CAPACIDAD_INTERMEDIO);
    }

    /** Los almacenes vigentes de la empresa, en orden de identificador. */
    public static java.util.List<Almacen> todos() {
        java.util.List<Almacen> vigentes = configurados;
        return vigentes != null ? vigentes
                : java.util.List.of(centralDelEnunciado(), intermedioNorOeste(), intermedioEste());
    }

    /** Reemplaza la red de almacenes para las corridas que arranquen despues. */
    public static void configurar(java.util.List<Almacen> almacenes) {
        configurados = java.util.List.copyOf(almacenes);
    }
}
