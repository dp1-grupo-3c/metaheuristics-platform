package org.kindbox.core.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;

/**
 * Lector del archivo mensual de calles bloqueadas de la respuesta 7 del cuestionario.
 *
 * <p>El archivo se llama {@code AAAAMM.bloqueadas}, por ejemplo {@code 202601.bloqueadas}.
 * El equipo docente no publico el formato del registro, de modo que se <b>adopta</b> uno
 * analogo al del archivo de ventas y se documenta aqui como decision propia del equipo:</p>
 *
 * <pre>##d##h##m-##d##h##m:x1,y1,x2,y2,...,xn,yn</pre>
 *
 * <p>El primer grupo de tiempo es el inicio de la vigencia del bloqueo y el segundo su fin,
 * ambos referidos a dias del mes del archivo. A continuacion viene la poligonal como pares
 * de coordenadas separados por comas, con n mayor o igual a 2. Un registro real seria
 * {@code 01d08h00m-01d18h30m:12,20,13,20,13,21}, que corta dos tramos durante diez horas y
 * media del primer dia del mes.</p>
 *
 * <p>Se valida que dos nodos consecutivos de la poligonal sean adyacentes en la reticula,
 * es decir que difieran en 1 Km en exactamente un eje, porque la ciudad no tiene diagonales
 * ni calles de mas de un kilometro entre esquinas. Se valida tambien que la poligonal sea
 * abierta, es decir que no repita ningun nodo: el enunciado del curso 1INF54 2026-2 solo
 * usa poligonales abiertas y esa hipotesis es la que garantiza que todo nodo de la ciudad
 * sigue siendo alcanzable.</p>
 *
 * <p>Se ignoran las lineas en blanco y las que empiezan por almohadilla. Cualquier otro
 * registro mal formado detiene la carga con un mensaje que incluye el archivo, el numero
 * de linea y el registro completo.</p>
 */
public final class LectorBloqueos {

    /** Extension de los archivos mensuales de bloqueos. */
    public static final String EXTENSION_ARCHIVO = ".bloqueadas";

    /** Marca de orden de bytes de UTF-8 vista a traves de la decodificacion ISO-8859-1. */
    private static final String MARCA_ORDEN_ISO = "\u00EF\u00BB\u00BF";

    private final CalendarioEscenario calendario;

    public LectorBloqueos(CalendarioEscenario calendario) {
        if (calendario == null) {
            throw new IllegalArgumentException("El lector de bloqueos necesita un calendario de escenario");
        }
        this.calendario = calendario;
    }

    /** Lee el archivo deduciendo el mes de su nombre. */
    public List<Bloqueo> leer(Path archivo) throws IOException {
        return leer(archivo, mesDeArchivo(archivo.getFileName().toString()));
    }

