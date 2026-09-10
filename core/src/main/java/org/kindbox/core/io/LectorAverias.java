package org.kindbox.core.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.kindbox.core.modelo.Averia;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Lector del archivo de averias de la respuesta 3 del cuestionario.
 *
 * <p>El apartado 7 del documento de dominio establece que las averias se registran en el
 * visualizador, de forma individual o por <b>carga masiva de archivo</b>. El equipo docente
 * <b>no publico</b> el formato de ese archivo, de modo que se <b>adopta</b> uno analogo al
 * del archivo de ventas y se documenta aqui como decision propia del equipo:</p>
 *
 * <pre>##d##h##m:TTNN:T</pre>
 *
 * <p>El primer grupo es el instante del incidente, con el dia referido al mes del archivo
 * igual que en ventas y bloqueos; {@code TTNN} es el codigo de la unidad afectada y
 * {@code T} el tipo de averia, 1 menor, 2 intermedia o 3 mayor. Un registro real seria
 * {@code 03d11h47m:TB07:1}, que averia la bicicleta TB07 el dia 3 a las 11:47 con una
 * averia menor.</p>
 *
 * <p>El registro <b>no lleva el lugar del incidente</b>, y es deliberado: el apartado 7
 * situa la averia donde este la unidad, de modo que el nodo lo resuelve el motor de
 * simulacion en el instante del suceso. Por eso el lector devuelve
 * {@link AveriaProgramada}, que es el registro tal cual viene del archivo, y no un
 * {@link Averia} del modelo; {@link AveriaProgramada#enNodo(int)} completa el dato cuando
 * el motor ya sabe donde estaba la unidad.</p>
 *
 * <p>Los codigos de unidad se normalizan a mayusculas, de modo que casen con los que
 * produce {@link LectorFlota} sin depender de como se haya escrito el archivo.</p>
 *
 * <p>Se ignoran las lineas en blanco y las que empiezan por almohadilla. Cualquier otro
 * registro mal formado detiene la carga con un mensaje que incluye el archivo, el numero de
 * linea y el registro completo: una averia perdida en silencio cambiaria la disponibilidad
 * de la flota y con ella el nivel 1 de la funcion objetivo del apartado 2.5 del ISA.</p>
 */
public final class LectorAverias {

    /** Prefijo del nombre de los archivos mensuales de averias. */
    public static final String PREFIJO_ARCHIVO = "averias";

    /** Marca de orden de bytes de UTF-8 vista a traves de la decodificacion ISO-8859-1. */
    private static final String MARCA_ORDEN_ISO = "\u00EF\u00BB\u00BF";

    private final CalendarioEscenario calendario;

    public LectorAverias(CalendarioEscenario calendario) {
        if (calendario == null) {
            throw new IllegalArgumentException("El lector de averias necesita un calendario de escenario");
        }
        this.calendario = calendario;
    }

    /**
     * Lee el archivo deduciendo el mes de su nombre.
     *
     * @param archivo ruta del archivo de averias
     * @return averias en el orden en que aparecen en el archivo
     */
    public List<AveriaProgramada> leer(Path archivo) throws IOException {
        return leer(archivo, mesDeArchivo(archivo.getFileName().toString()));
    }

    /**
     * Lee el archivo con el mes indicado de forma explicita. Es la variante que usa la
     * carga masiva del visualizador, donde el archivo que sube el usuario puede llamarse
     * de cualquier manera.
     *
     * @param archivo ruta del archivo de averias
     * @param mes     mes al que pertenecen los dias de los registros
     * @return averias en el orden en que aparecen en el archivo
     */
    public List<AveriaProgramada> leer(Path archivo, YearMonth mes) throws IOException {
        String nombre = archivo.getFileName().toString();
        List<AveriaProgramada> averias = new ArrayList<>();
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
                averias.add(interpretar(registro, nombre, numeroLinea, mes));
            }
        }
        return averias;
    }

    /** Interpreta un unico registro ya limpio de espacios. */
    private AveriaProgramada interpretar(String registro, String archivo, int numeroLinea, YearMonth mes) {
        String[] campos = registro.split(":", -1);
        if (campos.length != 3) {
            throw error(archivo, numeroLinea, registro, "se esperaban 3 campos separados por ':'"
                    + " (##d##h##m:TTNN:T) y hay " + campos.length);
        }
        long minutoAveria = instante(campos[0].trim(), mes, archivo, numeroLinea, registro);

        String codigo = campos[1].trim().toUpperCase(Locale.ROOT);
        validarCodigo(codigo, archivo, numeroLinea, registro);

        int codigoTipo = entero(campos[2], "el tipo de averia", archivo, numeroLinea, registro);
        TipoAveria tipo;
        try {
            tipo = TipoAveria.porCodigo(codigoTipo);
        } catch (IllegalArgumentException e) {
            throw error(archivo, numeroLinea, registro, "el tipo de averia debe ser 1, 2 o 3 y es "
                    + codigoTipo);
        }
        return new AveriaProgramada(minutoAveria, codigo, tipo);
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

    /** Convierte el campo {@code ##d##h##m} al reloj interno. */
    private long instante(String texto, YearMonth mes, String archivo, int numeroLinea, String registro) {
        int posD = texto.indexOf('d');
        int posH = texto.indexOf('h');
        int posM = texto.indexOf('m');
        if (posD <= 0 || posH <= posD || posM <= posH || posM != texto.length() - 1) {
            throw error(archivo, numeroLinea, registro,
                    "el instante de la averia \"" + texto + "\" no sigue el formato ##d##h##m");
        }
        int dia = entero(texto.substring(0, posD), "el dia del mes", archivo, numeroLinea, registro);
        int hora = entero(texto.substring(posD + 1, posH), "la hora", archivo, numeroLinea, registro);
        int minuto = entero(texto.substring(posH + 1, posM), "el minuto", archivo, numeroLinea, registro);
        try {
            return calendario.minutosDeRegistroVentas(dia, hora, minuto, mes);
        } catch (IllegalArgumentException e) {
            throw error(archivo, numeroLinea, registro, e.getMessage());
        }
    }

    // ---------------------------------------------------- nombres de archivo

    /** Nombre canonico del archivo mensual de averias del mes dado, {@code averiasAAAAMM}. */
    public static String nombreDeArchivo(YearMonth mes) {
        return String.format("%s%04d%02d", PREFIJO_ARCHIVO, mes.getYear(), mes.getMonthValue());
    }

    /** Indica si el nombre corresponde a un archivo mensual de averias. */
    public static boolean esArchivoDeAverias(String nombre) {
        if (nombre == null || !nombre.regionMatches(true, 0, PREFIJO_ARCHIVO, 0, PREFIJO_ARCHIVO.length())) {
            return false;
        }
        return digitosIniciales(nombre.substring(PREFIJO_ARCHIVO.length())).length() == 6;
    }

    /**
     * Deduce el mes del nombre del archivo. Se leen los seis digitos que siguen al prefijo
     * como {@code AAAAMM} y se admite cualquier extension posterior.
     */
    public static YearMonth mesDeArchivo(String nombre) {
        if (!esArchivoDeAverias(nombre)) {
            throw new IllegalArgumentException("No se puede deducir el mes del nombre de archivo \"" + nombre
                    + "\"; se esperaba " + PREFIJO_ARCHIVO + "AAAAMM, por ejemplo averias202609");
        }
        String digitos = digitosIniciales(nombre.substring(PREFIJO_ARCHIVO.length()));
        int ano = Integer.parseInt(digitos.substring(0, 4));
        int mes = Integer.parseInt(digitos.substring(4, 6));
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El nombre de archivo \"" + nombre + "\" declara el mes " + mes
                    + ", que no existe");
        }
        return YearMonth.of(ano, mes);
    }

    // ------------------------------------------------------------ utilitarios

    /** Digitos con los que arranca el texto, hasta el primer caracter que no lo sea. */
    private static String digitosIniciales(String texto) {
        int fin = 0;
        while (fin < texto.length() && Character.isDigit(texto.charAt(fin))) {
            fin++;
        }
        return texto.substring(0, fin);
    }

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

    /**
     * Averia tal como viene del archivo, sin el lugar del incidente.
     *
     * <p>El formato adoptado no consigna el nodo porque la averia ocurre donde este la
     * unidad en ese instante, dato que solo conoce el motor de simulacion. Este registro es
     * por tanto una averia <em>programada</em>: se sabe a quien, cuando y de que tipo, y el
     * donde se resuelve al dispararla.</p>
     *
     * @param minutoAveria instante del incidente, en minutos desde el inicio del escenario
     * @param codigoUnidad codigo TTNN de la unidad afectada, en mayusculas
     * @param tipo         tipo de averia
     */
    public record AveriaProgramada(long minutoAveria, String codigoUnidad, TipoAveria tipo)
            implements Comparable<AveriaProgramada> {

        /** Completa el registro con el nodo en que quedo inmovilizada la unidad. */
        public Averia enNodo(int nodo) {
            return new Averia(codigoUnidad, tipo, minutoAveria, nodo);
        }

        /** Orden natural por instante y, a igualdad de instante, por codigo de unidad. */
        @Override
        public int compareTo(AveriaProgramada otra) {
            int porMinuto = Long.compare(minutoAveria, otra.minutoAveria);
            return porMinuto != 0 ? porMinuto : codigoUnidad.compareTo(otra.codigoUnidad);
        }
    }
}
