package org.kindbox.core.datagen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.util.Aleatorio;

/**
 * Generador del archivo mensual de bloqueos del apartado 6 del contexto de dominio.
 *
 * <p>Produce un archivo por mes, llamado {@code AAAAMM.bloqueadas}, cuyos registros tienen
 * el formato {@code ##d##h##m-##d##h##m:x1,y1,x2,y2,...,xn,yn}: la ventana de vigencia y a
 * continuacion la poligonal de nodos cuyos tramos consecutivos quedan intransitables en
 * ambos sentidos, porque todas las calles son de doble sentido.</p>
 *
 * <p>Se respetan las tres reglas que el curso 1INF54 2026-2 impone a los bloqueos:</p>
 * <ol>
 *   <li><b>Solo poligonales abiertas</b>, de entre {@value #NODOS_MINIMOS} y
 *       {@value #NODOS_MAXIMOS} nodos, con nodos consecutivos adyacentes en la reticula y
 *       sin repetir ningun nodo. Al no cerrarse nunca sobre si misma, la poligonal no
 *       encierra ninguna region y todo nodo de la ciudad sigue siendo alcanzable.</li>
 *   <li><b>Ninguna arista incidente a un almacen se bloquea.</b> Una arista es incidente a
 *       un almacen justo cuando uno de sus extremos es el nodo del almacen, de modo que
 *       basta con excluir los tres nodos de almacen del recorrido para garantizarlo y
 *       ningun almacen queda aislado.</li>
 *   <li><b>Ventanas de entre 2 y 48 horas</b>, varias por dia. Como los instantes de
 *       inicio se sortean dentro de cada dia y las duraciones son largas, los solapes
 *       aparecen solos y el planificador debe lidiar con varios bloqueos simultaneos.</li>
 * </ol>
 *
 * <p>La generacion es determinista a partir de la semilla: la misma semilla y el mismo mes
 * producen siempre el mismo archivo. Una ventana que se saldria del mes se recorta al
 * ultimo minuto del mes, porque cada archivo describe un unico mes calendario.</p>
 *
 * <p>La clase no es segura frente a hilos: mantiene una marca de nodos visitados que se
 * reutiliza entre poligonales para no reservar memoria en cada intento.</p>
 */
public final class GeneradorBloqueos {

    /** Bloqueos que arrancan cada dia con la configuracion por defecto. */
    public static final double BLOQUEOS_POR_DIA_POR_DEFECTO = 3.0;
    /** Numero minimo de nodos de una poligonal. */
    public static final int NODOS_MINIMOS = 2;
    /** Numero maximo de nodos de una poligonal. */
    public static final int NODOS_MAXIMOS = 12;
    /** Duracion minima de una ventana de vigencia, en minutos (2 horas). */
    public static final int DURACION_MINIMA_MIN = 120;
    /** Duracion maxima de una ventana de vigencia, en minutos (48 horas). */
    public static final int DURACION_MAXIMA_MIN = 2880;

    /** Probabilidad de que la poligonal continue en la misma direccion que traia. */
    private static final double PROB_SEGUIR_RECTO = 0.65;
    /** Intentos de construccion de una poligonal antes de aceptar la mas corta posible. */
    private static final int INTENTOS_POLIGONAL = 32;
    /** Desplazamientos de los cuatro vecinos de un nodo de la reticula. */
    private static final int[] PASO_X = {1, -1, 0, 0};
    private static final int[] PASO_Y = {0, 0, 1, -1};

    private static final int BITS_INDICE = 20;
    private static final int MASCARA_INDICE = (1 << BITS_INDICE) - 1;

    private final long semilla;
    private final double bloqueosPorDia;
    private final int[] nodoAlmacen;

    /** Marca de visitados por nodo. Se compara con {@link #selloActual} para no limpiarla. */
    private final int[] sello = new int[Ciudad.TOTAL_NODOS];
    private int selloActual;

    /** Generador con la densidad por defecto de {@value #BLOQUEOS_POR_DIA_POR_DEFECTO} bloqueos diarios. */
    public GeneradorBloqueos(long semilla) {
        this(semilla, BLOQUEOS_POR_DIA_POR_DEFECTO);
    }

    /**
     * @param semilla        semilla maestra de la generacion
     * @param bloqueosPorDia bloqueos que arrancan cada dia en promedio
     */
    public GeneradorBloqueos(long semilla, double bloqueosPorDia) {
        if (bloqueosPorDia < 0.0 || !Double.isFinite(bloqueosPorDia)) {
            throw new IllegalArgumentException("Densidad de bloqueos invalida: " + bloqueosPorDia);
        }
        this.semilla = semilla;
        this.bloqueosPorDia = bloqueosPorDia;
        List<Almacen> almacenes = Almacen.todos();
        this.nodoAlmacen = new int[almacenes.size()];
        for (int i = 0; i < nodoAlmacen.length; i++) {
            nodoAlmacen[i] = almacenes.get(i).nodo();
        }
    }

