package org.kindbox.core.datagen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.simulacion.GeneradorAverias;

/**
 * Generador de archivos de averias de ejemplo, formato {@code ##d##h##m:TTNN:T} adoptado en
 * el apartado 7 del contexto de dominio.
 *
 * <p>El archivo alimenta la carga masiva del visualizador, de modo que lo que se comprueba
 * aqui es que se puede regenerar identico a partir de la semilla, que respeta el formato y
 * el rango del mes, y que aplica las mismas reglas que el motor de simulacion en lugar de
 * inventar las suyas.</p>
 */
class GeneradorArchivoAveriasTest {

    private static final YearMonth MES = YearMonth.of(2026, 9);

    @Test
    @DisplayName("La misma semilla regenera el archivo identico, byte a byte")
    void generacionReproducible(@TempDir Path directorio) throws IOException {
        GeneradorArchivoAverias uno = new GeneradorArchivoAverias(20260903L);
        GeneradorArchivoAverias otro = new GeneradorArchivoAverias(20260903L);
        assertArrayEquals(uno.registrosDeMes(MES), otro.registrosDeMes(MES));

        Path primera = Files.createDirectories(directorio.resolve("primera"));
        Path segunda = Files.createDirectories(directorio.resolve("segunda"));
        uno.escribirMes(MES, primera);
        otro.escribirMes(MES, segunda);
        assertArrayEquals(
                Files.readAllBytes(primera.resolve(GeneradorArchivoAverias.nombreArchivo(MES))),
                Files.readAllBytes(segunda.resolve(GeneradorArchivoAverias.nombreArchivo(MES))));

        assertFalse(Arrays.equals(uno.registrosDeMes(MES), new GeneradorArchivoAverias(1L).registrosDeMes(MES)),
                "otra semilla debe dar otro archivo");
    }

    @Test
    @DisplayName("Los registros siguen el formato adoptado y caen dentro del mes")
    void formatoYRangoDeLosRegistros() {
        String[] registros = new GeneradorArchivoAverias(777L).registrosDeMes(MES);
        assertTrue(registros.length > 0, "con la flota completa en ruta el mes debe traer averias");

        int ultimoMinuto = 0;
        for (String registro : registros) {
            String[] campos = registro.split(":");
            assertEquals(3, campos.length, "registro mal formado: " + registro);
            assertTrue(campos[0].matches("\\d{2}d\\d{2}h\\d{2}m"), "instante mal formado: " + registro);
            assertTrue(campos[1].matches("T[AMB]\\d{2}"), "codigo de unidad mal formado: " + registro);
            assertTrue(campos[2].matches("[123]"), "tipo de averia mal formado: " + registro);

            int dia = Integer.parseInt(campos[0].substring(0, 2));
            int hora = Integer.parseInt(campos[0].substring(3, 5));
            int minuto = Integer.parseInt(campos[0].substring(6, 8));
            assertTrue(dia >= 1 && dia <= MES.lengthOfMonth(), "dia fuera del mes: " + registro);
            assertTrue(hora <= 23 && minuto <= 59, "hora fuera de rango: " + registro);

            int absoluto = (dia - 1) * 1440 + hora * 60 + minuto;
            assertTrue(absoluto >= ultimoMinuto, "los registros deben salir ordenados por instante");
            ultimoMinuto = absoluto;
        }
    }

    @Test
    @DisplayName("El archivo aplica las reglas del motor y no unas propias")
    void aplicaLasReglasDelMotor() {
        // Con probabilidad cero no puede aparecer ninguna averia, por larga que sea la flota.
        assertEquals(0, new GeneradorArchivoAverias(5L, 0.0).registrosDeMes(MES).length);
        // Sin unidades tampoco.
        assertEquals(0, new GeneradorArchivoAverias(5L).registrosDeMes(MES, List.of()).length);

        // Subir la probabilidad tiene que producir mas averias.
        int pocas = new GeneradorArchivoAverias(5L, 0.01).registrosDeMes(MES).length;
        int muchas = new GeneradorArchivoAverias(5L, 0.20).registrosDeMes(MES).length;
        assertTrue(muchas > pocas, "con " + muchas + " frente a " + pocas + " la probabilidad no influye");

        // Las bicicletas se averian mas que los autos, que es la regla 2 del generador.
        String[] registros = new GeneradorArchivoAverias(20260903L, 0.10).registrosDeMes(MES);
        int autos = 0;
        int bicicletas = 0;
        for (String registro : registros) {
            String prefijo = registro.substring(registro.indexOf(':') + 1, registro.indexOf(':') + 3);
            if (prefijo.equals(TipoUnidad.AUTO.prefijo())) {
                autos++;
            } else if (prefijo.equals(TipoUnidad.BICICLETA.prefijo())) {
                bicicletas++;
            }
        }
        assertTrue(bicicletas > autos, "las bicicletas (" + bicicletas + ") deben averiarse mas que los"
                + " autos (" + autos + "), aunque haya menos bicicletas en la flota");

        // Las reglas expuestas son las mismas del motor de simulacion.
        GeneradorAverias reglas = new GeneradorArchivoAverias(9L).reglas();
        assertEquals(GeneradorArchivoAverias.PROBABILIDAD_POR_DEFECTO, reglas.probabilidadPorUnidadPorTurno());
        assertEquals(9L, reglas.semilla());
        assertTrue(reglas.activo());
    }

    @Test
    @DisplayName("Los codigos de la flota del enunciado son 10 autos, 15 motos y 12 bicicletas")
    void codigosDeLaFlota() {
        List<String> codigos = GeneradorArchivoAverias.codigosDeLaFlota();
        assertEquals(37, codigos.size());
        assertEquals("TA01", codigos.get(0));
        assertEquals("TA10", codigos.get(9));
        assertEquals("TM01", codigos.get(10));
        assertEquals("TM15", codigos.get(24));
        assertEquals("TB01", codigos.get(25));
        assertEquals("TB12", codigos.get(36));
        assertEquals(codigos.size(), codigos.stream().distinct().count(), "no puede repetirse ningun codigo");
        assertEquals(6, GeneradorArchivoAverias.codigosDeLaFlota(1, 2, 3).size());
    }

    @Test
    @DisplayName("El archivo escrito termina en salto de linea y no depende del sistema")
    void formatoDelArchivo(@TempDir Path directorio) throws IOException {
        GeneradorArchivoAverias generador = new GeneradorArchivoAverias(20260903L);
        int escritos = generador.escribirMes(MES, directorio);
        String contenido = Files.readString(
                directorio.resolve(GeneradorArchivoAverias.nombreArchivo(MES)), StandardCharsets.US_ASCII);

        assertFalse(contenido.contains("\r"), "el archivo no debe llevar retornos de carro");
        assertTrue(contenido.endsWith("\n"));
        assertEquals(escritos, contenido.split("\n").length);
        assertEquals("averias202609", GeneradorArchivoAverias.nombreArchivo(MES));
    }
}
