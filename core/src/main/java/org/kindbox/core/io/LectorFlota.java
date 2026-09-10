package org.kindbox.core.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Lector de la composicion de la flota, que en el material del curso llega como la hoja
 * {@code flota.csv} y aqui se espera en texto plano.
 *
 * <p>Se admiten dos formas de linea, mezclables en el mismo archivo:</p>
 * <ul>
 *   <li><b>Por tipo.</b> {@code AUTO,10} declara diez autos, y la forma larga
 *       {@code AUTO,10,24,40.0,8.00} agrega capacidad en paquetes, velocidad en Km/h y
 *       costo en soles por kilometro. Los tres ultimos campos son opcionales y se pueden
 *       omitir por la derecha. El tipo se escribe con su nombre ({@code AUTO}, {@code MOTO},
 *       {@code BICICLETA}, en singular o en plural) o con su prefijo ({@code TA}, {@code TM},
 *       {@code TB}), sin distinguir mayusculas.</li>
 *   <li><b>Por unidad.</b> {@code TA07} declara esa unidad concreta.</li>
 * </ul>
 *
 * <p>Las lineas por tipo se expanden a codigos correlativos segun el prefijo del tipo, es
 * decir {@code TA01..TA10}, {@code TM01..TM15} y {@code TB01..TB12} para la flota del
 * enunciado. El correlativo salta los codigos que ya haya tomado una linea por unidad, de
 * modo que ninguna unidad se declara dos veces.</p>
 *
 * <p>Todas las unidades arrancan en el nodo del almacen central, porque al inicio de
 * cualquier escenario todas las unidades salen de ahi (respuesta 4 del cuestionario).</p>
 *
 * <p>La capacidad, la velocidad y el costo que declare el archivo se devuelven aparte, en
 * {@link FlotaLeida}, y no se aplican solos. La razon es que en el nucleo la capacidad y el
 * costo por kilometro son constantes de {@link TipoUnidad}, mientras que la velocidad es el
 * unico parametro modificable en caliente y vive en {@link ParametrosOperacion}; ademas el
 * documento de dominio deja registrada una discrepancia entre las velocidades de la hoja y
 * las del enunciado, de modo que quien carga los datos debe decidir de forma consciente que
 * juego de valores usa.</p>
 */
public final class LectorFlota {

    /** Nombre habitual del archivo de flota dentro del directorio de datos. */
    public static final String NOMBRE_ARCHIVO = "flota.txt";

    /** Correlativo maximo admitido por tipo, que es el que cabe en el formato TTNN. */
    public static final int CORRELATIVO_MAXIMO = 99;

    /** Marca de orden de bytes de UTF-8 vista a traves de la decodificacion ISO-8859-1. */
    private static final String MARCA_ORDEN_ISO = "\u00EF\u00BB\u00BF";

    private final int nodoInicial;

    /** Lector que situa toda la flota en el almacen central. */
    public LectorFlota() {
        this(Almacen.crearCentral().nodo());
    }

    /** Lector que situa toda la flota en un nodo dado. Sirve para pruebas y variantes. */
    public LectorFlota(int nodoInicial) {
        this.nodoInicial = nodoInicial;
    }

