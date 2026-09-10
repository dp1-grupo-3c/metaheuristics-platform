package org.kindbox.core.simulacion;

/**
 * Suceso encolado en el motor de simulacion.
 *
 * <p>El orden natural es por instante simulado y, a igualdad de instante, por el orden de
 * declaracion de {@link TipoEvento}, que fija de forma deliberada que el entorno se
 * actualice antes de mover las unidades y que la replanificacion ocurra con el estado ya
 * actualizado. El tercer criterio es el numero de secuencia, que garantiza un orden total y
 * por tanto que la simulacion sea reproducible.</p>
 *
 * @param minuto     instante simulado, en minutos desde el inicio del escenario
 * @param tipo       naturaleza del suceso
 * @param secuencia  numero de creacion, que desempata de forma estable
 * @param referencia identificador del elemento implicado: indice de pedido, de unidad o de
 *                   almacen segun el tipo, o {@code -1} si el suceso no implica a ninguno
 * @param dato       carga util adicional, cuyo significado depende del tipo
 */
public record Evento(long minuto, TipoEvento tipo, long secuencia, int referencia, int dato)
        implements Comparable<Evento> {

    @Override
    public int compareTo(Evento otro) {
        int porMinuto = Long.compare(minuto, otro.minuto);
        if (porMinuto != 0) {
            return porMinuto;
        }
        int porTipo = Integer.compare(tipo.ordinal(), otro.tipo.ordinal());
        if (porTipo != 0) {
            return porTipo;
        }
        return Long.compare(secuencia, otro.secuencia);
    }
}
