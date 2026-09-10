package org.kindbox.experiments;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Ejecutable que produce el juego de datos sinteticos completo del proyecto.
 *
 * <p>Cubre por defecto del 01 de enero de 2026 al 31 de diciembre de 2028, que es el
 * periodo que pide el equipo docente, y deja bajo el directorio raiz la estructura que
 * consumen los lectores del modulo core:</p>
 * <pre>
 *   &lt;raiz&gt;/ventas/ventasAAAAMM
 *   &lt;raiz&gt;/bloqueos/AAAAMM.bloqueadas
 *   &lt;raiz&gt;/mantenimiento/AAAA/mant.preventivo.MM.MM
 *   &lt;raiz&gt;/flota.txt
 *   &lt;raiz&gt;/prueba/...  juego minimo para las pruebas unitarias
 * </pre>
 *
 * <p>Los archivos de mantenimiento van en un subdirectorio por ano porque su nombre,
 * {@code mant.preventivo.MM.MM}, no lleva el ano y tres anos de generacion producirian
 * colisiones en un unico directorio.</p>
 *
 * <p>El juego reducido de {@code <raiz>/prueba/} tiene un solo mes con unas pocas decenas
 * de pedidos y tres bloqueos. Es el que usan las pruebas unitarias y la verificacion de
 * equivalencia del apartado 12.4 del ISA, donde hace falta un caso lo bastante pequeno
 * para revisarlo a mano y estrictamente reproducible.</p>
 *
 * <p>Uso:</p>
 * <pre>
 *   java org.kindbox.experiments.GenerarDatos [directorio] [fechaInicial] [fechaFinal] [perfil]
 *   java org.kindbox.experiments.GenerarDatos data 2026-01-01 2028-12-31 CRECIENTE
 * </pre>
 *
 * <p>El rango se redondea a meses completos, porque los archivos del curso son mensuales y
 * porque asi el archivo de un mes no depende de con que dia se pidio la generacion.</p>
 */
public final class GenerarDatos {

    /** Directorio de salida por defecto. */
    private static final String DIRECTORIO_POR_DEFECTO = "data";
    /** Primer dia del periodo que pide el equipo docente. */
    private static final LocalDate FECHA_INICIAL_POR_DEFECTO = LocalDate.of(2026, 1, 1);
    /** Ultimo dia del periodo que pide el equipo docente. */
    private static final LocalDate FECHA_FINAL_POR_DEFECTO = LocalDate.of(2028, 12, 31);
    /** Perfil de demanda por defecto, el que lleva al colapso dentro del periodo. */
    private static final String PERFIL_POR_DEFECTO = "CRECIENTE";

    /** Semilla de los bloqueos del juego completo. */
    private static final long SEMILLA_BLOQUEOS = 20260601L;
    /** Semilla de los bloqueos del juego reducido de pruebas. */
    private static final long SEMILLA_BLOQUEOS_PRUEBA = 424242L;
    /** Mes del juego reducido de pruebas. */
    private static final YearMonth MES_PRUEBA = YearMonth.of(2026, 1);
    /** Bloqueos del juego reducido de pruebas. */
    private static final int BLOQUEOS_PRUEBA = 3;

    /** Unidades de la flota real del proyecto, segun la hoja de flota. */
    private static final int AUTOS = 10;
    private static final int MOTOS = 15;
    private static final int BICICLETAS = 12;

    private GenerarDatos() {
    }

    public static void main(String[] args) throws IOException {
        Path raiz = Path.of(args.length > 0 ? args[0] : DIRECTORIO_POR_DEFECTO);
        LocalDate desde = fecha(args, 1, FECHA_INICIAL_POR_DEFECTO);
        LocalDate hasta = fecha(args, 2, FECHA_FINAL_POR_DEFECTO);
        PerfilDemanda perfil = PerfilDemanda.porNombre(args.length > 3 ? args[3] : PERFIL_POR_DEFECTO);
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final " + hasta + " es anterior a la inicial " + desde);
        }

        YearMonth primerMes = YearMonth.from(desde);
        YearMonth ultimoMes = YearMonth.from(hasta);
        int meses = (int) ChronoUnit.MONTHS.between(primerMes, ultimoMes) + 1;

        System.out.println("KindBox - generacion del juego de datos de PaqRap");
        System.out.printf(Locale.ROOT, "  raiz             : %s%n", raiz.toAbsolutePath());
        System.out.printf(Locale.ROOT, "  rango            : %s a %s (%d meses completos)%n", desde, hasta, meses);
        System.out.printf(Locale.ROOT, "  perfil de demanda: %s%n", perfil.descripcion());
        System.out.printf(Locale.ROOT, "  capacidad flota  : %d unidades/dia en el dia extremo%n",
                PerfilDemanda.CAPACIDAD_DIARIA_MAXIMA_FLOTA);
        int mesSaturacion = perfil.primerMesDeSaturacion();
        if (mesSaturacion >= 0) {
            System.out.printf(Locale.ROOT, "  colapso previsto : mes %d del perfil, es decir %s%n",
                    mesSaturacion + 1, YearMonth.of(2026, 1).plusMonths(mesSaturacion));
        } else {
            System.out.println("  colapso previsto : ninguno, la demanda no llega a saturar la flota");
        }
        System.out.println();

        int archivos = 0;
        archivos += generarJuego(raiz, primerMes, ultimoMes, perfil, SEMILLA_BLOQUEOS,
                GeneradorBloqueos.BLOQUEOS_POR_DIA_POR_DEFECTO, -1);

