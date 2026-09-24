package org.kindbox.core.simulacion;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Properties;

/**
 * Parametros de una corrida de simulacion.
 *
 * <p>El requisito no funcional (a) del enunciado exige que el planificador resuelva los
 * tres escenarios <b>mediante parametros</b>. Este record es ese juego de parametros: el
 * motor es uno solo y lo que cambia entre escenarios es su contenido.</p>
 *
 * <p>El apartado 2.3 del ISA deriva de aqui el presupuesto de computo. El horizonte de la
 * simulacion 5D son 7 200 minutos simulados que deben recorrerse en entre 30 y 60 minutos
 * reales, de donde el factor de aceleracion K queda entre 120 y 240 y el presupuesto
 * teorico por ejecucion del planificador es el salto SA dividido entre K.</p>
 *
 * @param tipo                 escenario a ejecutar
 * @param primerDia            primer dia del horizonte
 * @param ultimoDia            ultimo dia del horizonte, incluido; se ignora en el colapso
 * @param saltoMinutos         SA, minutos simulados entre dos ejecuciones del planificador
 * @param factorAceleracion    K, minutos simulados por minuto real
 * @param modoReloj            si el motor se acompasa al reloj de pared
 * @param algoritmo            nombre del algoritmo del planificador
 * @param semilla              semilla del generador de la corrida, que la hace reproducible
 * @param minutosEntreFotografias cadencia de las fotografias que recibe el visualizador
 * @param generarAverias       si el motor genera averias por reglas ademas de las registradas
 * @param averiasPorUnidadPorTurno probabilidad de que una unidad se averie durante un turno
 * @param arranqueDesdePlanVigente si cada replanificacion arranca desde el plan vigente en
 *                             lugar de desde la heuristica constructiva. Es el segundo modo
 *                             de arranque del apartado 7.3.5 del ISA y la hipotesis
 *                             experimental del apartado 11.4; solo lo aprovecha el algoritmo
 *                             que lo admite, hoy la busqueda adaptativa de vecindad amplia.
 *                             Por defecto esta desactivado, porque el arranque de referencia
 *                             es el constructivo
 */
