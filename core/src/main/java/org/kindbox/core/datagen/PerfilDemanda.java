package org.kindbox.core.datagen;

import java.util.Locale;
import org.kindbox.core.util.Aleatorio;

/**
 * Perfil de demanda de un juego de datos sinteticos: cuantos pedidos llegan por dia,
 * como crece esa cifra con el paso de los meses y que mezcla de plazos usan.
 *
 * <p>El equipo docente pide cubrir del 01 de enero de 2026 al 31 de diciembre de 2028 con
 * archivos mensuales de ventas del apartado 5 del contexto de dominio. Un perfil concentra
 * las decisiones estadisticas de esa generacion, de modo que {@link GeneradorVentas} solo
 * las aplique y el juego de datos quede descrito por un unico objeto reproducible.</p>
 *
 * <p><b>Capacidad de la flota y colapso logistico.</b> La hoja de flota consigna, para el
 * dia extremo, cuatro viajes por auto, cuatro por moto y dos por bicicleta, es decir
 * {@code 10*24*4 + 15*8*4 + 12*4*2 = 1 536} unidades del producto P transportadas en un
 * dia. Ese numero es la cota superior absoluta de lo que la flota puede mover en 24 horas,
 * sin contar bloqueos, mantenimientos ni averias, de modo que un perfil cuya demanda diaria
 * estimada supere de forma sostenida las {@value #CAPACIDAD_DIARIA_MAXIMA_FLOTA} unidades
 * lleva al colapso logistico del escenario del apartado 11 por construccion, y no por azar.
 * {@link #primerMesDeSaturacion()} indica en que mes ocurre eso para un perfil dado.</p>
 *
 * <p>Las cinco fracciones de plazo deben sumar uno. Corresponden al plazo estandar de 36
 * horas y a los plazos priorizados de 18, 12, 8 y 4 horas del apartado 5.</p>
 *
 * @param nombre               nombre del perfil, el que acepta el ejecutable de generacion
 * @param pedidosPorDiaInicial pedidos diarios esperados en el mes base
 * @param crecimientoMensual   crecimiento compuesto por mes, en fraccion (0.04 es un 4 por ciento)
 * @param semilla              semilla maestra; el mismo perfil y el mismo mes dan siempre el mismo archivo
 * @param fraccion36h          fraccion de pedidos con el plazo estandar de 36 horas
 * @param fraccion18h          fraccion de pedidos con plazo priorizado de 18 horas
 * @param fraccion12h          fraccion de pedidos con plazo priorizado de 12 horas
 * @param fraccion8h           fraccion de pedidos con plazo priorizado de 8 horas
 * @param fraccion4h           fraccion de pedidos con plazo priorizado de 4 horas
 */
