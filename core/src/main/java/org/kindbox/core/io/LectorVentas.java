package org.kindbox.core.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Pedido;

/**
 * Lector del archivo mensual de pedidos de la respuesta 8 del cuestionario.
 *
 * <p>El archivo se llama {@code ventasAAAAMM}, por ejemplo {@code ventas202601}, y cada
 * registro tiene el formato {@code ##d##h##m:posX,posY,cIdCliente,qq,hl}. Un registro real
 * es {@code 11d13h31m:45,43,c9167,12,36}: el pedido llega el dia 11 a las 13:31, se entrega
 * en el nodo (45,43) al cliente c9167, son 12 unidades del producto P y el plazo es de
 * 36 horas.</p>
 *
 * <p>Como el campo de tiempo solo trae el dia del mes, el mes del archivo es necesario
 * para situar el pedido en el reloj interno. Se deduce del nombre del archivo, y existe
 * una sobrecarga para pasarlo de forma explicita cuando el nombre no sigue la convencion.</p>
 *
 * <p>Los identificadores de pedido son correlativos y arrancan en el valor que recibe el
 * lector, de modo que se pueden encadenar varios meses sin colisiones: basta sumar al
 * primer identificador la cantidad de pedidos devueltos para obtener el arranque del mes
 * siguiente.</p>
 *
 * <p>Se ignoran las lineas en blanco y las que empiezan por almohadilla. Cualquier otra
 * cosa mal formada detiene la carga con un mensaje que incluye el archivo, el numero de
 * linea y el registro completo, porque un pedido perdido en silencio falsearia el nivel 1
 * de la funcion objetivo del apartado 2.5 del ISA.</p>
 */
public final class LectorVentas {

    /** Prefijo del nombre de los archivos mensuales de ventas. */
    public static final String PREFIJO_ARCHIVO = "ventas";

    /** Marca de orden de bytes de UTF-8 vista a traves de la decodificacion ISO-8859-1. */
    private static final String MARCA_ORDEN_ISO = "\u00EF\u00BB\u00BF";

    private final CalendarioEscenario calendario;

    public LectorVentas(CalendarioEscenario calendario) {
        if (calendario == null) {
            throw new IllegalArgumentException("El lector de ventas necesita un calendario de escenario");
        }
        this.calendario = calendario;
    }

    /**
     * Lee el archivo deduciendo el mes de su nombre.
     *
     * @param archivo  ruta del archivo mensual de ventas
     * @param primerId identificador del primer pedido; los siguientes son correlativos
     * @return pedidos en el orden en que aparecen en el archivo
     */
    public List<Pedido> leer(Path archivo, int primerId) throws IOException {
        return leer(archivo, mesDeArchivo(archivo.getFileName().toString()), primerId);
    }

    /**
     * Lee el archivo con el mes indicado de forma explicita.
     *
     * @param archivo  ruta del archivo mensual de ventas
     * @param mes      mes al que pertenecen los dias de los registros
     * @param primerId identificador del primer pedido; los siguientes son correlativos
     * @return pedidos en el orden en que aparecen en el archivo
     */
    public List<Pedido> leer(Path archivo, YearMonth mes, int primerId) throws IOException {
        String nombre = archivo.getFileName().toString();
        List<Pedido> pedidos = new ArrayList<>();
        // Se decodifica como ISO-8859-1 y no como UTF-8 porque los registros son ASCII puro
        // y esta codificacion nunca falla ante un comentario escrito con tildes.
        try (BufferedReader lector = Files.newBufferedReader(archivo, StandardCharsets.ISO_8859_1)) {
            String linea;
            int numeroLinea = 0;
            int id = primerId;
            while ((linea = lector.readLine()) != null) {
                numeroLinea++;
                String registro = limpiar(linea, numeroLinea);
                if (registro.isEmpty() || registro.charAt(0) == '#') {
                    continue;
                }
                pedidos.add(interpretar(registro, nombre, numeroLinea, mes, id));
                id++;
            }
        }
        return pedidos;
    }

