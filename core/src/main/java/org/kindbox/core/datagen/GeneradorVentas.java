package org.kindbox.core.datagen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.util.Aleatorio;

/**
 * Generador del archivo mensual de ventas del apartado 5 del contexto de dominio.
 *
 * <p>Produce un archivo por mes, llamado {@code ventasAAAAMM}, cuyos registros tienen el
 * formato {@code ##d##h##m:posX,posY,cIdCliente,qq,hl} y salen ordenados por instante de
 * llegada. La generacion es determinista: el mismo {@link PerfilDemanda} y el mismo mes
 * producen siempre byte a byte el mismo archivo, porque la semilla del generador se deriva
 * de la semilla del perfil y del ano y mes pedidos, nunca del reloj. Se usa
 * {@link Aleatorio} y no {@code java.util.Random} para que la reproducibilidad no dependa
 * de la version de la maquina virtual.</p>
 *
 * <p>Las instancias resultantes no son uniformes, que es lo que hace interesante el
 * agrupamiento del planificador:</p>
 * <ul>
 *   <li>la llegada de pedidos sigue un perfil horario con mucha mas actividad entre las
 *       08:00 y las 20:00 que de madrugada, y con fines de semana mas flojos;</li>
 *   <li>una parte de los destinos se reparte de forma uniforme por la ciudad y el resto se
 *       concentra alrededor de los tres almacenes, con una densidad que decae de forma
 *       exponencial con la distancia al almacen;</li>
 *   <li>ningun destino coincide con un nodo de almacen, porque un pedido en la puerta del
 *       almacen no aporta nada al problema de ruteo;</li>
 *   <li>la mayoria de los pedidos son pequenos y hay una cola de pedidos grandes. Solo un
 *       uno por ciento supera los 24 paquetes de capacidad de un auto, de forma deliberada,
 *       para ejercitar la entrega parcial del apartado 5;</li>
 *   <li>los identificadores de cliente salen de un padron fijo {@code cNNNN}, de modo que
 *       los clientes se repiten dentro del mes y entre meses.</li>
 * </ul>
 *
 * <p>La clase no es segura frente a hilos: cada hilo debe usar su propia instancia.</p>
 */
public final class GeneradorVentas {

    /** Tamano del padron de clientes que se reutiliza mes a mes. */
    public static final int CLIENTES_TOTALES = 1500;
    /** Primeros identificadores del padron, que concentran la mitad de los pedidos. */
    public static final int CLIENTES_FRECUENTES = 300;
    /** Cantidad maxima que puede pedir un cliente en un solo pedido. */
    public static final int CANTIDAD_MAXIMA = 40;

    /** Probabilidad de que el pedido lo haga un cliente frecuente. */
    private static final double PROB_CLIENTE_FRECUENTE = 0.55;
    /** Probabilidad de que el destino caiga en el entorno de un almacen y no en toda la ciudad. */
    private static final double PROB_DESTINO_AGRUPADO = 0.60;
    /** Reparto del agrupamiento entre almacen central, nor-oeste y este. */
    private static final double[] PESO_ALMACEN = {0.45, 0.30, 0.25};
    /** Distancia media, en kilometros, de un destino agrupado a su almacen de referencia. */
    private static final double DISPERSION_KM = 6.0;
    /** Intentos de muestreo del destino antes de recurrir al reparto uniforme de respaldo. */
    private static final int INTENTOS_DESTINO = 16;

    /**
     * Peso relativo de cada hora del dia como instante de llegada de un pedido. La jornada
     * comercial de 08:00 a 20:00 concentra alrededor del 80 por ciento de las llegadas.
     */
    private static final double[] PESO_HORA = {
            0.20, 0.15, 0.10, 0.10, 0.15, 0.30, 0.60, 1.10,
            2.20, 2.60, 2.80, 2.70, 2.30, 2.40, 2.70, 2.80,
            2.60, 2.40, 2.10, 1.80, 1.30, 0.90, 0.60, 0.35};

