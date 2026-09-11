package org.kindbox.experiments;

import java.util.Locale;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.ModoReloj;

/**
 * Duracion y modo de reloj de una corrida lanzada desde la linea de comandos, y el
 * presupuesto de computo por llamada al planificador que de ellos se deriva.
 *
 * <p>El apartado 2.3 del ISA deriva el presupuesto de cada llamada del factor de aceleracion
 * K: la simulacion 5D recorre 7 200 minutos simulados en entre 30 y 60 minutos reales, K
 * queda entre 120 y 240 y el presupuesto efectivo, el 60 por ciento de SA / K, entre 2 y 18
 * segundos. Los ejecutables tomaban antes por defecto una duracion de un minuto, es decir K
 * igual a 7 200 y apenas 150 ms por llamada con el salto de 30 minutos: toda medicion hecha
 * asi evaluaba al planificador con la trigesima parte del presupuesto que el ISA le concede.
 * Ahora el defecto es la corrida de 30 minutos, K igual a 240, y la corrida rapida hay que
 * pedirla de forma expresa.</p>
 *
 * <p>Formas del argumento, sin distinguir mayusculas:</p>
 * <ul>
 *   <li>ausente o {@code LIBRE}: modo libre con K de la corrida de 30 minutos, 240;</li>
 *   <li>{@code LIBRE:<minutos>}: modo libre con K igual a 7 200 entre esos minutos;</li>
 *   <li>{@code RAPIDO}: modo libre con K igual a 7 200, el de la corrida de un minuto, solo
 *       para pruebas de humo;</li>
 *   <li>un numero de minutos: lo que ese numero significaba ya en cada ejecutable, que se
 *       indica al interpretar el argumento.</li>
 * </ul>
 *
 * <p>En modo libre el factor K ya no gobierna la espera, que no la hay, sino solo el
 * presupuesto por llamada, y por eso se aplica igual a los tres escenarios.</p>
 *
 * @param duracionMinutos minutos reales de la corrida 5D equivalente, de los que sale K
 * @param modo            relacion entre el reloj simulado y el de pared
 * @param rapido          si se pidio de forma expresa la corrida rapida
 */
record OpcionReloj(int duracionMinutos, ModoReloj modo, boolean rapido) {

    /** Duracion por defecto: el extremo rapido del apartado 2.3, K igual a 240. */
    static final int DURACION_POR_DEFECTO = 30;
    /** Duracion del modo rapido, K igual a 7 200. */
    static final int DURACION_RAPIDA = 1;
    /** Extremos del presupuesto efectivo por llamada del apartado 2.3, en milisegundos. */
    static final long PRESUPUESTO_MINIMO_MS = 2_000L;
    static final long PRESUPUESTO_MAXIMO_MS = 18_000L;

    OpcionReloj {
        if (duracionMinutos <= 0) {
            throw new IllegalArgumentException("Duracion no positiva: " + duracionMinutos);
        }
    }

    /**
     * Interpreta el argumento de duracion.
     *
     * @param argumento      texto de la linea de comandos, o {@code null} si no se paso
     * @param modoDeUnNumero modo que corresponde a un numero de minutos a secas
     */
    static OpcionReloj interpretar(String argumento, ModoReloj modoDeUnNumero) {
        if (argumento == null || argumento.isBlank()) {
            return new OpcionReloj(DURACION_POR_DEFECTO, ModoReloj.LIBRE, false);
        }
        String texto = argumento.trim().toUpperCase(Locale.ROOT);
        if (texto.equals("RAPIDO")) {
            return new OpcionReloj(DURACION_RAPIDA, ModoReloj.LIBRE, true);
        }
        if (texto.equals("LIBRE")) {
            return new OpcionReloj(DURACION_POR_DEFECTO, ModoReloj.LIBRE, false);
        }
        if (texto.startsWith("LIBRE:")) {
            return new OpcionReloj(minutos(texto.substring("LIBRE:".length()), argumento), ModoReloj.LIBRE, false);
        }
        return new OpcionReloj(minutos(texto, argumento), modoDeUnNumero, false);
    }

    private static int minutos(String texto, String argumento) {
        int valor;
        try {
            valor = Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            valor = 0;
        }
        if (valor <= 0) {
            throw new IllegalArgumentException("Argumento de duracion no valido: '" + argumento
                    + "'. Se admite RAPIDO, LIBRE, LIBRE:<minutos> o un numero de minutos positivo");
        }
        return valor;
    }

    /** Factor K que corresponde a la duracion, el mismo que calcula la simulacion 5D. */
    double factorAceleracion() {
        return (double) ConfiguracionEscenario.HORIZONTE_5D_MINUTOS / duracionMinutos;
    }

    /**
     * Aplica la opcion a una configuracion. En modo libre fija el modo y el factor K; en modo
     * acompasado deja la configuracion como la construyo su escenario.
     */
    ConfiguracionEscenario aplicar(ConfiguracionEscenario base) {
        if (modo != ModoReloj.LIBRE) {
            return base;
        }
        return new ConfiguracionEscenario(base.tipo(), base.primerDia(), base.ultimoDia(), base.saltoMinutos(),
                factorAceleracion(), ModoReloj.LIBRE, base.algoritmo(), base.semilla(),
                base.minutosEntreFotografias(), base.generarAverias(), base.averiasPorUnidadPorTurno(),
                base.arranqueDesdePlanVigente());
    }

    /** Presupuesto por llamada al planificador que usara el motor con esta configuracion. */
    static long milisegundosPorLlamada(ConfiguracionEscenario configuracion) {
        return PresupuestoComputo.deSimulacion(configuracion.saltoMinutos(), configuracion.factorAceleracion())
                .milisegundosTotales();
    }

    /**
     * Linea que se imprime al arrancar, con K y el presupuesto por llamada, seguida de un
     * aviso si el presupuesto cae fuera del rango del apartado 2.3.
     */
    String describir(ConfiguracionEscenario configuracion) {
        long milisegundos = milisegundosPorLlamada(configuracion);
        StringBuilder texto = new StringBuilder(String.format(Locale.ROOT,
                "Reloj: modo %s%s, K=%.1f, presupuesto por llamada al planificador %d ms"
                        + " (SA=%d min, %.0f %% de SA/K)",
                configuracion.modoReloj(), rapido ? " RAPIDO" : "", configuracion.factorAceleracion(),
                milisegundos, configuracion.saltoMinutos(), 100.0 * PresupuestoComputo.FRACCION_EFECTIVA));
        if (milisegundos < PRESUPUESTO_MINIMO_MS || milisegundos > PRESUPUESTO_MAXIMO_MS) {
            texto.append(String.format(Locale.ROOT,
                    "%nAVISO: el presupuesto por llamada queda fuera del rango de %d a %d ms del apartado 2.3"
                            + " del ISA; la calidad medida no es la del planificador en operacion.",
                    PRESUPUESTO_MINIMO_MS, PRESUPUESTO_MAXIMO_MS));
        }
        return texto.toString();
    }
}
