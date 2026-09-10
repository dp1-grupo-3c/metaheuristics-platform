package org.kindbox.core.datagen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.kindbox.core.modelo.Mantenimiento;

/**
 * Generador de los archivos de mantenimiento preventivo del apartado 8 del contexto de
 * dominio. Los archivos se llaman {@code mant.preventivo.MM.MM}, cubren un par de meses y
 * sus registros tienen el formato {@code aaaammdd:TTNN}.
 *
 * <p><b>Plan entregado.</b> El plan de septiembre y octubre de 2026 es dato del equipo
 * docente, tomado de la columna "Plan ordenado por dia" de la hoja de flota, y se
 * reproduce aqui verbatim en {@link #planEntregado()}: 37 entradas, un solo mantenimiento
 * por dia y una entrada por cada una de las 37 unidades de la flota (10 autos, 15 motos y
 * 12 bicicletas). Cuando el rango pedido cubre esos dos meses se emite tal cual, sin
 * reordenar ni recalcular nada.</p>
 *
 * <p><b>Regla para el resto del periodo.</b> El plan entregado define un orden de rotacion
 * de la flota, que es simplemente el orden en que aparecen sus unidades. Fuera de
 * septiembre y octubre de 2026 se continua esa misma rotacion: a cada dia calendario le
 * corresponde la siguiente unidad de la lista, que vuelve a empezar al agotarse. Solo se
 * usan los dias {@code 01} a {@value #DIA_MAXIMO} de cada mes, para que el plan sea igual
 * en meses cortos y largos y para que febrero no obligue a un caso especial. Como 28 dias
 * y 37 unidades no son multiplos, el corte se desplaza mes a mes y a lo largo del ano toda
 * la flota entra a mantenimiento un numero parecido de veces.</p>
 *
 * <p>La rotacion se ancla en {@link #MES_ANCLA}: el indice de rotacion de un mes se deduce
 * contando las entradas emitidas desde ese ancla, de modo que el archivo de un mes es
 * siempre el mismo con independencia del rango que se pida generar. No interviene ningun
 * sorteo, de modo que el generador no necesita semilla.</p>
 */
public final class GeneradorMantenimiento {

    /** Ultimo dia del mes que se usa para programar mantenimientos. */
    public static final int DIA_MAXIMO = 28;

    /** Mes a partir del cual se cuenta la rotacion. */
    public static final YearMonth MES_ANCLA = YearMonth.of(2026, 1);

    /** Primer mes del plan entregado por el equipo docente. */
    public static final YearMonth MES_PLAN_ENTREGADO = YearMonth.of(2026, 9);

    /**
     * Plan de mantenimiento preventivo de septiembre y octubre de 2026, dato entregado por
     * el equipo docente. Se reproduce verbatim y en este orden.
     */
    private static final String[] PLAN_ENTREGADO = {
            "20260901:TA01", "20260902:TB01", "20260903:TM07", "20260906:TB11", "20260907:TA03",
            "20260908:TB03", "20260909:TM09", "20260912:TM01", "20260913:TA05", "20260914:TB05",
            "20260915:TM11", "20260918:TM03", "20260919:TA07", "20260920:TB07", "20260921:TM13",
            "20260924:TM05", "20260925:TA09", "20260926:TB09", "20260928:TM15", "20261003:TB10",
            "20261004:TA02", "20261005:TB02", "20261006:TM08", "20261009:TB12", "20261010:TA04",
            "20261011:TB04", "20261012:TM10", "20261015:TM02", "20261016:TA06", "20261017:TB06",
            "20261018:TM12", "20261021:TM04", "20261022:TA08", "20261023:TB08", "20261024:TM14",
            "20261027:TM06", "20261028:TA10"};

    /** Orden de rotacion de la flota, deducido del plan entregado. */
    private static final String[] ROTACION = new String[PLAN_ENTREGADO.length];

    static {
        for (int i = 0; i < PLAN_ENTREGADO.length; i++) {
            ROTACION[i] = PLAN_ENTREGADO[i].substring(PLAN_ENTREGADO[i].indexOf(':') + 1);
        }
    }

    private GeneradorMantenimiento() {
    }

    /** Plan entregado por el equipo docente, en el orden original. */
    public static List<String> planEntregado() {
        return List.of(PLAN_ENTREGADO);
    }

    /** Orden de rotacion de la flota que define el plan entregado. */
    public static List<String> rotacionFlota() {
        return List.of(ROTACION);
    }

    /** Numero de unidades de la rotacion, que es el tamano de la flota. */
    public static int unidadesEnRotacion() {
        return ROTACION.length;
    }

    /** Nombre del archivo que cubre el par de meses, {@code mant.preventivo.MM.MM}. */
    public static String nombreArchivo(YearMonth primerMes, YearMonth segundoMes) {
        return String.format("mant.preventivo.%02d.%02d", primerMes.getMonthValue(), segundoMes.getMonthValue());
    }

    /**
     * Primer mes del par al que pertenece el mes dado. Los pares son enero-febrero,
     * marzo-abril y asi sucesivamente, de modo que septiembre y octubre caen juntos como en
     * el archivo {@code mant.preventivo.09.10} del equipo docente.
     */
    public static YearMonth primerMesDelPar(YearMonth mes) {
        return mes.getMonthValue() % 2 == 1 ? mes : mes.minusMonths(1);
    }