        System.out.println();
        System.out.println("Juego reducido para pruebas unitarias y para el apartado 12.4 del ISA");
        archivos += generarJuego(raiz.resolve("prueba"), MES_PRUEBA, MES_PRUEBA, PerfilDemanda.LIGERO,
                SEMILLA_BLOQUEOS_PRUEBA, 0.0, BLOQUEOS_PRUEBA);

        System.out.println();
        System.out.printf(Locale.ROOT, "Ficheros escritos: %d%n", archivos);
    }

    /**
     * Genera un juego de datos completo bajo la raiz indicada y devuelve cuantos ficheros
     * escribio. Con {@code bloqueosFijos} negativo se usa la densidad diaria de bloqueos;
     * con un valor no negativo se escriben exactamente esos bloqueos en el mes.
     */
    private static int generarJuego(Path raiz, YearMonth primerMes, YearMonth ultimoMes,
            PerfilDemanda perfil, long semillaBloqueos, double bloqueosPorDia,
            int bloqueosFijos) throws IOException {
        Path dirVentas = raiz.resolve("ventas");
        Path dirBloqueos = raiz.resolve("bloqueos");
        Path dirMantenimiento = raiz.resolve("mantenimiento");
        Files.createDirectories(dirVentas);
        Files.createDirectories(dirBloqueos);
        Files.createDirectories(dirMantenimiento);

        GeneradorVentas generadorVentas = new GeneradorVentas(perfil, YearMonth.of(2026, 1));
        GeneradorBloqueos generadorBloqueos = new GeneradorBloqueos(semillaBloqueos, bloqueosPorDia);

        System.out.printf(Locale.ROOT, "  %-9s %-16s %10s %10s%n", "mes", "ventas", "pedidos", "bloqueos");
        int archivos = 0;
        long totalPedidos = 0;
        long totalBloqueos = 0;
        for (YearMonth mes = primerMes; !mes.isAfter(ultimoMes); mes = mes.plusMonths(1)) {
            int pedidos = generadorVentas.escribirMes(mes, dirVentas);
            int bloqueos = bloqueosFijos >= 0
                    ? generadorBloqueos.escribirMes(mes, bloqueosFijos, dirBloqueos)
                    : generadorBloqueos.escribirMes(mes, dirBloqueos);
            archivos += 2;
            totalPedidos += pedidos;
            totalBloqueos += bloqueos;
            System.out.printf(Locale.ROOT, "  %-9s %-16s %10s %10s%n",
                    mes, GeneradorVentas.nombreArchivo(mes), formato(pedidos), formato(bloqueos));
        }

        int entradasMantenimiento = 0;
        List<YearMonth> pares = GeneradorMantenimiento.paresQueCubren(primerMes, ultimoMes);
        for (YearMonth par : pares) {
            Path dirAno = dirMantenimiento.resolve(String.valueOf(par.getYear()));
            entradasMantenimiento += GeneradorMantenimiento.escribirPar(par, dirAno);
            archivos++;
        }

        escribirFlota(raiz.resolve("flota.txt"));
        archivos++;

        System.out.printf(Locale.ROOT, "  total: %s pedidos, %s bloqueos, %s mantenimientos en %d archivos de plan%n",
                formato(totalPedidos), formato(totalBloqueos), formato(entradasMantenimiento), pares.size());
        return archivos;
    }

    /**
     * Escribe la flota real del proyecto: 10 autos, 15 motos y 12 bicicletas. Se emite una
     * linea por tipo, con la misma informacion que la hoja de flota del equipo docente, y
     * con las velocidades del enunciado, que son las que adopta el modelo. Las lineas que
     * empiezan por almohadilla son comentarios de cabecera.
     */
    private static void escribirFlota(Path destino) throws IOException {
        int[] unidadesPorTipo = new int[TipoUnidad.values().length];
        unidadesPorTipo[TipoUnidad.AUTO.ordinal()] = AUTOS;
        unidadesPorTipo[TipoUnidad.MOTO.ordinal()] = MOTOS;
        unidadesPorTipo[TipoUnidad.BICICLETA.ordinal()] = BICICLETAS;

        Files.createDirectories(destino.toAbsolutePath().getParent());
        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.US_ASCII)) {
            escritor.write("# Flota de PaqRap. Codigos TTNN: TA autos, TM motos, TB bicicletas.\n");
            escritor.write("# Formato: TT,cantidad,capacidad,velocidadKmH,costoPorKm\n");
            escritor.write("# Velocidades del enunciado: autos 40, motos 25 y bicicletas 12 Km/h.\n");
            for (TipoUnidad tipo : TipoUnidad.values()) {
                escritor.write(String.format(Locale.ROOT, "%s,%d,%d,%.1f,%.2f",
                        tipo.prefijo(), unidadesPorTipo[tipo.ordinal()], tipo.capacidad(),
                        tipo.velocidadPorDefecto(), tipo.costoPorKm()));
                escritor.write('\n');
            }
        }
    }

    /** Lee una fecha ISO de los argumentos, con un valor por defecto. */
    private static LocalDate fecha(String[] args, int posicion, LocalDate porDefecto) {
        if (args.length <= posicion) {
            return porDefecto;
        }
        try {
            return LocalDate.parse(args[posicion]);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Fecha invalida: " + args[posicion] + ". Se espera el formato aaaa-mm-dd", e);
        }
    }

    private static String formato(long valor) {
        return String.format(Locale.ROOT, "%,d", valor);
    }
}