    /** Nombre del archivo mensual, {@code AAAAMM.bloqueadas}. */
    public static String nombreArchivo(YearMonth mes) {
        return String.format("%04d%02d.bloqueadas", mes.getYear(), mes.getMonthValue());
    }

    /**
     * Genera los registros del mes, ordenados por instante de inicio. El numero de bloqueos
     * de cada dia se sortea alrededor de la densidad configurada.
     */
    public String[] registrosDeMes(YearMonth mes) {
        Aleatorio aleatorio = new Aleatorio(semillaDeMes(mes));
        int dias = mes.lengthOfMonth();

        // Primera pasada: cuantos bloqueos arrancan cada dia, para dimensionar los arreglos
        // sin usar colecciones que crezcan.
        int[] porDia = new int[dias];
        int total = 0;
        for (int dia = 0; dia < dias; dia++) {
            porDia[dia] = sortearCantidadDelDia(aleatorio);
            total += porDia[dia];
        }

        int[] inicio = new int[total];
        int escritos = 0;
        for (int dia = 0; dia < dias; dia++) {
            for (int k = 0; k < porDia[dia]; k++) {
                inicio[escritos++] = dia * 1440 + aleatorio.siguienteEntero(0, 1439);
            }
        }
        return construir(aleatorio, inicio, dias);
    }

    /**
     * Genera exactamente {@code cantidad} bloqueos repartidos por todo el mes. Es la
     * variante que alimenta el juego de datos reducido de las pruebas unitarias, donde
     * interesa un numero fijo y pequeno de bloqueos.
     */
    public String[] registrosDeMes(YearMonth mes, int cantidad) {
        if (cantidad < 0) {
            throw new IllegalArgumentException("Cantidad de bloqueos negativa: " + cantidad);
        }
        Aleatorio aleatorio = new Aleatorio(semillaDeMes(mes));
        int dias = mes.lengthOfMonth();
        int[] inicio = new int[cantidad];
        for (int i = 0; i < cantidad; i++) {
            inicio[i] = aleatorio.siguienteEntero(0, dias * 1440 - DURACION_MINIMA_MIN);
        }
        return construir(aleatorio, inicio, dias);
    }

    /**
     * Escribe el archivo {@code AAAAMM.bloqueadas} en el directorio indicado, que se crea
     * si no existe, y devuelve cuantos bloqueos contiene.
     */
    public int escribirMes(YearMonth mes, Path directorio) throws IOException {
        return escribir(mes, registrosDeMes(mes), directorio);
    }

    /** Variante de {@link #escribirMes(YearMonth, Path)} con un numero fijo de bloqueos. */
    public int escribirMes(YearMonth mes, int cantidad, Path directorio) throws IOException {
        return escribir(mes, registrosDeMes(mes, cantidad), directorio);
    }

    // ------------------------------------------------------------------ interno

