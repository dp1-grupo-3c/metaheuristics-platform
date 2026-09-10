package org.kindbox.core.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Lector del plan de mantenimiento preventivo de la respuesta 19 del cuestionario.
 *
 * <p>El archivo se llama {@code mant.preventivo.m1.m2}, por ejemplo
 * {@code mant.preventivo.09.10} para el plan de septiembre y octubre, y cada registro tiene
 * el formato {@code aaaammdd:TTNN}, por ejemplo {@code 20260901:TA05}. La unidad indicada no
 * esta disponible para la planificacion desde las 00:00 hasta las 23:59 de ese dia.</p>
 *
 * <p>Los codigos de unidad se normalizan a mayusculas, de modo que casen con los que
 * produce {@link LectorFlota} sin depender de como se haya escrito el archivo.</p>
 *
 * <p>Ademas de la lista de entradas se ofrece la vista agrupada por fecha, que es la forma
 * en que la consume el motor de simulacion: al cambiar de dia necesita, de un solo acceso,
 * el conjunto de unidades que debe retirar de la planificacion.</p>
 *
 * <p>Se ignoran las lineas en blanco y las que empiezan por almohadilla.</p>
 */
public final class LectorMantenimiento {

    /** Prefijo del nombre de los archivos de mantenimiento preventivo. */
    public static final String PREFIJO_ARCHIVO = "mant.preventivo.";

    /** Marca de orden de bytes de UTF-8 vista a traves de la decodificacion ISO-8859-1. */
    private static final String MARCA_ORDEN_ISO = "\u00EF\u00BB\u00BF";

    /**
     * Lee el archivo completo.
     *
     * @param archivo ruta del archivo de mantenimiento preventivo
     * @return entradas en el orden en que aparecen en el archivo
     */
    public List<Mantenimiento> leer(Path archivo) throws IOException {
        String nombre = archivo.getFileName().toString();
        List<Mantenimiento> entradas = new ArrayList<>();
        // ISO-8859-1 y no UTF-8: los registros son ASCII puro y asi ningun comentario con
        // tildes puede hacer fallar la decodificacion.
        try (BufferedReader lector = Files.newBufferedReader(archivo, StandardCharsets.ISO_8859_1)) {
            String linea;
            int numeroLinea = 0;
            while ((linea = lector.readLine()) != null) {
                numeroLinea++;
                String registro = limpiar(linea, numeroLinea);
                if (registro.isEmpty() || registro.charAt(0) == '#') {
                    continue;
                }
                entradas.add(interpretar(registro, nombre, numeroLinea));
            }
        }
        return entradas;
    }

    /** Lee el archivo y devuelve directamente la vista agrupada por fecha. */
    public Map<LocalDate, Set<String>> leerAgrupado(Path archivo) throws IOException {
        return agrupadoPorFecha(leer(archivo));
    }

    /**
     * Agrupa las entradas por dia calendario. El mapa esta ordenado por fecha y cada
     * conjunto conserva el orden de lectura. El resultado es una copia que pertenece a
     * quien la pide.
     */
    public static Map<LocalDate, Set<String>> agrupadoPorFecha(List<Mantenimiento> entradas) {
        Map<LocalDate, Set<String>> porFecha = new TreeMap<>();
        for (Mantenimiento m : entradas) {
            porFecha.computeIfAbsent(m.fecha(), f -> new LinkedHashSet<>()).add(m.codigoUnidad());
        }
        return porFecha;
    }