    /** Numero de mantenimientos que programa el mes indicado. */
    public static int entradasDeMes(YearMonth mes) {
        if (esDelPlanEntregado(mes)) {
            int cuenta = 0;
            String prefijo = prefijoDeMes(mes);
            for (String entrada : PLAN_ENTREGADO) {
                if (entrada.startsWith(prefijo)) {
                    cuenta++;
                }
            }
            return cuenta;
        }
        return DIA_MAXIMO;
    }

    /** Registros {@code aaaammdd:TTNN} del mes, ordenados por fecha. */
    public static String[] registrosDeMes(YearMonth mes) {
        if (esDelPlanEntregado(mes)) {
            String prefijo = prefijoDeMes(mes);
            String[] registros = new String[entradasDeMes(mes)];
            int escritos = 0;
            for (String entrada : PLAN_ENTREGADO) {
                if (entrada.startsWith(prefijo)) {
                    registros[escritos++] = entrada;
                }
            }
            return registros;
        }
        int indice = indiceRotacionDe(mes);
        String[] registros = new String[DIA_MAXIMO];
        for (int dia = 1; dia <= DIA_MAXIMO; dia++) {
            String unidad = ROTACION[(indice + dia - 1) % ROTACION.length];
            registros[dia - 1] = String.format("%04d%02d%02d:%s",
                    mes.getYear(), mes.getMonthValue(), dia, unidad);
        }
        return registros;
    }

    /** Mismo plan que {@link #registrosDeMes(YearMonth)}, ya convertido al modelo del dominio. */
    public static Mantenimiento[] mantenimientosDeMes(YearMonth mes) {
        String[] registros = registrosDeMes(mes);
        Mantenimiento[] plan = new Mantenimiento[registros.length];
        for (int i = 0; i < registros.length; i++) {
            String registro = registros[i];
            int corte = registro.indexOf(':');
            LocalDate fecha = LocalDate.of(
                    Integer.parseInt(registro.substring(0, 4)),
                    Integer.parseInt(registro.substring(4, 6)),
                    Integer.parseInt(registro.substring(6, 8)));
            plan[i] = new Mantenimiento(fecha, registro.substring(corte + 1));
        }
        return plan;
    }

    /**
     * Escribe el archivo del par de meses que empieza en {@code primerMes} dentro del
     * directorio indicado, que se crea si no existe, y devuelve cuantas entradas contiene.
     * El nombre no lleva el ano, de modo que quien genere varios anos debe darle a cada uno
     * su propio directorio.
     */
    public static int escribirPar(YearMonth primerMes, Path directorio) throws IOException {
        YearMonth segundoMes = primerMes.plusMonths(1);
        String[] primero = registrosDeMes(primerMes);
        String[] segundo = registrosDeMes(segundoMes);
        Files.createDirectories(directorio);
        Path destino = directorio.resolve(nombreArchivo(primerMes, segundoMes));
        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.US_ASCII)) {
            for (String registro : primero) {
                escritor.write(registro);
                // Salto de linea explicito para que el archivo no dependa del sistema.
                escritor.write('\n');
            }
            for (String registro : segundo) {
                escritor.write(registro);
                escritor.write('\n');
            }
        }
        return primero.length + segundo.length;
    }

    /**
     * Primeros meses de los pares que hacen falta para cubrir el rango de meses dado. Un
     * par se incluye completo aunque el rango solo toque uno de sus dos meses, porque el
     * archivo del equipo docente es siempre de dos meses.
     */
    public static List<YearMonth> paresQueCubren(YearMonth desde, YearMonth hasta) {
        List<YearMonth> pares = new ArrayList<>();
        YearMonth par = primerMesDelPar(desde);
        while (!par.isAfter(hasta)) {
            pares.add(par);
            par = par.plusMonths(2);
        }
        return pares;
    }

    /**
     * Comprueba el invariante del plan entregado: la rotacion recorre las 37 unidades de la
     * flota sin repetir ninguna. Lo verifican las pruebas unitarias del juego de datos.
     */
    public static boolean rotacionSinRepeticiones() {
        String[] copia = ROTACION.clone();
        Arrays.sort(copia);
        for (int i = 1; i < copia.length; i++) {
            if (copia[i].equals(copia[i - 1])) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ interno

    private static boolean esDelPlanEntregado(YearMonth mes) {
        return mes.equals(MES_PLAN_ENTREGADO) || mes.equals(MES_PLAN_ENTREGADO.plusMonths(1));
    }

    private static String prefijoDeMes(YearMonth mes) {
        return String.format("%04d%02d", mes.getYear(), mes.getMonthValue());
    }

    /**
     * Indice de la rotacion con el que arranca el mes. Se obtiene acumulando las entradas
     * de todos los meses que van del ancla al mes pedido, de modo que el resultado no
     * dependa del rango que se este generando.
     */
    private static int indiceRotacionDe(YearMonth mes) {
        int acumulado = 0;
        if (mes.isAfter(MES_ANCLA)) {
            for (YearMonth m = MES_ANCLA; m.isBefore(mes); m = m.plusMonths(1)) {
                acumulado += entradasDeMes(m);
            }
        } else {
            for (YearMonth m = mes; m.isBefore(MES_ANCLA); m = m.plusMonths(1)) {
                acumulado -= entradasDeMes(m);
            }
        }
        return Math.floorMod(acumulado, ROTACION.length);
    }
}
