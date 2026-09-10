package org.kindbox.core.modelo;

/**
 * Tipos de averia de la respuesta 3 del cuestionario.
 *
 * <p>Una averia es la no disponibilidad de una unidad de transporte por un periodo
 * determinado. En los tipos 2 y 3 la unidad permanece 4 horas en el lugar del
 * incidente y, cumplido ese plazo, tanto la unidad como los paquetes que no fueron
 * trasvasados se trasladan de manera instantanea al almacen central.</p>
 */
public enum TipoAveria {

    /** Menor. La unidad queda inmovilizada 2 horas y se reincorpora en el mismo lugar. */
    TIPO_1(1, 120, false),
    /** Intermedia. La unidad no vuelve hasta el cierre del turno siguiente al del incidente. */
    TIPO_2(2, 240, true),
    /** Mayor. Al menos 2 dias de mantenimiento; reingresa en el turno de 15:00 a 23:00. */
    TIPO_3(3, 240, true);

    /** Horas que la unidad permanece en el lugar antes del traslado al almacen central. */
    public static final int MINUTOS_EN_EL_LUGAR = 240;

    private final int codigo;
    private final int minutosEnElLugar;
    private final boolean trasladaAlmacenCentral;

    TipoAveria(int codigo, int minutosEnElLugar, boolean trasladaAlmacenCentral) {
        this.codigo = codigo;
        this.minutosEnElLugar = minutosEnElLugar;
        this.trasladaAlmacenCentral = trasladaAlmacenCentral;
    }

    /** Codigo numerico 1, 2 o 3. */
    public int codigo() {
        return codigo;
    }

    /** Minutos que la unidad permanece fisicamente en el punto de la averia. */
    public int minutosEnElLugar() {
        return minutosEnElLugar;
    }

    /** Indica si la unidad y su carga remanente se llevan al almacen central. */
    public boolean trasladaAlmacenCentral() {
        return trasladaAlmacenCentral;
    }

    /**
     * Instante absoluto, en minutos desde el inicio del escenario, en que la unidad
     * vuelve a estar disponible para la planificacion.
     *
     * @param minutoAveria instante en que se produjo el incidente
     */
    public long minutoReincorporacion(long minutoAveria) {
        return switch (this) {
            // Dos horas de indisponibilidad, sin abandonar el lugar.
            case TIPO_1 -> minutoAveria + 120L;
            // Hasta el final del turno siguiente al que se averio.
            case TIPO_2 -> Turno.finDelTurnoSiguiente(minutoAveria);
            // Al menos dos dias de mantenimiento y retorno en el turno de 15:00 a 23:00.
            case TIPO_3 -> Turno.proximoInicioDeTarde(minutoAveria + 2L * 1440L);
        };
    }

    /** Resuelve el tipo a partir de su codigo numerico. */
    public static TipoAveria porCodigo(int codigo) {
        for (TipoAveria t : values()) {
            if (t.codigo == codigo) {
                return t;
            }
        }
        throw new IllegalArgumentException("Tipo de averia desconocido: " + codigo);
    }
}
