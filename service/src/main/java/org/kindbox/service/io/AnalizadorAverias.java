package org.kindbox.service.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.LectorAverias;

/**
 * Adapta la carga masiva de averias del visualizador al lector del nucleo.
 *
 * <p>El formato del archivo es {@code ##d##h##m:TTNN:T} y quien lo interpreta es
 * {@link LectorAverias}, que es el unico lugar del proyecto donde vive esa gramatica. Este
 * analizador solo hace lo que el lector no puede hacer, porque no conoce la corrida: situar
 * el archivo en el mes del escenario, descartar los registros que caen fuera del horizonte y
 * rechazar las placas que no pertenecen a la flota cargada.</p>
 *
 * <p>El lector trabaja sobre un {@code Path} porque su caso normal es un archivo mensual del
 * equipo docente, mientras que aqui el contenido llega en memoria desde una peticion
 * multipart. El puente es un archivo temporal que se borra siempre; escribirlo cuesta menos
 * que duplicar la gramatica en dos sitios, que es como se desincronizan los formatos.</p>
 *
 * <p>El lector es deliberadamente estricto y detiene la carga en el primer registro mal
 * formado, con el numero de linea y el registro completo en el mensaje. Esa excepcion sube
 * tal cual: una averia perdida en silencio cambiaria la disponibilidad de la flota y con ella
 * el nivel 1 de la funcion objetivo del apartado 2.5 del ISA.</p>
 */
public final class AnalizadorAverias {

    private final LectorAverias lector;
    private final YearMonth mesDelEscenario;
    private final long minutoFin;

    /**
     * @param calendario      calendario de la corrida, que traduce el registro al reloj interno
     * @param mesDelEscenario mes al que pertenece el campo de dia del archivo
     * @param minutoFin       instante de cierre del escenario, en minutos desde su inicio
     */
    public AnalizadorAverias(CalendarioEscenario calendario, YearMonth mesDelEscenario, long minutoFin) {
        this.lector = new LectorAverias(calendario);
        this.mesDelEscenario = mesDelEscenario;
        this.minutoFin = minutoFin;
    }

    /**
     * Interpreta el contenido del archivo subido.
     *
     * @param contenido      texto del archivo
     * @param nombreArchivo  nombre con que lo subio el usuario, que aparece en los mensajes
     *                       de error del lector; admite nulo
     * @param codigosDeFlota codigos de unidad del escenario, para rechazar placas inexistentes
     * @throws IOException              si el archivo temporal no se puede escribir ni leer
     * @throws IllegalArgumentException si algun registro no sigue el formato
     */
    public Lectura analizar(String contenido, String nombreArchivo, Set<String> codigosDeFlota)
            throws IOException {
        // El lector del nucleo trabaja sobre un Path y nombra el archivo en sus mensajes de
        // error, de modo que el temporal se crea dentro de un directorio propio y conserva el
        // nombre original: asi el operador lee "mis-averias.txt, linea 4: ..." y no un nombre
        // generado al azar.
        Path directorio = Files.createTempDirectory("kindbox-averias");
        Path temporal = directorio.resolve(nombreSeguro(nombreArchivo));
        List<LectorAverias.AveriaProgramada> leidas;
        try {
            // ISO-8859-1 en la escritura y en la lectura, que es la codificacion con la que
            // el lector del nucleo abre los archivos de averias.
            Files.writeString(temporal, contenido, StandardCharsets.ISO_8859_1);
            leidas = lector.leer(temporal, mesDelEscenario);
        } finally {
            Files.deleteIfExists(temporal);
            Files.deleteIfExists(directorio);
        }

        List<LectorAverias.AveriaProgramada> admitidas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (LectorAverias.AveriaProgramada averia : leidas) {
            if (averia.minutoAveria() < 0 || averia.minutoAveria() > minutoFin) {
                avisos.add(averia.codigoUnidad() + " en el minuto " + averia.minutoAveria()
                        + ": queda fuera del horizonte del escenario y se descarta");
                continue;
            }
            if (codigosDeFlota != null && !codigosDeFlota.contains(averia.codigoUnidad())) {
                avisos.add(averia.codigoUnidad() + ": no pertenece a la flota del escenario y se descarta");
                continue;
            }
            admitidas.add(averia);
        }
        admitidas.sort(null);
        return new Lectura(admitidas, avisos);
    }

    /**
     * Nombre utilizable como archivo temporal: se queda con el nombre base y sustituye todo
     * lo que no sea letra, digito, punto, guion o subrayado, de modo que un nombre subido
     * desde el visualizador nunca pueda salirse del directorio temporal.
     */
    private static String nombreSeguro(String nombreArchivo) {
        if (nombreArchivo == null || nombreArchivo.isBlank()) {
            return "averias-cargadas.txt";
        }
        String base = Path.of(nombreArchivo).getFileName().toString();
        String limpio = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return limpio.isBlank() || limpio.equals(".") || limpio.equals("..")
                ? "averias-cargadas.txt" : limpio;
    }

    /**
     * Resultado de interpretar el archivo.
     *
     * @param averias registros admitidos, en orden cronologico
     * @param avisos  registros descartados y su motivo
     */
    public record Lectura(List<LectorAverias.AveriaProgramada> averias, List<String> avisos) {

        public Lectura {
            averias = List.copyOf(averias);
            avisos = List.copyOf(avisos);
        }
    }
}