    /**
     * Lee el archivo de flota.
     *
     * @param archivo ruta del archivo de composicion de la flota
     * @return unidades creadas y los valores por tipo que declare el archivo
     */
    public FlotaLeida leer(Path archivo) throws IOException {
        String nombre = archivo.getFileName().toString();
        List<UnidadTransporte> unidades = new ArrayList<>();
        Set<String> codigosUsados = new LinkedHashSet<>();
        Map<TipoUnidad, Integer> capacidades = new EnumMap<>(TipoUnidad.class);
        Map<TipoUnidad, Double> velocidades = new EnumMap<>(TipoUnidad.class);
        Map<TipoUnidad, Double> costos = new EnumMap<>(TipoUnidad.class);
        int[] correlativo = new int[TipoUnidad.values().length];

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
                String[] campos = registro.split(",", -1);
                if (campos[0].trim().equalsIgnoreCase("tipo")) {
                    continue; // Encabezado de la hoja de flota exportada a texto.
                }
                if (campos.length == 1) {
                    unidades.add(unidadConcreta(campos[0].trim(), codigosUsados, nombre, numeroLinea, registro));
                } else {
                    expandirTipo(campos, unidades, codigosUsados, correlativo,
                            capacidades, velocidades, costos, nombre, numeroLinea, registro);
                }
            }
        }
        if (unidades.isEmpty()) {
            throw new IllegalArgumentException("El archivo de flota " + nombre + " no declara ninguna unidad");
        }
        return new FlotaLeida(unidades, capacidades, velocidades, costos);
    }

    /**
     * Flota del enunciado: 10 autos, 15 motos y 12 bicicletas, sin valores declarados por
     * tipo. Sirve de respaldo cuando el directorio de datos no trae archivo de flota.
     */
    public FlotaLeida flotaPorDefecto() {
        List<UnidadTransporte> unidades = new ArrayList<>();
        agregarSerie(unidades, TipoUnidad.AUTO, 10);
        agregarSerie(unidades, TipoUnidad.MOTO, 15);
        agregarSerie(unidades, TipoUnidad.BICICLETA, 12);
        return new FlotaLeida(unidades, Map.of(), Map.of(), Map.of());
    }

    private void agregarSerie(List<UnidadTransporte> unidades, TipoUnidad tipo, int cantidad) {
        for (int n = 1; n <= cantidad; n++) {
            unidades.add(new UnidadTransporte(codigo(tipo, n), tipo, nodoInicial));
        }
    }

    /** Crea la unidad de una linea que nombra un codigo TTNN concreto. */
    private UnidadTransporte unidadConcreta(String texto, Set<String> codigosUsados,
                                            String archivo, int numeroLinea, String registro) {
        if (resolverTipo(texto) != null) {
            throw error(archivo, numeroLinea, registro, "la declaracion por tipo \"" + texto
                    + "\" necesita ademas la cantidad de unidades, por ejemplo AUTO,10");
        }
        String codigo = texto.toUpperCase(Locale.ROOT);
        if (codigo.length() < 3) {
            throw error(archivo, numeroLinea, registro, "\"" + texto + "\" no es un codigo de unidad TTNN"
                    + " ni una declaracion por tipo, que necesita al menos tipo y cantidad");
        }
        TipoUnidad tipo;
        try {
            tipo = TipoUnidad.porPrefijo(codigo.substring(0, 2));
        } catch (IllegalArgumentException e) {
            throw error(archivo, numeroLinea, registro, "el codigo \"" + texto
                    + "\" no empieza por un prefijo de tipo conocido (TA autos, TM motos, TB bicicletas)");
        }
        for (int i = 2; i < codigo.length(); i++) {
            if (!Character.isDigit(codigo.charAt(i))) {
                throw error(archivo, numeroLinea, registro, "el codigo \"" + texto
                        + "\" debe terminar en el correlativo numerico de la unidad");
            }
        }
        if (!codigosUsados.add(codigo)) {
            throw error(archivo, numeroLinea, registro, "la unidad " + codigo + " ya fue declarada antes");
        }
        return new UnidadTransporte(codigo, tipo, nodoInicial);
    }

    /** Expande una linea por tipo y anota los valores opcionales que declare. */
    private void expandirTipo(String[] campos, List<UnidadTransporte> unidades, Set<String> codigosUsados,
                              int[] correlativo, Map<TipoUnidad, Integer> capacidades,
                              Map<TipoUnidad, Double> velocidades, Map<TipoUnidad, Double> costos,
                              String archivo, int numeroLinea, String registro) {
        if (campos.length > 5) {
            throw error(archivo, numeroLinea, registro, "se esperaban como maximo 5 campos"
                    + " (tipo,cantidad,capacidad,velocidad,costo) y hay " + campos.length);
        }
        TipoUnidad tipo = resolverTipo(campos[0]);
        if (tipo == null) {
            throw error(archivo, numeroLinea, registro, "el tipo de unidad \"" + campos[0].trim()
                    + "\" es desconocido; se admiten AUTO, MOTO y BICICLETA o los prefijos TA, TM y TB");
        }
        int cantidad = entero(campos[1], "la cantidad de unidades", archivo, numeroLinea, registro);
        if (cantidad < 1 || cantidad > CORRELATIVO_MAXIMO) {
            throw error(archivo, numeroLinea, registro, "la cantidad de unidades debe estar entre 1 y "
                    + CORRELATIVO_MAXIMO + " y es " + cantidad);
        }

        if (campos.length >= 3 && !campos[2].isBlank()) {
            int capacidad = entero(campos[2], "la capacidad", archivo, numeroLinea, registro);
            if (capacidad < 1) {
                throw error(archivo, numeroLinea, registro, "la capacidad debe ser positiva y es " + capacidad);
            }
            Integer previa = capacidades.put(tipo, capacidad);
            if (previa != null && previa != capacidad) {
                throw error(archivo, numeroLinea, registro, "el tipo " + tipo.etiqueta()
                        + " ya habia declarado la capacidad " + previa);
            }
        }
        if (campos.length >= 4 && !campos[3].isBlank()) {
            double velocidad = real(campos[3], "la velocidad", archivo, numeroLinea, registro);
            if (velocidad <= 0.0) {
                throw error(archivo, numeroLinea, registro, "la velocidad debe ser positiva y es " + velocidad);
            }
            Double previa = velocidades.put(tipo, velocidad);
            if (previa != null && previa != velocidad) {
                throw error(archivo, numeroLinea, registro, "el tipo " + tipo.etiqueta()
                        + " ya habia declarado la velocidad " + previa);
            }
        }
        if (campos.length >= 5 && !campos[4].isBlank()) {
            double costo = real(campos[4], "el costo por kilometro", archivo, numeroLinea, registro);
            if (costo < 0.0) {
                throw error(archivo, numeroLinea, registro, "el costo por kilometro no puede ser negativo y es " + costo);
            }
            Double previo = costos.put(tipo, costo);
            if (previo != null && previo != costo) {
                throw error(archivo, numeroLinea, registro, "el tipo " + tipo.etiqueta()
                        + " ya habia declarado el costo " + previo);
            }
        }

        for (int i = 0; i < cantidad; i++) {
            String codigo;
            do {
                correlativo[tipo.ordinal()]++;
                if (correlativo[tipo.ordinal()] > CORRELATIVO_MAXIMO) {
                    throw error(archivo, numeroLinea, registro, "se agotaron los correlativos del tipo "
                            + tipo.etiqueta() + ", que llegan hasta " + CORRELATIVO_MAXIMO);
                }
                codigo = codigo(tipo, correlativo[tipo.ordinal()]);
            } while (!codigosUsados.add(codigo)); // Salta los codigos tomados por lineas por unidad.
            unidades.add(new UnidadTransporte(codigo, tipo, nodoInicial));
        }
    }

    /** Codigo TTNN del correlativo dado. */
    private static String codigo(TipoUnidad tipo, int correlativo) {
        return String.format("%s%02d", tipo.prefijo(), correlativo);
    }

    /**
     * Resuelve el tipo por nombre, por nombre en plural o por prefijo, sin distinguir
     * mayusculas. Devuelve {@code null} si no reconoce el texto, para que quien llama
     * construya el error con el contexto de la linea.
     */
    private static TipoUnidad resolverTipo(String texto) {
        String limpio = texto.trim().toUpperCase(Locale.ROOT);
        if (limpio.length() > 2 && limpio.charAt(limpio.length() - 1) == 'S') {
            limpio = limpio.substring(0, limpio.length() - 1);
        }
        for (TipoUnidad tipo : TipoUnidad.values()) {
            if (limpio.equals(tipo.name()) || limpio.equals(tipo.prefijo())) {
                return tipo;
            }
        }
        return null;
    }

    /**
     * Flota leida de un archivo, con los valores por tipo que este declare.
     *
     * <p>Las unidades son objetos mutables que pasan a pertenecer al motor de simulacion.
     * Los tres mapas traen solo los tipos cuyo valor aparece de forma explicita en el
     * archivo; para el resto valen las constantes de {@link TipoUnidad}.</p>
     *
     * @param unidades    unidades creadas, en el orden en que las declara el archivo
     * @param capacidades capacidad en paquetes declarada por tipo
     * @param velocidades velocidad en Km/h declarada por tipo
     * @param costos      costo en soles por kilometro declarado por tipo
     */
    public record FlotaLeida(
            List<UnidadTransporte> unidades,
            Map<TipoUnidad, Integer> capacidades,
            Map<TipoUnidad, Double> velocidades,
            Map<TipoUnidad, Double> costos) {

        public FlotaLeida {
            unidades = List.copyOf(unidades);
            capacidades = Map.copyOf(capacidades);
            velocidades = Map.copyOf(velocidades);
            costos = Map.copyOf(costos);
        }

        /** Cantidad de unidades del tipo dado. */
        public int cantidadDeTipo(TipoUnidad tipo) {
            int total = 0;
            for (UnidadTransporte u : unidades) {
                if (u.tipo() == tipo) {
                    total++;
                }
            }
            return total;
        }

        /** Capacidad vigente del tipo: la declarada por el archivo o la del enunciado. */
        public int capacidad(TipoUnidad tipo) {
            Integer declarada = capacidades.get(tipo);
            return declarada != null ? declarada : tipo.capacidad();
        }

        /** Velocidad vigente del tipo en Km/h: la declarada por el archivo o la del enunciado. */
        public double velocidad(TipoUnidad tipo) {
            Double declarada = velocidades.get(tipo);
            return declarada != null ? declarada : tipo.velocidadPorDefecto();
        }

        /** Costo vigente del tipo en soles por kilometro: el declarado o el del enunciado. */
        public double costoPorKm(TipoUnidad tipo) {
            Double declarado = costos.get(tipo);
            return declarado != null ? declarado : tipo.costoPorKm();
        }

        /**
         * Vuelca sobre los parametros de operacion las velocidades que declare el archivo.
         * Es el unico de los tres valores que el nucleo admite cambiar, y el cambio rige a
         * partir de la siguiente iteracion de planificacion (respuesta 16 del cuestionario).
         */
        public void aplicarVelocidades(ParametrosOperacion parametros) {
            for (Map.Entry<TipoUnidad, Double> entrada : velocidades.entrySet()) {
                parametros.velocidad(entrada.getKey(), entrada.getValue());
            }
        }
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

    /** Lee un real de un campo, con un mensaje que dice cual es el campo culpable. */
    private static double real(String texto, String campo, String archivo, int numeroLinea, String registro) {
        String limpio = texto.trim();
        try {
            double valor = Double.parseDouble(limpio);
            if (!Double.isFinite(valor)) {
                throw new NumberFormatException(limpio);
            }
            return valor;
        } catch (NumberFormatException e) {
            throw error(archivo, numeroLinea, registro, campo + " no es un numero: \"" + limpio + "\"");
        }
    }

    /** Construye el error de formato con el archivo, la linea y el registro completo. */
    private static IllegalArgumentException error(String archivo, int numeroLinea, String registro, String motivo) {
        return new IllegalArgumentException(archivo + ", linea " + numeroLinea + ": " + motivo
                + ". Registro: \"" + registro + "\"");
    }
}
