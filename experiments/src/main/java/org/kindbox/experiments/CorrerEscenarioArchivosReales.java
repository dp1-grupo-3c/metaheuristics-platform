package org.kindbox.experiments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapta ventas.AAAAMM.txt a ventasAAAAMM.txt en una copia temporal.
 * Conserva los contenidos y la estructura restante y delega en CorrerEscenario.
 * No adapta los lectores de bloqueos, flota o mantenimiento.
 */
public final class CorrerEscenarioArchivosReales {

    private static final Pattern VENTAS = Pattern.compile(
            "^ventas\\.?([0-9]{4})([0-9]{2})(\\.txt)?$",
            Pattern.CASE_INSENSITIVE);

    private CorrerEscenarioArchivosReales() {}

    public static void main(String[] argumentos) throws Exception {
        Path origen = Path.of(argumentos.length > 0 ? argumentos[0] : "data_dp1")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(origen.resolve("ventas"))) {
            throw new IllegalArgumentException(
                    "No existe la carpeta de ventas: " + origen.resolve("ventas"));
        }

        Path temporal = Files.createTempDirectory("kindbox-datos-");
        try {
            int adaptados = prepararCopia(origen, temporal);
            if (adaptados == 0) {
                throw new IllegalArgumentException(
                        "No se encontraron archivos ventas.AAAAMM.txt o ventasAAAAMM.txt en "
                                + origen.resolve("ventas"));
            }
            System.out.println("Datos originales: " + origen);
            System.out.println("Copia compatible: " + temporal);
            System.out.println("Archivos mensuales de ventas reconocidos: " + adaptados);

            String[] delegados = Arrays.copyOf(argumentos, Math.max(1, argumentos.length));
            delegados[0] = temporal.toString();
            CorrerEscenario.main(delegados);
        } finally {
            borrarCopia(temporal);
        }
    }

    private static int prepararCopia(Path origen, Path destino) throws IOException {
        List<Path> entradas;
        try (Stream<Path> recorrido = Files.walk(origen)) {
            entradas = recorrido.collect(Collectors.toList());
        }

        int reconocidos = 0;
        for (Path entrada : entradas) {
            Path relativa = origen.relativize(entrada);
            Path salida = destino.resolve(relativa);
            if (Files.isDirectory(entrada)) {
                Files.createDirectories(salida);
                continue;
            }

            // El repositorio espera los archivos directamente dentro de ventas/.
            if (relativa.getNameCount() == 2
                    && relativa.getName(0).toString().equals("ventas")) {
                Matcher coincidencia = VENTAS.matcher(entrada.getFileName().toString());
                if (coincidencia.matches()) {
                    int ano = Integer.parseInt(coincidencia.group(1));
                    int mes = Integer.parseInt(coincidencia.group(2));
                    YearMonth.of(ano, mes); // Rechaza meses inexistentes.
                    String nombre = String.format(Locale.ROOT,
                            "ventas%04d%02d.txt", ano, mes);
                    salida = destino.resolve("ventas").resolve(nombre);
                    reconocidos++;
                    System.out.println("Ventas: " + entrada.getFileName() + " -> " + nombre);
                }
            }

            Files.createDirectories(salida.getParent());
            // Sin REPLACE_EXISTING: detecta dos archivos que se normalizan al mismo nombre.
            Files.copy(entrada, salida);
        }
        return reconocidos;
    }

    private static void borrarCopia(Path temporal) {
        try (Stream<Path> recorrido = Files.walk(temporal)) {
            List<Path> entradas = recorrido.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path entrada : entradas) {
                Files.delete(entrada);
            }
        } catch (IOException error) {
            // No oculta una eventual excepcion original de carga o simulacion.
            System.err.println("No se pudo limpiar la copia temporal "
                    + temporal + ": " + error.getMessage());
        }
    }
}