public record PerfilDemanda(
        String nombre,
        double pedidosPorDiaInicial,
        double crecimientoMensual,
        long semilla,
        double fraccion36h,
        double fraccion18h,
        double fraccion12h,
        double fraccion8h,
        double fraccion4h) {

    /** Unidades del producto P que la flota completa puede mover en un dia extremo. */
    public static final int CAPACIDAD_DIARIA_MAXIMA_FLOTA = 1536;

    /**
     * Unidades medias por pedido con las que {@link GeneradorVentas} calibra su cola de
     * cantidades. Sirve para traducir pedidos por dia en unidades por dia y compararlas
     * con la capacidad de la flota.
     */
    public static final double UNIDADES_MEDIAS_POR_PEDIDO = 4.0;

    /** Numero de meses mas alla del cual se deja de buscar el mes de saturacion. */
    private static final int MESES_MAXIMOS_DE_BUSQUEDA = 1200;

    /**
     * Demanda plana, pensada para el escenario de simulacion semanal de cinco dias.
     * Unas 800 unidades diarias, algo mas de la mitad de la capacidad extrema de la flota,
     * de modo que el escenario sea exigente pero resoluble con {@code H = 0}.
     */
    public static final PerfilDemanda ESTABLE =
            new PerfilDemanda("ESTABLE", 200.0, 0.0, 20260501L, 0.55, 0.20, 0.13, 0.08, 0.04);

    /**
     * Crecimiento sostenido del cuatro por ciento mensual desde 120 pedidos diarios.
     * Al cabo de treinta meses la demanda estimada supera las 1 536 unidades diarias, de
     * modo que el escenario de colapso encuentra su primer pedido no entregable dentro del
     * periodo 2026-2028 sin necesidad de forzar nada a mano.
     */
    public static final PerfilDemanda CRECIENTE =
            new PerfilDemanda("CRECIENTE", 120.0, 0.04, 20260101L, 0.50, 0.20, 0.15, 0.10, 0.05);

    /**
     * Demanda minima para pruebas unitarias y para la verificacion de equivalencia del
     * apartado 12.4 del ISA: unas pocas decenas de pedidos en todo el mes, de modo que el
     * juego de datos pueda revisarse a ojo.
     */
    public static final PerfilDemanda LIGERO =
            new PerfilDemanda("LIGERO", 1.5, 0.0, 777L, 0.60, 0.15, 0.13, 0.08, 0.04);

    public PerfilDemanda {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El perfil de demanda necesita un nombre");
        }
        if (pedidosPorDiaInicial <= 0.0 || !Double.isFinite(pedidosPorDiaInicial)) {
            throw new IllegalArgumentException("Pedidos por dia iniciales invalidos: " + pedidosPorDiaInicial);
        }
        if (crecimientoMensual <= -1.0 || !Double.isFinite(crecimientoMensual)) {
            throw new IllegalArgumentException("Crecimiento mensual invalido: " + crecimientoMensual);
        }
        double suma = fraccion36h + fraccion18h + fraccion12h + fraccion8h + fraccion4h;
        if (Math.abs(suma - 1.0) > 1e-9) {
            throw new IllegalArgumentException("La mezcla de plazos debe sumar uno, y suma " + suma);
        }
        if (fraccion36h < 0 || fraccion18h < 0 || fraccion12h < 0 || fraccion8h < 0 || fraccion4h < 0) {
            throw new IllegalArgumentException("Ninguna fraccion de plazo puede ser negativa");
        }
    }

    /** Perfiles con nombre disponibles, en el orden en que se documentan. */
    public static PerfilDemanda[] perfiles() {
        return new PerfilDemanda[] {ESTABLE, CRECIENTE, LIGERO};
    }

    /** Resuelve un perfil por su nombre, sin distinguir mayusculas. */
    public static PerfilDemanda porNombre(String nombre) {
        for (PerfilDemanda p : perfiles()) {
            if (p.nombre.equalsIgnoreCase(nombre)) {
                return p;
            }
        }
        throw new IllegalArgumentException(
                "Perfil de demanda desconocido: " + nombre + ". Validos: ESTABLE, CRECIENTE, LIGERO");
    }

    /** Copia del perfil con otra semilla, para generar replicas independientes del mismo escenario. */
    public PerfilDemanda conSemilla(long otraSemilla) {
        return new PerfilDemanda(nombre, pedidosPorDiaInicial, crecimientoMensual, otraSemilla,
                fraccion36h, fraccion18h, fraccion12h, fraccion8h, fraccion4h);
    }

    /** Copia del perfil con otra demanda inicial, manteniendo el resto de decisiones. */
    public PerfilDemanda conPedidosPorDiaInicial(double pedidos) {
        return new PerfilDemanda(nombre, pedidos, crecimientoMensual, semilla,
                fraccion36h, fraccion18h, fraccion12h, fraccion8h, fraccion4h);
    }

    /**
     * Pedidos diarios esperados en el mes numero {@code indiceMes}, contado desde el mes
     * base del generador. El crecimiento es compuesto.
     */
    public double pedidosPorDia(int indiceMes) {
        return pedidosPorDiaInicial * Math.pow(1.0 + crecimientoMensual, indiceMes);
    }

    /** Unidades del producto P que se esperan por dia en ese mes. */
    public double unidadesPorDia(int indiceMes) {
        return pedidosPorDia(indiceMes) * UNIDADES_MEDIAS_POR_PEDIDO;
    }

    /** Fraccion de la capacidad extrema de la flota que consume la demanda de ese mes. */
    public double saturacion(int indiceMes) {
        return unidadesPorDia(indiceMes) / CAPACIDAD_DIARIA_MAXIMA_FLOTA;
    }

    /**
     * Primer mes, contado desde el mes base, en que la demanda estimada supera la capacidad
     * extrema de la flota y el colapso logistico deja de ser evitable. Devuelve {@code -1}
     * si el perfil nunca llega a ese punto, que es lo que ocurre con los perfiles planos.
     */
    public int primerMesDeSaturacion() {
        for (int mes = 0; mes <= MESES_MAXIMOS_DE_BUSQUEDA; mes++) {
            if (unidadesPorDia(mes) > CAPACIDAD_DIARIA_MAXIMA_FLOTA) {
                return mes;
            }
            if (crecimientoMensual <= 0.0) {
                // Sin crecimiento la demanda no cambia: basta con evaluar el primer mes.
                return -1;
            }
        }
        return -1;
    }

    /**
     * Sortea el plazo de un pedido, en horas, segun la mezcla del perfil. Se evaluan
     * primero los plazos priorizados para que cualquier residuo de redondeo caiga en el
     * plazo estandar de 36 horas, que es el comportamiento por defecto de la empresa.
     */
    public int plazoHoras(Aleatorio aleatorio) {
        double u = aleatorio.siguienteDouble();
        double acumulado = fraccion4h;
        if (u < acumulado) {
            return 4;
        }
        acumulado += fraccion8h;
        if (u < acumulado) {
            return 8;
        }
        acumulado += fraccion12h;
        if (u < acumulado) {
            return 12;
        }
        acumulado += fraccion18h;
        if (u < acumulado) {
            return 18;
        }
        return 36;
    }

    /** Descripcion de una linea para el resumen por consola del ejecutable de generacion. */
    public String descripcion() {
        return String.format(Locale.ROOT,
                "%s: %.1f pedidos/dia iniciales, crecimiento %.2f%% mensual, semilla %d",
                nombre, pedidosPorDiaInicial, crecimientoMensual * 100.0, semilla);
    }
}