    /** Suma de {@link #PESO_HORA}, precalculada para no recorrerla dos veces por sorteo. */
    private static final double TOTAL_PESO_HORA = sumar(PESO_HORA);

    /** Factor de demanda por dia de la semana, de lunes a domingo. */
    private static final double[] FACTOR_DIA_SEMANA = {1.10, 1.05, 1.05, 1.05, 1.15, 0.80, 0.55};

    /** Amplitud del ruido diario multiplicativo alrededor de la media del perfil. */
    private static final double RUIDO_DIARIO_MINIMO = 0.85;
    private static final double RUIDO_DIARIO_MAXIMO = 1.15;

    /** Bits reservados al indice del pedido al empaquetar la clave de ordenamiento. */
    private static final int BITS_INDICE = 21;
    private static final int MASCARA_INDICE = (1 << BITS_INDICE) - 1;

    private final PerfilDemanda perfil;
    private final YearMonth mesBase;
    private final int[] nodoAlmacen;

    /** Generador con mes base enero de 2026, que es el inicio del periodo pedido. */
    public GeneradorVentas(PerfilDemanda perfil) {
        this(perfil, YearMonth.of(2026, 1));
    }

    /**
     * @param perfil  perfil de demanda a aplicar
     * @param mesBase mes en el que la demanda vale {@code pedidosPorDiaInicial}; el
     *                crecimiento se cuenta a partir de el, tambien hacia atras
     */
    public GeneradorVentas(PerfilDemanda perfil, YearMonth mesBase) {
        this.perfil = perfil;
        this.mesBase = mesBase;
        List<Almacen> almacenes = Almacen.todos();
        this.nodoAlmacen = new int[almacenes.size()];
        for (int i = 0; i < nodoAlmacen.length; i++) {
            nodoAlmacen[i] = almacenes.get(i).nodo();
        }
    }

    /** Nombre del archivo mensual, {@code ventasAAAAMM}. */
    public static String nombreArchivo(YearMonth mes) {
        return String.format("ventas%04d%02d", mes.getYear(), mes.getMonthValue());
    }

    /** Perfil con el que se generan los archivos. */
    public PerfilDemanda perfil() {
        return perfil;
    }

    /** Pedidos diarios esperados en el mes indicado, segun el perfil. */
    public double pedidosPorDiaEsperados(YearMonth mes) {
        return perfil.pedidosPorDia(indiceMes(mes));
    }