public record ConfiguracionEscenario(
        TipoEscenario tipo,
        LocalDate primerDia,
        LocalDate ultimoDia,
        int saltoMinutos,
        double factorAceleracion,
        ModoReloj modoReloj,
        String algoritmo,
        long semilla,
        int minutosEntreFotografias,
        boolean generarAverias,
        double averiasPorUnidadPorTurno,
        boolean arranqueDesdePlanVigente) {

    /** Horizonte de la simulacion de cinco dias, en minutos. */
    public static final int HORIZONTE_5D_MINUTOS = 5 * 24 * 60;

    /**
     * Propiedad de sistema con que los ejecutables y el servicio activan el arranque desde el
     * plan vigente, sin recompilar y sin tocar la configuracion del escenario.
     */
    public static final String PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE = "arranqueDesdePlanVigente";

    public ConfiguracionEscenario {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de escenario es obligatorio");
        }
        if (primerDia == null || ultimoDia == null) {
            throw new IllegalArgumentException("El escenario necesita fechas de inicio y fin");
        }
        if (ultimoDia.isBefore(primerDia)) {
            throw new IllegalArgumentException("El rango del escenario esta invertido");
        }
        if (modoReloj == null) {
            throw new IllegalArgumentException("El modo de reloj es obligatorio");
        }
        if (algoritmo == null || algoritmo.isBlank()) {
            throw new IllegalArgumentException("El algoritmo es obligatorio");
        }
        if (saltoMinutos <= 0) {
            throw new IllegalArgumentException("El salto de planificacion debe ser positivo");
        }
        if (factorAceleracion <= 0 || !Double.isFinite(factorAceleracion)) {
            throw new IllegalArgumentException("Factor de aceleracion invalido: " + factorAceleracion);
        }
        if (minutosEntreFotografias <= 0) {
            throw new IllegalArgumentException("La cadencia de fotografias debe ser positiva");
        }
        if (averiasPorUnidadPorTurno < 0.0 || averiasPorUnidadPorTurno > 1.0) {
            throw new IllegalArgumentException("Probabilidad de averia fuera de [0,1]");
        }
    }

    /**
     * Configuracion de la simulacion de cinco dias.
     *
     * @param duracionMinutosReales duracion objetivo de la corrida, entre 30 y 60 minutos
     *                              segun el enunciado; de ella se deriva el factor K
     */
    public static ConfiguracionEscenario simulacion5D(LocalDate primerDia, int duracionMinutosReales,
                                                      int saltoMinutos, String algoritmo, long semilla) {
        double k = (double) HORIZONTE_5D_MINUTOS / duracionMinutosReales;
        return new ConfiguracionEscenario(TipoEscenario.SIMULACION_5D, primerDia, primerDia.plusDays(4),
                saltoMinutos, k, ModoReloj.ACOMPASADO, algoritmo, semilla, 1, true, 0.02, false);
    }

    /** Configuracion del escenario de colapso, que corre sin horizonte y sin acompasar. */
    public static ConfiguracionEscenario colapso(LocalDate primerDia, int saltoMinutos,
                                                 String algoritmo, long semilla) {
        return new ConfiguracionEscenario(TipoEscenario.COLAPSO, primerDia, primerDia.plusYears(1),
                saltoMinutos, 240.0, ModoReloj.LIBRE, algoritmo, semilla, 15, true, 0.02, false);
    }

    /** Configuracion de la operacion dia a dia, en la que el reloj simulado es el real. */
    public static ConfiguracionEscenario diaADia(LocalDate dia, int saltoMinutos,
                                                 String algoritmo, long semilla) {
        return new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, dia, dia,
                saltoMinutos, 1.0, ModoReloj.ACOMPASADO, algoritmo, semilla, 1, true, 0.02, false);
    }

    /** La misma configuracion con el arranque desde el plan vigente activado o desactivado. */
    public ConfiguracionEscenario conArranqueDesdePlanVigente(boolean activo) {
        return activo == arranqueDesdePlanVigente ? this
                : new ConfiguracionEscenario(tipo, primerDia, ultimoDia, saltoMinutos, factorAceleracion,
                        modoReloj, algoritmo, semilla, minutosEntreFotografias, generarAverias,
                        averiasPorUnidadPorTurno, activo);
    }

    /**
     * Lee de las propiedades el indicador {@value #PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE}.
     * Ausente o vacio es {@code true}: cada replanificacion parte del plan vigente, porque
     * con el arranque constructivo el presupuesto se gasta en reconstruir asignaciones que ya
     * estaban resueltas y el plan pierde pedidos que ya tenia colocados a tiempo. El arranque
     * constructivo de referencia se pide con {@code false}.
     *
     * @throws IllegalArgumentException si el valor no es {@code true} ni {@code false}. Caer
     *                                  en silencio al valor por defecto haria pasar una
     *                                  corrida por otra en la comparacion del apartado 12
     */
    public static boolean leerArranqueDesdePlanVigente(Properties propiedades) {
        String texto = propiedades == null ? null : propiedades.getProperty(PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE);
        if (texto == null || texto.isBlank()) {
            return true;
        }
        String limpio = texto.trim().toLowerCase(Locale.ROOT);
        if (limpio.equals("true")) {
            return true;
        }
        if (limpio.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("Propiedad de sistema " + PROPIEDAD_ARRANQUE_DESDE_PLAN_VIGENTE
                + " no valida: '" + texto + "'. Debe ser true o false");
    }

    /** Milisegundos de reloj de pared que representa un minuto simulado. */
    public double milisegundosPorMinutoSimulado() {
        return 60_000.0 / factorAceleracion;
    }
}
