package org.kindbox.core.modelo;

// Duraciones configurables de una averia; cada tipo usa solo las que aplican a su regla.
public record AtributosAveria(int minutosEnElLugar, int turnosHastaReincorporacion, int diasMantenimiento) {

    public static final int MINUTOS_EN_EL_LUGAR_MAXIMO = 1440;
    public static final int TURNOS_MAXIMO = 9;
    public static final int DIAS_MAXIMO = 14;

    public AtributosAveria {
        if (minutosEnElLugar < 1 || minutosEnElLugar > MINUTOS_EN_EL_LUGAR_MAXIMO) {
            throw new IllegalArgumentException("minutosEnElLugar debe estar entre 1 y "
                    + MINUTOS_EN_EL_LUGAR_MAXIMO + " y es " + minutosEnElLugar);
        }
        if (turnosHastaReincorporacion < 1 || turnosHastaReincorporacion > TURNOS_MAXIMO) {
            throw new IllegalArgumentException("turnosHastaReincorporacion debe estar entre 1 y "
                    + TURNOS_MAXIMO + " y es " + turnosHastaReincorporacion);
        }
        if (diasMantenimiento < 1 || diasMantenimiento > DIAS_MAXIMO) {
            throw new IllegalArgumentException("diasMantenimiento debe estar entre 1 y "
                    + DIAS_MAXIMO + " y es " + diasMantenimiento);
        }
    }
}