    /** Interpreta un unico registro ya limpio de espacios. */
    private Pedido interpretar(String registro, String archivo, int numeroLinea, YearMonth mes, int id) {
        int separador = registro.indexOf(':');
        if (separador < 0) {
            throw error(archivo, numeroLinea, registro,
                    "falta el separador ':' entre el instante de llegada y los datos del pedido");
        }
        long minutoRegistro = instante(registro.substring(0, separador).trim(), mes, archivo, numeroLinea, registro);

        String[] campos = registro.substring(separador + 1).split(",", -1);
        if (campos.length != 5) {
            throw error(archivo, numeroLinea, registro,
                    "se esperaban 5 campos separados por comas (posX,posY,cIdCliente,qq,hl) y hay " + campos.length);
        }

        int x = entero(campos[0], "la coordenada X", archivo, numeroLinea, registro);
        int y = entero(campos[1], "la coordenada Y", archivo, numeroLinea, registro);
        if (!Ciudad.dentro(x, y)) {
            throw error(archivo, numeroLinea, registro, "la coordenada (" + x + "," + y
                    + ") esta fuera de la ciudad, que abarca de (0,0) a ("
                    + Ciudad.LARGO_KM + "," + Ciudad.ANCHO_KM + ")");
        }

        String idCliente = campos[2].trim();
        if (idCliente.length() < 2 || (idCliente.charAt(0) != 'c' && idCliente.charAt(0) != 'C')) {
            throw error(archivo, numeroLinea, registro, "el identificador de cliente \"" + idCliente
                    + "\" debe llevar el prefijo 'c' seguido del numero, como c9167");
        }

        int cantidad = entero(campos[3], "la cantidad de producto P", archivo, numeroLinea, registro);
        if (cantidad <= 0) {
            throw error(archivo, numeroLinea, registro, "la cantidad de producto P debe ser positiva y es " + cantidad);
        }

        int plazoHoras = entero(campos[4], "el plazo en horas", archivo, numeroLinea, registro);
        if (plazoHoras <= 0) {
            throw error(archivo, numeroLinea, registro, "el plazo en horas debe ser positivo y es " + plazoHoras);
        }

        return new Pedido(id, idCliente, Ciudad.nodo(x, y), cantidad, minutoRegistro, plazoHoras);
    }

    /** Convierte el campo {@code ##d##h##m} al reloj interno. */
    private long instante(String texto, YearMonth mes, String archivo, int numeroLinea, String registro) {
        int posD = texto.indexOf('d');
        int posH = texto.indexOf('h');
        int posM = texto.indexOf('m');
        if (posD <= 0 || posH <= posD || posM <= posH || posM != texto.length() - 1) {
            throw error(archivo, numeroLinea, registro,
                    "el instante de llegada \"" + texto + "\" no sigue el formato ##d##h##m");
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

    /** Nombre canonico del archivo mensual de ventas del mes dado, {@code ventasAAAAMM}. */
    public static String nombreDeArchivo(YearMonth mes) {
        return String.format("%s%04d%02d", PREFIJO_ARCHIVO, mes.getYear(), mes.getMonthValue());
    }

    /** Indica si el nombre corresponde a un archivo mensual de ventas. */
    public static boolean esArchivoDeVentas(String nombre) {
        if (nombre == null || !nombre.regionMatches(true, 0, PREFIJO_ARCHIVO, 0, PREFIJO_ARCHIVO.length())) {
            return false;
        }
        return digitosIniciales(nombre.substring(PREFIJO_ARCHIVO.length())).length() == 6;
    }

    /**
     * Deduce el mes del nombre del archivo. El ano puede variar, de modo que se leen los
     * seis digitos que siguen al prefijo como {@code AAAAMM} y se admite cualquier
     * extension posterior.
     */
    public static YearMonth mesDeArchivo(String nombre) {
        if (!esArchivoDeVentas(nombre)) {
            throw new IllegalArgumentException("No se puede deducir el mes del nombre de archivo \"" + nombre
                    + "\"; se esperaba " + PREFIJO_ARCHIVO + "AAAAMM, por ejemplo ventas202601");
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
}