    private int escribir(YearMonth mes, String[] registros, Path directorio) throws IOException {
        Files.createDirectories(directorio);
        Path destino = directorio.resolve(nombreArchivo(mes));
        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.US_ASCII)) {
            for (String registro : registros) {
                escritor.write(registro);
                // Salto de linea explicito para que el archivo no dependa del sistema.
                escritor.write('\n');
            }
        }
        return registros.length;
    }

    /**
     * Construye un registro por cada instante de inicio dado y los devuelve ordenados por
     * ese instante.
     */
    private String[] construir(Aleatorio aleatorio, int[] inicio, int dias) {
        int total = inicio.length;
        if (total > MASCARA_INDICE) {
            throw new IllegalStateException("Demasiados bloqueos para el ordenamiento: " + total);
        }
        int ultimoMinuto = dias * 1440 - 1;
        String[] sinOrdenar = new String[total];
        long[] clave = new long[total];
        int[] nodos = new int[NODOS_MAXIMOS];
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < total; i++) {
            // Un bloqueo que arrancase en los ultimos minutos del mes daria una ventana mas
            // corta que el minimo al recortarla, de modo que se adelanta su comienzo.
            int comienzo = Math.min(inicio[i], ultimoMinuto - DURACION_MINIMA_MIN);
            int duracion = aleatorio.siguienteEntero(DURACION_MINIMA_MIN, DURACION_MAXIMA_MIN);
            int fin = Math.min(comienzo + duracion, ultimoMinuto);
            int cantidadNodos = trazarPoligonal(aleatorio, nodos);
            sinOrdenar[i] = registro(sb, comienzo, fin, nodos, cantidadNodos);
            clave[i] = (((long) comienzo) << BITS_INDICE) | i;
        }
        Arrays.sort(clave);
        String[] ordenados = new String[total];
        for (int k = 0; k < total; k++) {
            ordenados[k] = sinOrdenar[(int) (clave[k] & MASCARA_INDICE)];
        }
        return ordenados;
    }

    /** Numero de bloqueos que arrancan en un dia, alrededor de la densidad configurada. */
    private int sortearCantidadDelDia(Aleatorio aleatorio) {
        int base = (int) Math.floor(bloqueosPorDia);
        if (aleatorio.conProbabilidad(bloqueosPorDia - base)) {
            base++;
        }
        return Math.max(0, base + aleatorio.siguienteEntero(-1, 1));
    }

    /**
     * Traza una poligonal abierta por caminata aleatoria sobre la reticula y devuelve
     * cuantos nodos ocupa de {@code nodos}. La caminata prefiere seguir recta, de modo que
     * el bloqueo se parezca a un tramo de calle cerrado por la municipalidad y no a una
     * mancha; nunca visita dos veces el mismo nodo ni pisa un nodo de almacen.
     */
    private int trazarPoligonal(Aleatorio aleatorio, int[] nodos) {
        for (int intento = 0; intento < INTENTOS_POLIGONAL; intento++) {
            selloActual++;
            int objetivo = aleatorio.siguienteEntero(NODOS_MINIMOS, NODOS_MAXIMOS);
            int x = aleatorio.siguienteEntero(0, Ciudad.LARGO_KM);
            int y = aleatorio.siguienteEntero(0, Ciudad.ANCHO_KM);
            if (esAlmacen(x, y)) {
                continue;
            }
            nodos[0] = Ciudad.nodo(x, y);
            sello[nodos[0]] = selloActual;
            int cantidad = 1;
            int direccion = aleatorio.siguienteEntero(4);
            while (cantidad < objetivo) {
                int elegida = -1;
                if (aleatorio.conProbabilidad(PROB_SEGUIR_RECTO)
                        && transitable(x + PASO_X[direccion], y + PASO_Y[direccion])) {
                    elegida = direccion;
                } else {
                    // Se prueban las cuatro direcciones a partir de una al azar, de modo que
                    // el giro no tenga sesgo hacia ningun eje.
                    int primera = aleatorio.siguienteEntero(4);
                    for (int k = 0; k < 4; k++) {
                        int candidata = (primera + k) & 3;
                        if (transitable(x + PASO_X[candidata], y + PASO_Y[candidata])) {
                            elegida = candidata;
                            break;
                        }
                    }
                }
                if (elegida < 0) {
                    break;
                }
                direccion = elegida;
                x += PASO_X[direccion];
                y += PASO_Y[direccion];
                nodos[cantidad] = Ciudad.nodo(x, y);
                sello[nodos[cantidad]] = selloActual;
                cantidad++;
            }
            if (cantidad >= NODOS_MINIMOS) {
                return cantidad;
            }
        }
        // Respaldo: un unico tramo horizontal en una zona libre de almacenes. Solo se llega
        // aqui si la caminata queda encerrada muchas veces seguidas, lo que no ocurre en una
        // reticula de 71 por 51 nodos, pero el generador no puede devolver nada invalido.
        nodos[0] = Ciudad.nodo(0, 0);
        nodos[1] = Ciudad.nodo(1, 0);
        return 2;
    }

    /** Indica si el nodo puede incorporarse a la poligonal en curso. */
    private boolean transitable(int x, int y) {
        if (!Ciudad.dentro(x, y)) {
            return false;
        }
        if (esAlmacen(x, y)) {
            return false;
        }
        return sello[Ciudad.nodo(x, y)] != selloActual;
    }

    private boolean esAlmacen(int x, int y) {
        int nodo = Ciudad.nodo(x, y);
        for (int n : nodoAlmacen) {
            if (n == nodo) {
                return true;
            }
        }
        return false;
    }

    /** Arma el registro {@code ##d##h##m-##d##h##m:x1,y1,...,xn,yn}. */
    private static String registro(StringBuilder sb, int inicio, int fin, int[] nodos, int cantidad) {
        sb.setLength(0);
        escribirInstante(sb, inicio);
        sb.append('-');
        escribirInstante(sb, fin);
        sb.append(':');
        for (int i = 0; i < cantidad; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Ciudad.x(nodos[i])).append(',').append(Ciudad.y(nodos[i]));
        }
        return sb.toString();
    }

    /** Escribe el instante en el formato {@code ##d##h##m}, con el dia empezando en 1. */
    private static void escribirInstante(StringBuilder sb, int minutoDelMes) {
        int dia = minutoDelMes / 1440 + 1;
        int minutoDelDia = minutoDelMes % 1440;
        dosDigitos(sb, dia);
        sb.append('d');
        dosDigitos(sb, minutoDelDia / 60);
        sb.append('h');
        dosDigitos(sb, minutoDelDia % 60);
        sb.append('m');
    }

    private static void dosDigitos(StringBuilder sb, int valor) {
        if (valor < 10) {
            sb.append('0');
        }
        sb.append(valor);
    }

    /**
     * Semilla del mes. Mezcla la semilla maestra con el ano y mes mediante las constantes
     * de dispersion de SplitMix64, de modo que meses contiguos den bloqueos sin parecido.
     */
    private long semillaDeMes(YearMonth mes) {
        long codigo = mes.getYear() * 100L + mes.getMonthValue();
        return semilla * 0x9E3779B97F4A7C15L + codigo * 0x94D049BB133111EBL + 0x165667B19E3779F9L;
    }
}
