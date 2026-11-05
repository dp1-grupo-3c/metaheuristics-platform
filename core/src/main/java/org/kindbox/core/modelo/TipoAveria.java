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
    TIPO_1(1, false, new AtributosAveria(120, 1, 2)),
    /** Intermedia. La unidad no vuelve hasta el cierre del turno siguiente al del incidente. */
    TIPO_2(2, true, new AtributosAveria(240, 1, 2)),
    /** Mayor. Al menos 2 dias de mantenimiento; reingresa en el turno de 15:00 a 23:00. */
    TIPO_3(3, true, new AtributosAveria(240, 1, 2));

    /** Horas que la unidad permanece en el lugar antes del traslado al almacen central. */
    public static final int MINUTOS_EN_EL_LUGAR = 240;

    // Con traslado, la permanencia no puede pasar de un turno para no caer despues del reingreso.
    public static final int MINUTOS_EN_EL_LUGAR_MAXIMO_CON_TRASLADO = Turno.DURACION_MIN;

    private final int codigo;
    private final boolean trasladaAlmacenCentral;
    private final AtributosAveria porDefecto;
    private volatile AtributosAveria vigentes;

    TipoAveria(int codigo, boolean trasladaAlmacenCentral, AtributosAveria porDefecto) {
        this.codigo = codigo;
        this.trasladaAlmacenCentral = trasladaAlmacenCentral;
        this.porDefecto = porDefecto;
        this.vigentes = porDefecto;
    }

    /** Codigo numerico 1, 2 o 3. */
    public int codigo() {
        return codigo;
    }

    public AtributosAveria atributos() {
        return vigentes;
    }

    // Rige para las averias que ocurran despues del cambio; las ya programadas no se mueven.
    public void atributos(AtributosAveria nuevos) {
        if (nuevos == null) {
            throw new IllegalArgumentException("Faltan los atributos de la averia de tipo " + codigo);
        }
        if (trasladaAlmacenCentral && nuevos.minutosEnElLugar() > MINUTOS_EN_EL_LUGAR_MAXIMO_CON_TRASLADO) {
            throw new IllegalArgumentException("En la averia de tipo " + codigo
                    + " minutosEnElLugar no puede pasar de " + MINUTOS_EN_EL_LUGAR_MAXIMO_CON_TRASLADO
                    + " y es " + nuevos.minutosEnElLugar());
        }
        vigentes = nuevos;
    }

    public AtributosAveria atributosPorDefecto() {
        return porDefecto;
    }

    public static void restablecerAtributos() {
        for (TipoAveria tipo : values()) {
            tipo.vigentes = tipo.porDefecto;
        }
    }

    /** Minutos que la unidad permanece fisicamente en el punto de la averia. */
    public int minutosEnElLugar() {
        return vigentes.minutosEnElLugar();
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
        AtributosAveria a = vigentes;
        return switch (this) {
            // Indisponible en el mismo lugar durante la permanencia configurada.
            case TIPO_1 -> minutoAveria + a.minutosEnElLugar();
            // Cierre del turno del incidente mas los turnos configurados.
            case TIPO_2 -> Turno.finDelTurno(minutoAveria) + (long) a.turnosHastaReincorporacion() * Turno.DURACION_MIN;
            // Dias de mantenimiento y retorno en el turno de 15:00 a 23:00.
            case TIPO_3 -> Turno.proximoInicioDeTarde(minutoAveria + a.diasMantenimiento() * 1440L);
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
