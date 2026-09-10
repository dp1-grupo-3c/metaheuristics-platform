package org.kindbox.core.datagen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Averia;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.simulacion.GeneradorAverias;

/**
 * Generador de archivos de averias de ejemplo, en el formato adoptado en el apartado 7 del
 * contexto de dominio.
 *
 * <p>Produce un archivo por mes, llamado {@code averiasAAAAMM}, cuyos registros tienen el
 * formato {@code ##d##h##m:TTNN:T} que lee {@code LectorAverias}: el instante del
 * incidente, el codigo de la unidad y el tipo 1, 2 o 3. Su razon de ser es poder ejercitar
 * de verdad la <b>carga masiva de archivo</b> que la respuesta 3 del cuestionario atribuye
 * al visualizador, sin esperar a que una corrida real produzca averias.</p>
 *
 * <p>Las averias no se inventan aqui: se piden a {@link GeneradorAverias}, que es donde
 * viven las reglas del motor de simulacion. De ese modo el archivo de ejemplo y las averias
 * que la simulacion genera por su cuenta obedecen exactamente al mismo reparto por tipo de
 * unidad y por tipo de averia, y una discrepancia entre ambos caminos seria un defecto y no
 * una diferencia de criterio.</p>
 *
 * <p><b>Hipotesis del archivo.</b> Un archivo no conoce el estado de la flota, de modo que
 * se supone que <b>todas las unidades estan en ruta durante todos los turnos del mes</b>,
 * que es la hipotesis mas exigente posible: en una corrida real parte de la flota esta
 * parada en almacen, en mantenimiento o ya averiada y no entra en el sorteo. Quien quiera
 * un archivo menos cargado baja la probabilidad por unidad y por turno del constructor.</p>
 *
 * <p>El reloj del archivo arranca a las 00:00 del dia 1 del mes, igual que en ventas y
 * bloqueos, y se recorren todos los turnos que tocan el mes; el turno de noche que arranca
 * el ultimo dia del mes anterior se recorre tambien, porque parte de el cae dentro del mes,
 * pero sus averias anteriores al minuto cero se descartan.</p>
 *
 * <p>La generacion es determinista a partir de la semilla: la misma semilla, el mismo mes y
 * la misma lista de unidades producen siempre el mismo archivo.</p>
 */
public final class GeneradorArchivoAverias {

    /** Probabilidad por unidad y por turno por defecto, la misma que usa el escenario 5D. */
    public static final double PROBABILIDAD_POR_DEFECTO = 0.02;

    /** Autos de la flota del enunciado. */
    public static final int AUTOS = 10;
    /** Motos de la flota del enunciado. */
    public static final int MOTOS = 15;
    /** Bicicletas de la flota del enunciado. */
    public static final int BICICLETAS = 12;

    private final GeneradorAverias reglas;

    /** Generador con la probabilidad por defecto de {@value #PROBABILIDAD_POR_DEFECTO}. */
    public GeneradorArchivoAverias(long semilla) {
        this(semilla, PROBABILIDAD_POR_DEFECTO);
    }

    /**
     * @param semilla                       semilla maestra de la generacion
     * @param probabilidadPorUnidadPorTurno probabilidad de referencia por unidad y turno
     */
    public GeneradorArchivoAverias(long semilla, double probabilidadPorUnidadPorTurno) {
        this(new GeneradorAverias(semilla, probabilidadPorUnidadPorTurno));
    }

    /** Generador que reutiliza unas reglas ya configuradas. */
    public GeneradorArchivoAverias(GeneradorAverias reglas) {
        if (reglas == null) {
            throw new IllegalArgumentException("El generador de archivos necesita las reglas de averias");
        }
        this.reglas = reglas;
    }

    /** Reglas de generacion que aplica el archivo. */
    public GeneradorAverias reglas() {
        return reglas;
    }

    /** Nombre del archivo mensual, {@code averiasAAAAMM}. */
    public static String nombreArchivo(YearMonth mes) {
        return String.format("averias%04d%02d", mes.getYear(), mes.getMonthValue());
    }

    /**
     * Codigos de la flota del enunciado: {@code TA01..TA10}, {@code TM01..TM15} y
     * {@code TB01..TB12}, en ese orden.
     */
    public static List<String> codigosDeLaFlota() {
        return codigosDeLaFlota(AUTOS, MOTOS, BICICLETAS);
    }

