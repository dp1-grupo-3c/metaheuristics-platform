package org.kindbox.core.io;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Traductor entre el calendario real y el reloj interno del planificador.
 *
 * <p>Todo el nucleo trabaja con un unico entero largo: los minutos transcurridos desde el
 * arranque del escenario. Esa eleccion viene del apartado 13 del ISA, porque los plazos de
 * los pedidos, las ventanas de bloqueo, los turnos de la respuesta 12 del cuestionario y
 * los horizontes de las averias se comparan millones de veces por ejecucion y una
 * comparacion de enteros es mucho mas barata que una de fechas. Los archivos de entrada,
 * en cambio, hablan en dias del mes y horas del reloj: {@code ##d##h##m} en el archivo de
 * ventas y {@code aaaammdd} en el de mantenimiento preventivo. Esta clase es el unico
 * punto donde ambos mundos se tocan.</p>
 *
 * <p>El instante de inicio se trunca al minuto, porque el reloj de la simulacion avanza en
 * minutos enteros y un arranque con segundos produciria conversiones de ida y vuelta que
 * no coinciden.</p>
 *
 * <p>La clase es inmutable y por lo tanto segura de compartir entre el motor de simulacion
 * y los hilos de planificacion.</p>
 */
public final class CalendarioEscenario {

    /** Minutos que tiene un dia completo. */
    public static final int MINUTOS_POR_DIA = 1440;

    private static final DateTimeFormatter FORMATO_LARGO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalDateTime inicio;

    /** Crea el calendario a partir del instante en que arranca el escenario. */
    public CalendarioEscenario(LocalDateTime inicio) {
        if (inicio == null) {
            throw new IllegalArgumentException("El escenario necesita un instante de inicio");
        }
        this.inicio = inicio.truncatedTo(ChronoUnit.MINUTES);
    }

    /** Calendario que arranca a las 00:00 del dia indicado, que es el caso habitual. */
    public static CalendarioEscenario desde(LocalDate dia) {
        return new CalendarioEscenario(dia.atStartOfDay());
    }

    /** Calendario que arranca a las 00:00 del primer dia del mes indicado. */
    public static CalendarioEscenario desde(YearMonth mes) {
        return new CalendarioEscenario(mes.atDay(1).atStartOfDay());
    }

    /** Instante real en que arranca el escenario, es decir el minuto interno cero. */
    public LocalDateTime inicio() {
        return inicio;
    }

    // ------------------------------------------------- del calendario al reloj

    /** Minuto interno que corresponde a un instante real. Es negativo si precede al arranque. */
    public long aMinutos(LocalDateTime instante) {
        return ChronoUnit.MINUTES.between(inicio, instante.truncatedTo(ChronoUnit.MINUTES));
    }

    /** Minuto interno de las 00:00 del dia indicado. */
    public long aMinutos(LocalDate dia) {
        return aMinutos(dia.atStartOfDay());
    }

    /** Minuto interno de una hora concreta de un dia concreto. */
    public long aMinutos(LocalDate dia, LocalTime hora) {
        return aMinutos(LocalDateTime.of(dia, hora));
    }

    /**
     * Resuelve el campo {@code ##d##h##m} de un registro del archivo mensual de ventas.
     * El dia es dia del mes, de modo que el mes al que pertenece el archivo es
     * imprescindible para situar el registro en el reloj interno.
     *
     * @param dia    dia del mes, entre 1 y la ultima fecha del mes
     * @param hora   hora del dia, entre 0 y 23
     * @param minuto minuto de la hora, entre 0 y 59
     * @param mes    mes al que corresponde el archivo
     * @return minuto interno del registro
     */
    public long minutosDeRegistroVentas(int dia, int hora, int minuto, YearMonth mes) {
        if (mes == null) {
            throw new IllegalArgumentException("Falta el mes al que pertenece el registro");
        }
        if (dia < 1 || dia > mes.lengthOfMonth()) {
            throw new IllegalArgumentException("el dia " + dia + " no existe en " + mes
                    + ", que tiene " + mes.lengthOfMonth() + " dias");
        }
        if (hora < 0 || hora > 23) {
            throw new IllegalArgumentException("la hora " + hora + " esta fuera del rango 0..23");
        }
        if (minuto < 0 || minuto > 59) {
            throw new IllegalArgumentException("el minuto " + minuto + " esta fuera del rango 0..59");
        }
        return aMinutos(LocalDateTime.of(mes.atDay(dia), LocalTime.of(hora, minuto)));
    }

    // ------------------------------------------------- del reloj al calendario

    /** Instante real que corresponde a un minuto interno. */
    public LocalDateTime aFecha(long minutos) {
        return inicio.plusMinutes(minutos);
    }

    /** Dia calendario que corresponde a un minuto interno. */
    public LocalDate aDia(long minutos) {
        return aFecha(minutos).toLocalDate();
    }

    // -------------------------------------------------------- utilidades del dia

    /**
     * Minuto interno de las 00:00 del dia que contiene al minuto dado. Es negativo cuando
     * el escenario no arranca a medianoche y el minuto pertenece al primer dia.
     */
    public long inicioDelDia(long minutos) {
        return aMinutos(aDia(minutos));
    }

    /**
     * Minutos transcurridos desde las 00:00 del dia que contiene al minuto dado, entre 0 y
     * 1439. Es la escala en que estan expresados los cambios de turno de las 07:00, 15:00
     * y 23:00 y la recarga diaria de los almacenes intermedios.
     */
    public int minutoDelDia(long minutos) {
        LocalDateTime fecha = aFecha(minutos);
        return fecha.getHour() * 60 + fecha.getMinute();
    }

    /** Numero de dia del escenario, empezando en 1 el dia calendario del arranque. */
    public int diaDeEscenario(long minutos) {
        return (int) ChronoUnit.DAYS.between(inicio.toLocalDate(), aDia(minutos)) + 1;
    }

    /** Fecha completa del minuto interno, con el formato "dd/MM/yyyy HH:mm". */
    public String fechaTexto(long minutos) {
        return FORMATO_LARGO.format(aFecha(minutos));
    }

    /**
     * Forma corta "NdHH:mm" para las trazas del planificador y del motor de simulacion,
     * donde N es el numero de dia del escenario. Por ejemplo el minuto 3 245 de un
     * escenario que arranca a medianoche se escribe "3d06:05".
     */
    public String textoCorto(long minutos) {
        return diaDeEscenario(minutos) + "d" + FORMATO_HORA.format(aFecha(minutos));
    }

    @Override
    public String toString() {
        return "CalendarioEscenario[inicio=" + FORMATO_LARGO.format(inicio) + "]";
    }
}