    /**
     * Genera los registros del mes, ya ordenados por instante de llegada. Es la operacion
     * que usan las pruebas unitarias cuando no quieren tocar el sistema de archivos.
     */
    public String[] registrosDeMes(YearMonth mes) {
        Aleatorio aleatorio = new Aleatorio(semillaDeMes(mes));
        int dias = mes.lengthOfMonth();

        // Primera pasada: cuantos pedidos llegan cada dia. Se sortean todos antes de
        // generar ningun pedido para que el orden de consumo del generador sea fijo.
        int[] pedidosPorDia = new int[dias];
        double media = perfil.pedidosPorDia(indiceMes(mes));
        int total = 0;
        for (int dia = 0; dia < dias; dia++) {
            int diaSemana = mes.atDay(dia + 1).getDayOfWeek().getValue() - 1;
            double esperados = media * FACTOR_DIA_SEMANA[diaSemana]
                    * aleatorio.siguienteDouble(RUIDO_DIARIO_MINIMO, RUIDO_DIARIO_MAXIMO);
            int cantidad = (int) Math.round(esperados);
            pedidosPorDia[dia] = Math.max(0, cantidad);
            total += pedidosPorDia[dia];
        }
        if (total > MASCARA_INDICE) {
            throw new IllegalStateException("Demasiados pedidos en " + mes + " para el ordenamiento: " + total);
        }

        // Segunda pasada: los pedidos propiamente dichos, en arreglos primitivos paralelos.
        int[] minuto = new int[total];
        int[] nodo = new int[total];
        int[] cliente = new int[total];
        int[] cantidad = new int[total];
        int[] plazo = new int[total];
        int escritos = 0;
        for (int dia = 0; dia < dias; dia++) {
            int base = dia * 1440;
            for (int k = 0; k < pedidosPorDia[dia]; k++) {
                minuto[escritos] = base + sortearHora(aleatorio) * 60 + aleatorio.siguienteEntero(60);
                nodo[escritos] = sortearDestino(aleatorio);
                cliente[escritos] = sortearCliente(aleatorio);
                cantidad[escritos] = sortearCantidad(aleatorio);
                plazo[escritos] = perfil.plazoHoras(aleatorio);
                escritos++;
            }
        }

        // Ordenamiento por instante de llegada. La clave empaqueta minuto e indice en un
        // solo long, de modo que basta un Arrays.sort sobre primitivos.
        long[] clave = new long[total];
        for (int i = 0; i < total; i++) {
            clave[i] = (((long) minuto[i]) << BITS_INDICE) | i;
        }
        Arrays.sort(clave);

        String[] registros = new String[total];
        StringBuilder sb = new StringBuilder(48);
        for (int k = 0; k < total; k++) {
            int i = (int) (clave[k] & MASCARA_INDICE);
            sb.setLength(0);
            escribirInstante(sb, minuto[i]);
            sb.append(':')
                    .append(Ciudad.x(nodo[i])).append(',')
                    .append(Ciudad.y(nodo[i])).append(',');
            escribirCliente(sb, cliente[i]);
            sb.append(',').append(cantidad[i]).append(',').append(plazo[i]);
            registros[k] = sb.toString();
        }
        return registros;
    }