    /**
     * Lee el archivo con el mes indicado de forma explicita.
     *
     * @param archivo ruta del archivo mensual de bloqueos
     * @param mes     mes al que pertenecen los dias de las ventanas de vigencia
     * @return bloqueos en el orden en que aparecen en el archivo
     */
    public List<Bloqueo> leer(Path archivo, YearMonth mes) throws IOException {
        String nombre = archivo.getFileName().toString();
        List<Bloqueo> bloqueos = new ArrayList<>();
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
                bloqueos.add(interpretar(registro, nombre, numeroLinea, mes));
            }
        }
        return bloqueos;
    }

    /** Interpreta un unico registro ya limpio de espacios. */
    private Bloqueo interpretar(String registro, String archivo, int numeroLinea, YearMonth mes) {
        int separador = registro.indexOf(':');
        if (separador < 0) {
            throw error(archivo, numeroLinea, registro,
                    "falta el separador ':' entre la ventana de vigencia y la poligonal");
        }
        String ventana = registro.substring(0, separador).trim();
        int guion = ventana.indexOf('-');
        if (guion <= 0 || guion == ventana.length() - 1) {
            throw error(archivo, numeroLinea, registro, "la ventana de vigencia \"" + ventana
                    + "\" no sigue el formato ##d##h##m-##d##h##m");
        }
        long minutoInicio = instante(ventana.substring(0, guion), mes, archivo, numeroLinea, registro);
        long minutoFin = instante(ventana.substring(guion + 1), mes, archivo, numeroLinea, registro);
        if (minutoFin <= minutoInicio) {
            throw error(archivo, numeroLinea, registro,
                    "la ventana de vigencia esta vacia porque el fin no es posterior al inicio");
        }

        String[] campos = registro.substring(separador + 1).split(",", -1);
        if (campos.length < 4 || campos.length % 2 != 0) {
            throw error(archivo, numeroLinea, registro, "la poligonal necesita pares x,y de al menos dos nodos"
                    + " y trae " + campos.length + " valores");
        }

        int cantidadNodos = campos.length / 2;
        int[] nodos = new int[cantidadNodos];
        for (int i = 0; i < cantidadNodos; i++) {
            int x = entero(campos[2 * i], "la coordenada X del nodo " + (i + 1), archivo, numeroLinea, registro);
            int y = entero(campos[2 * i + 1], "la coordenada Y del nodo " + (i + 1), archivo, numeroLinea, registro);
            if (!Ciudad.dentro(x, y)) {
                throw error(archivo, numeroLinea, registro, "el nodo " + (i + 1) + " de la poligonal, ("
                        + x + "," + y + "), esta fuera de la ciudad, que abarca de (0,0) a ("
                        + Ciudad.LARGO_KM + "," + Ciudad.ANCHO_KM + ")");
            }
            nodos[i] = Ciudad.nodo(x, y);
        }
        validarPoligonal(nodos, archivo, numeroLinea, registro);
        return new Bloqueo(nodos, minutoInicio, minutoFin);
    }

    /**
     * Exige que la poligonal sea una cadena de tramos de 1 Km y que no repita nodos.
     * La busqueda de repeticiones es cuadratica, pero las poligonales tienen unos pocos
     * nodos y esto ocurre una sola vez, al cargar el escenario.
     */
    private static void validarPoligonal(int[] nodos, String archivo, int numeroLinea, String registro) {
        // La repeticion se comprueba antes que la adyacencia porque un nodo repetido de
        // forma consecutiva tambien rompe la adyacencia y el motivo real es la repeticion.
        for (int i = 0; i < nodos.length; i++) {
            for (int j = i + 1; j < nodos.length; j++) {
                if (nodos[i] == nodos[j]) {
                    throw error(archivo, numeroLinea, registro, "la poligonal repite el nodo "
                            + Ciudad.texto(nodos[i]) + " en las posiciones " + (i + 1) + " y " + (j + 1)
                            + "; para este curso solo se admiten poligonales abiertas");
                }
            }
        }
        for (int i = 1; i < nodos.length; i++) {
            if (Ciudad.distanciaManhattan(nodos[i - 1], nodos[i]) != 1) {
                throw error(archivo, numeroLinea, registro, "los nodos " + i + " y " + (i + 1)
                        + " de la poligonal, " + Ciudad.texto(nodos[i - 1]) + " y " + Ciudad.texto(nodos[i])
                        + ", no son adyacentes: un tramo une esquinas separadas 1 Km en un solo eje");
            }
        }
    }

    /** Convierte un grupo {@code ##d##h##m} de la ventana de vigencia al reloj interno. */
    private long instante(String texto, YearMonth mes, String archivo, int numeroLinea, String registro) {
        String limpio = texto.trim();
        int posD = limpio.indexOf('d');
        int posH = limpio.indexOf('h');
        int posM = limpio.indexOf('m');
        if (posD <= 0 || posH <= posD || posM <= posH || posM != limpio.length() - 1) {
            throw error(archivo, numeroLinea, registro,
                    "el instante \"" + limpio + "\" no sigue el formato ##d##h##m");
        }
        int dia = entero(limpio.substring(0, posD), "el dia del mes", archivo, numeroLinea, registro);
        int hora = entero(limpio.substring(posD + 1, posH), "la hora", archivo, numeroLinea, registro);
        int minuto = entero(limpio.substring(posH + 1, posM), "el minuto", archivo, numeroLinea, registro);
        try {
            return calendario.minutosDeRegistroVentas(dia, hora, minuto, mes);
        } catch (IllegalArgumentException e) {
            throw error(archivo, numeroLinea, registro, e.getMessage());
        }
    }

    // ---------------------------------------------------- nombres de archivo

    /** Nombre canonico del archivo mensual de bloqueos, {@code AAAAMM.bloqueadas}. */
    public static String nombreDeArchivo(YearMonth mes) {
        return String.format("%04d%02d%s", mes.getYear(), mes.getMonthValue(), EXTENSION_ARCHIVO);
    }

    /**
     * Indica si el nombre corresponde a un archivo mensual de bloqueos. Se acepta tanto
     * {@code AAAAMM.bloqueadas} como la variante sin punto {@code AAAAMMbloqueadas}, porque
     * ambas aparecen en el material del curso.
     */
    public static boolean esArchivoDeBloqueos(String nombre) {
        return raizDeNombre(nombre) != null;
    }

    /** Deduce el mes del nombre del archivo. */
    public static YearMonth mesDeArchivo(String nombre) {
        String digitos = raizDeNombre(nombre);
        if (digitos == null) {
            throw new IllegalArgumentException("No se puede deducir el mes del nombre de archivo \"" + nombre
                    + "\"; se esperaba AAAAMM" + EXTENSION_ARCHIVO + ", por ejemplo 202601.bloqueadas");
        }
        int ano = Integer.parseInt(digitos.substring(0, 4));
        int mes = Integer.parseInt(digitos.substring(4, 6));
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El nombre de archivo \"" + nombre + "\" declara el mes " + mes
                    + ", que no existe");
        }
        return YearMonth.of(ano, mes);
    }

    /** Los seis digitos AAAAMM del nombre, o {@code null} si el nombre no es de bloqueos. */
    private static String raizDeNombre(String nombre) {
        if (nombre == null || nombre.length() < 6) {
            return null;
        }
        String resto = nombre.substring(6);
        boolean terminacionValida = resto.equalsIgnoreCase(EXTENSION_ARCHIVO)
                || resto.equalsIgnoreCase(EXTENSION_ARCHIVO.substring(1));
        if (!terminacionValida) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (!Character.isDigit(nombre.charAt(i))) {
                return null;
            }
        }
        return nombre.substring(0, 6);
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