    /** Codigos correlativos {@code TTNN} de una flota con las cantidades indicadas. */
    public static List<String> codigosDeLaFlota(int autos, int motos, int bicicletas) {
        List<String> codigos = new ArrayList<>(autos + motos + bicicletas);
        agregar(codigos, TipoUnidad.AUTO, autos);
        agregar(codigos, TipoUnidad.MOTO, motos);
        agregar(codigos, TipoUnidad.BICICLETA, bicicletas);
        return codigos;
    }

    /**
     * Genera los registros del mes para la flota del enunciado, ya ordenados por instante.
     * Es la operacion que usan las pruebas unitarias cuando no quieren tocar el sistema de
     * archivos.
     */
    public String[] registrosDeMes(YearMonth mes) {
        return registrosDeMes(mes, codigosDeLaFlota());
    }

    /**
     * Genera los registros del mes para la lista de unidades dada, ordenados por instante y,
     * a igualdad de instante, por codigo de unidad.
     *
     * @param mes            mes que cubre el archivo
     * @param codigosUnidad  codigos TTNN de las unidades que pueden averiarse
     */
    public String[] registrosDeMes(YearMonth mes, List<String> codigosUnidad) {
        if (codigosUnidad == null || codigosUnidad.isEmpty()) {
            return new String[0];
        }
        List<UnidadTransporte> unidades = unidadesEnRuta(codigosUnidad);
        int minutosDelMes = mes.lengthOfMonth() * 1440;

        List<String> registros = new ArrayList<>();
        StringBuilder sb = new StringBuilder(24);
        // Se arranca en el turno que contiene al minuto cero, que es el de noche iniciado el
        // ultimo dia del mes anterior, y se avanza turno a turno hasta salir del mes.
        for (long inicio = Turno.inicioDelTurno(0L); inicio < minutosDelMes; inicio += Turno.DURACION_MIN) {
            for (Averia averia : reglas.averiasDelTurno(inicio, unidades)) {
                long minuto = averia.minutoAveria();
                // La parte del primer turno anterior al dia 1 no pertenece a este archivo.
                if (minuto < 0 || minuto >= minutosDelMes) {
                    continue;
                }
                registros.add(registro(sb, (int) minuto, averia.codigoUnidad(), averia.tipo().codigo()));
            }
        }
        return registros.toArray(new String[0]);
    }

    /**
     * Escribe el archivo {@code averiasAAAAMM} de la flota del enunciado en el directorio
     * indicado, que se crea si no existe, y devuelve cuantas averias contiene.
     */
    public int escribirMes(YearMonth mes, Path directorio) throws IOException {
        return escribirMes(mes, codigosDeLaFlota(), directorio);
    }

    /** Variante de {@link #escribirMes(YearMonth, Path)} con una flota explicita. */
    public int escribirMes(YearMonth mes, List<String> codigosUnidad, Path directorio) throws IOException {
        String[] registros = registrosDeMes(mes, codigosUnidad);
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

    /**
     * Unidades de trabajo en estado {@link EstadoUnidad#EN_RUTA}, que es el unico estado que
     * admite la regla 1 de {@link GeneradorAverias}. El nodo es indiferente para el archivo,
     * porque el formato adoptado no consigna el lugar del incidente.
     */
    private static List<UnidadTransporte> unidadesEnRuta(List<String> codigosUnidad) {
        int nodoCentral = Almacen.crearCentral().nodo();
        List<UnidadTransporte> unidades = new ArrayList<>(codigosUnidad.size());
        for (String codigo : codigosUnidad) {
            UnidadTransporte unidad = UnidadTransporte.de(codigo, nodoCentral);
            unidad.estado(EstadoUnidad.EN_RUTA);
            unidades.add(unidad);
        }
        return unidades;
    }

    private static void agregar(List<String> codigos, TipoUnidad tipo, int cantidad) {
        if (cantidad < 0) {
            throw new IllegalArgumentException("Cantidad negativa de unidades de tipo " + tipo);
        }
        for (int i = 1; i <= cantidad; i++) {
            codigos.add(String.format("%s%02d", tipo.prefijo(), i));
        }
    }

    /** Arma el registro {@code ##d##h##m:TTNN:T}. */
    private static String registro(StringBuilder sb, int minutoDelMes, String codigo, int tipoAveria) {
        sb.setLength(0);
        escribirInstante(sb, minutoDelMes);
        sb.append(':').append(codigo).append(':').append(tipoAveria);
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
}