    /** Interpreta un unico registro ya limpio de espacios. */
    private static Mantenimiento interpretar(String registro, String archivo, int numeroLinea) {
        int separador = registro.indexOf(':');
        if (separador < 0) {
            throw error(archivo, numeroLinea, registro,
                    "falta el separador ':' entre la fecha y el codigo de unidad");
        }
        String fechaTexto = registro.substring(0, separador).trim();
        if (fechaTexto.length() != 8) {
            throw error(archivo, numeroLinea, registro,
                    "la fecha \"" + fechaTexto + "\" no sigue el formato aaaammdd de ocho digitos");
        }
        int ano = entero(fechaTexto.substring(0, 4), "el ano", archivo, numeroLinea, registro);
        int mes = entero(fechaTexto.substring(4, 6), "el mes", archivo, numeroLinea, registro);
        int dia = entero(fechaTexto.substring(6, 8), "el dia", archivo, numeroLinea, registro);
        LocalDate fecha;
        try {
            fecha = LocalDate.of(ano, mes, dia);
        } catch (DateTimeException e) {
            throw error(archivo, numeroLinea, registro, "la fecha " + fechaTexto + " no existe en el calendario");
        }

        String codigo = registro.substring(separador + 1).trim().toUpperCase(Locale.ROOT);
        validarCodigo(codigo, archivo, numeroLinea, registro);
        return new Mantenimiento(fecha, codigo);
    }

    /** Exige que el codigo siga el formato TTNN con un prefijo de tipo conocido. */
    private static void validarCodigo(String codigo, String archivo, int numeroLinea, String registro) {
        if (codigo.length() < 3) {
            throw error(archivo, numeroLinea, registro,
                    "el codigo de unidad \"" + codigo + "\" no sigue el formato TTNN, como TA05");
        }
        try {
            TipoUnidad.porPrefijo(codigo.substring(0, 2));
        } catch (IllegalArgumentException e) {
            throw error(archivo, numeroLinea, registro, "el codigo de unidad \"" + codigo
                    + "\" no empieza por un prefijo de tipo conocido (TA autos, TM motos, TB bicicletas)");
        }
        for (int i = 2; i < codigo.length(); i++) {
            if (!Character.isDigit(codigo.charAt(i))) {
                throw error(archivo, numeroLinea, registro, "el codigo de unidad \"" + codigo
                        + "\" debe terminar en el correlativo numerico de la unidad");
            }
        }
    }

    // ---------------------------------------------------- nombres de archivo

    /**
     * Nombre canonico del archivo de mantenimiento de dos meses,
     * {@code mant.preventivo.m1.m2} con los meses a dos digitos.
     */
    public static String nombreDeArchivo(int mesInicio, int mesFin) {
        if (mesInicio < 1 || mesInicio > 12 || mesFin < 1 || mesFin > 12) {
            throw new IllegalArgumentException("Meses fuera del rango 1..12: " + mesInicio + " y " + mesFin);
        }
        return String.format("%s%02d.%02d", PREFIJO_ARCHIVO, mesInicio, mesFin);
    }

    /** Indica si el nombre corresponde a un archivo de mantenimiento preventivo. */
    public static boolean esArchivoDeMantenimiento(String nombre) {
        return nombre != null
                && nombre.regionMatches(true, 0, PREFIJO_ARCHIVO, 0, PREFIJO_ARCHIVO.length())
                && nombre.length() > PREFIJO_ARCHIVO.length();
    }

    // ------------------------------------------------------------ utilitarios

    /**
     * Quita los espacios de los extremos y, en la primera linea, la marca de orden de
     * bytes que algunos editores anteponen al archivo.
     */
    private static String limpiar(String linea, int numeroLinea) {
        String limpia = linea.trim();
        if (numeroLinea == 1) {
            if (!limpia.isEmpty() && limpia.charAt(0) == '\uFEFF') {
                limpia = limpia.substring(1).trim();
            } else if (limpia.startsWith(MARCA_ORDEN_ISO)) {
                limpia = limpia.substring(3).trim();
            }
        }
        return limpia;
    }

    /** Lee un entero de un campo, con un mensaje que dice cual es el campo culpable. */
    private static int entero(String texto, String campo, String archivo, int numeroLinea, String registro) {
        String limpio = texto.trim();
        try {
            return Integer.parseInt(limpio);
        } catch (NumberFormatException e) {
            throw error(archivo, numeroLinea, registro, campo + " no es un numero entero: \"" + limpio + "\"");
        }
    }

    /** Construye el error de formato con el archivo, la linea y el registro completo. */
    private static IllegalArgumentException error(String archivo, int numeroLinea, String registro, String motivo) {
        return new IllegalArgumentException(archivo + ", linea " + numeroLinea + ": " + motivo
                + ". Registro: \"" + registro + "\"");
    }
}