    /**
     * Escribe el archivo {@code ventasAAAAMM} en el directorio indicado, que se crea si no
     * existe, y devuelve cuantos pedidos contiene.
     */
    public int escribirMes(YearMonth mes, Path directorio) throws IOException {
        String[] registros = registrosDeMes(mes);
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

    // ------------------------------------------------------------------ interno

    /** Meses transcurridos desde el mes base, que pueden ser negativos. */
    private int indiceMes(YearMonth mes) {
        return (int) ChronoUnit.MONTHS.between(mesBase, mes);
    }

    /**
     * Semilla del mes. Mezcla la semilla del perfil con el ano y mes mediante la constante
     * de dispersion de SplitMix64, de modo que meses contiguos den corrientes aleatorias
     * sin parecido entre si.
     */
    private long semillaDeMes(YearMonth mes) {
        long codigo = mes.getYear() * 100L + mes.getMonthValue();
        return perfil.semilla() * 0x9E3779B97F4A7C15L + codigo * 0xBF58476D1CE4E5B9L + 0x2545F4914F6CDD1DL;
    }

    /** Sortea la hora de llegada segun el perfil horario. */
    private static int sortearHora(Aleatorio aleatorio) {
        double corte = aleatorio.siguienteDouble() * TOTAL_PESO_HORA;
        double acumulado = 0.0;
        for (int hora = 0; hora < PESO_HORA.length; hora++) {
            acumulado += PESO_HORA[hora];
            if (corte < acumulado) {
                return hora;
            }
        }
        return PESO_HORA.length - 1;
    }

    /**
     * Sortea el nodo de entrega. Con probabilidad {@code PROB_DESTINO_AGRUPADO} el destino
     * cae en el entorno de un almacen, con una distancia que decae de forma exponencial;
     * en otro caso se reparte de forma uniforme por la ciudad. Nunca devuelve un nodo de
     * almacen.
     */
    private int sortearDestino(Aleatorio aleatorio) {
        for (int intento = 0; intento < INTENTOS_DESTINO; intento++) {
            int x;
            int y;
            if (aleatorio.conProbabilidad(PROB_DESTINO_AGRUPADO)) {
                int almacen = nodoAlmacen[aleatorio.ruleta(PESO_ALMACEN)];
                x = Ciudad.x(almacen) + desplazamiento(aleatorio);
                y = Ciudad.y(almacen) + desplazamiento(aleatorio);
            } else {
                x = aleatorio.siguienteEntero(0, Ciudad.LARGO_KM);
                y = aleatorio.siguienteEntero(0, Ciudad.ANCHO_KM);
            }
            if (!Ciudad.dentro(x, y)) {
                continue;
            }
            int nodo = Ciudad.nodo(x, y);
            if (!esAlmacen(nodo)) {
                return nodo;
            }
        }
        // Respaldo uniforme: la ciudad tiene 3 621 nodos y solo tres son almacenes, de modo
        // que este bucle termina de inmediato.
        int nodo;
        do {
            nodo = Ciudad.nodo(aleatorio.siguienteEntero(0, Ciudad.LARGO_KM),
                    aleatorio.siguienteEntero(0, Ciudad.ANCHO_KM));
        } while (esAlmacen(nodo));
        return nodo;
    }

    /**
     * Desplazamiento en un eje respecto del almacen de referencia. Se muestrea una
     * exponencial de media {@code DISPERSION_KM} y se le asigna signo, con lo que la
     * densidad de destinos decae con la distancia al almacen.
     */
    private static int desplazamiento(Aleatorio aleatorio) {
        double u = aleatorio.siguienteDouble();
        int magnitud = (int) Math.round(-DISPERSION_KM * Math.log(1.0 - u));
        return aleatorio.conProbabilidad(0.5) ? -magnitud : magnitud;
    }

    private boolean esAlmacen(int nodo) {
        for (int n : nodoAlmacen) {
            if (n == nodo) {
                return true;
            }
        }
        return false;
    }

    /** Sortea el numero de cliente dentro del padron, con sesgo hacia los frecuentes. */
    private static int sortearCliente(Aleatorio aleatorio) {
        return aleatorio.conProbabilidad(PROB_CLIENTE_FRECUENTE)
                ? aleatorio.siguienteEntero(1, CLIENTES_FRECUENTES)
                : aleatorio.siguienteEntero(CLIENTES_FRECUENTES + 1, CLIENTES_TOTALES);
    }

    /**
     * Sortea la cantidad de unidades del producto P. La mezcla da una media de unas
     * {@value PerfilDemanda#UNIDADES_MEDIAS_POR_PEDIDO} unidades por pedido: el 55 por
     * ciento son pedidos de una o dos unidades y solo el uno por ciento supera los 24
     * paquetes que caben en un auto, caso en el que la entrega parcial es obligatoria.
     */
    private static int sortearCantidad(Aleatorio aleatorio) {
        double u = aleatorio.siguienteDouble();
        if (u < 0.55) {
            return aleatorio.siguienteEntero(1, 2);
        }
        if (u < 0.82) {
            return aleatorio.siguienteEntero(3, 5);
        }
        if (u < 0.95) {
            return aleatorio.siguienteEntero(6, 10);
        }
        if (u < 0.99) {
            return aleatorio.siguienteEntero(11, 24);
        }
        return aleatorio.siguienteEntero(25, CANTIDAD_MAXIMA);
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

    /** Escribe el identificador de cliente en el formato {@code cNNNN}. */
    private static void escribirCliente(StringBuilder sb, int numero) {
        sb.append('c');
        if (numero < 1000) {
            sb.append('0');
        }
        if (numero < 100) {
            sb.append('0');
        }
        if (numero < 10) {
            sb.append('0');
        }
        sb.append(numero);
    }

    private static double sumar(double[] valores) {
        double total = 0.0;
        for (double v : valores) {
            total += v;
        }
        return total;
    }

    private static void dosDigitos(StringBuilder sb, int valor) {
        if (valor < 10) {
            sb.append('0');
        }
        sb.append(valor);
    }
}
