package org.kindbox.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.datagen.GeneradorArchivoAverias;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.Pedido;

/**
 * Ida y vuelta de los cuatro archivos del escenario: ventas, bloqueos, mantenimiento
 * preventivo y averias.
 *
 * <p>Cada generador escribe un archivo real en un directorio temporal, el lector
 * correspondiente lo interpreta y la prueba <b>vuelve a escribir</b> cada registro a partir
 * del objeto leido y lo compara caracter a caracter con el original. Es la unica forma de
 * asegurar que el lector no pierde ni deforma informacion en el camino: un archivo que se
 * lee sin errores pero desplaza un dia o intercambia dos coordenadas no da ningun sintoma
 * hasta que el plan sale mal.</p>
 *
 * <p>Los formatos son los de los apartados 5 a 8 del contexto de dominio. El de bloqueos y
 * el de averias no los publico el equipo docente y se adoptaron por analogia con el de
 * ventas, de modo que esta prueba es tambien la especificacion ejecutable de esa decision.</p>
 */
class IdaYVueltaDeArchivosTest {

    /** Mes de trabajo de las pruebas. */
    private static final YearMonth MES = YearMonth.of(2026, 9);

    /** Escribe el instante en el formato {@code ##d##h##m} que usan los cuatro archivos. */
    private static String instante(long minutoDelMes) {
        long dia = minutoDelMes / 1440 + 1;
        long minutoDelDia = minutoDelMes % 1440;
        return String.format("%02dd%02dh%02dm", dia, minutoDelDia / 60, minutoDelDia % 60);
    }

    @Test
    @DisplayName("Ventas: el archivo generado se lee y se vuelve a escribir sin perder nada")
    void idaYVueltaDeVentas(@TempDir Path directorio) throws IOException {
        GeneradorVentas generador = new GeneradorVentas(PerfilDemanda.LIGERO);
        String[] esperados = generador.registrosDeMes(MES);
        int escritos = generador.escribirMes(MES, directorio);
        assertEquals(esperados.length, escritos);
        assertTrue(escritos > 0, "el mes de prueba debe traer pedidos");

        Path archivo = directorio.resolve(GeneradorVentas.nombreArchivo(MES));
        assertEquals(MES, LectorVentas.mesDeArchivo(archivo.getFileName().toString()));

        List<Pedido> pedidos = new LectorVentas(CalendarioEscenario.desde(MES)).leer(archivo, 0);
        assertEquals(esperados.length, pedidos.size(), "no se puede perder ni un pedido");
        for (int i = 0; i < pedidos.size(); i++) {
            Pedido p = pedidos.get(i);
            String reconstruido = instante(p.minutoRegistro()) + ":" + p.x() + "," + p.y() + ","
                    + p.idCliente() + "," + p.cantidad() + "," + p.plazoHoras();
            assertEquals(esperados[i], reconstruido, "difieren en el registro " + i);
            assertEquals(i, p.id(), "los identificadores deben ser correlativos desde el primero");
        }
    }

    @Test
    @DisplayName("Bloqueos: el archivo generado se lee y se vuelve a escribir sin perder nada")
    void idaYVueltaDeBloqueos(@TempDir Path directorio) throws IOException {
        GeneradorBloqueos generador = new GeneradorBloqueos(424242L);
        String[] esperados = generador.registrosDeMes(MES, 12);
        int escritos = generador.escribirMes(MES, 12, directorio);
        assertEquals(12, escritos);

        Path archivo = directorio.resolve(GeneradorBloqueos.nombreArchivo(MES));
        assertEquals(MES, LectorBloqueos.mesDeArchivo(archivo.getFileName().toString()));

        List<Bloqueo> bloqueos = new LectorBloqueos(CalendarioEscenario.desde(MES)).leer(archivo);
        assertEquals(esperados.length, bloqueos.size());
        for (int i = 0; i < bloqueos.size(); i++) {
            Bloqueo b = bloqueos.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(instante(b.minutoInicio())).append('-').append(instante(b.minutoFin())).append(':');
            int[] nodos = b.nodos();
            for (int n = 0; n < nodos.length; n++) {
                if (n > 0) {
                    sb.append(',');
                }
                sb.append(Ciudad.x(nodos[n])).append(',').append(Ciudad.y(nodos[n]));
            }
            assertEquals(esperados[i], sb.toString(), "difieren en el registro " + i);
            assertTrue(b.minutoFin() > b.minutoInicio(), "la ventana de vigencia no puede ser vacia");
            for (int n = 1; n < nodos.length; n++) {
                assertEquals(1, Ciudad.distanciaManhattan(nodos[n - 1], nodos[n]),
                        "los nodos de la poligonal deben ser contiguos");
            }
        }
    }

    @Test
    @DisplayName("Mantenimiento: el archivo generado se lee y se vuelve a escribir sin perder nada")
    void idaYVueltaDeMantenimiento(@TempDir Path directorio) throws IOException {
        List<String> esperados = new ArrayList<>();
        for (String registro : GeneradorMantenimiento.registrosDeMes(MES)) {
            esperados.add(registro);
        }
        for (String registro : GeneradorMantenimiento.registrosDeMes(MES.plusMonths(1))) {
            esperados.add(registro);
        }
        int escritos = GeneradorMantenimiento.escribirPar(MES, directorio);
        assertEquals(esperados.size(), escritos);

        Path archivo = directorio.resolve(GeneradorMantenimiento.nombreArchivo(MES, MES.plusMonths(1)));
        assertTrue(LectorMantenimiento.esArchivoDeMantenimiento(archivo.getFileName().toString()));

        List<Mantenimiento> entradas = new LectorMantenimiento().leer(archivo);
        assertEquals(esperados.size(), entradas.size());
        for (int i = 0; i < entradas.size(); i++) {
            Mantenimiento m = entradas.get(i);
            String reconstruido = String.format("%04d%02d%02d:%s", m.fecha().getYear(),
                    m.fecha().getMonthValue(), m.fecha().getDayOfMonth(), m.codigoUnidad());
            assertEquals(esperados.get(i), reconstruido, "difieren en el registro " + i);
        }
        // La vista agrupada por fecha es la que consume el motor de simulacion.
        assertEquals(entradas.size(), LectorMantenimiento.agrupadoPorFecha(entradas).size(),
                "el plan entregado programa un solo mantenimiento por dia");
    }

    @Test
    @DisplayName("Averias: el archivo generado se lee y se vuelve a escribir sin perder nada")
    void idaYVueltaDeAverias(@TempDir Path directorio) throws IOException {
        GeneradorArchivoAverias generador = new GeneradorArchivoAverias(20260903L);
        String[] esperados = generador.registrosDeMes(MES);
        int escritos = generador.escribirMes(MES, directorio);
        assertEquals(esperados.length, escritos);
        assertTrue(escritos > 0, "con la flota completa en ruta el mes debe traer averias");

        Path archivo = directorio.resolve(GeneradorArchivoAverias.nombreArchivo(MES));
        assertEquals(GeneradorArchivoAverias.nombreArchivo(MES), LectorAverias.nombreDeArchivo(MES),
                "el generador y el lector deben coincidir en el nombre del archivo");
        assertEquals(MES, LectorAverias.mesDeArchivo(archivo.getFileName().toString()));

        List<LectorAverias.AveriaProgramada> averias =
                new LectorAverias(CalendarioEscenario.desde(MES)).leer(archivo);
        assertEquals(esperados.length, averias.size());
        long anterior = Long.MIN_VALUE;
        for (int i = 0; i < averias.size(); i++) {
            LectorAverias.AveriaProgramada a = averias.get(i);
            String reconstruido = instante(a.minutoAveria()) + ":" + a.codigoUnidad() + ":"
                    + a.tipo().codigo();
            assertEquals(esperados[i], reconstruido, "difieren en el registro " + i);
            assertTrue(a.minutoAveria() >= anterior, "las averias deben salir ordenadas por instante");
            anterior = a.minutoAveria();
            assertTrue(a.minutoAveria() >= 0 && a.minutoAveria() < MES.lengthOfMonth() * 1440L,
                    "la averia " + i + " cae fuera del mes del archivo");
            // El registro no lleva el lugar: lo completa el motor cuando dispara el suceso.
            assertEquals(Ciudad.nodo(27, 14), a.enNodo(Ciudad.nodo(27, 14)).nodo());
            assertEquals(a.tipo(), a.enNodo(0).tipo());
        }
    }

    @Test
    @DisplayName("Los cuatro lectores ignoran lineas en blanco y comentarios")
    void lineasEnBlancoYComentarios(@TempDir Path directorio) throws IOException {
        CalendarioEscenario calendario = CalendarioEscenario.desde(MES);

        Path ventas = escribir(directorio, "ventas202609",
                "# pedidos de prueba", "", "   ", "01d08h30m:45,43,c9167,12,36", "# fin");
        assertEquals(1, new LectorVentas(calendario).leer(ventas, 0).size());

        Path bloqueos = escribir(directorio, "202609.bloqueadas",
                "# bloqueos de prueba", "", "01d08h00m-01d18h30m:12,20,13,20,13,21");
        assertEquals(1, new LectorBloqueos(calendario).leer(bloqueos).size());

        Path mantenimiento = escribir(directorio, "mant.preventivo.09.10",
                "# plan de prueba", "", "20260901:TA01");
        assertEquals(1, new LectorMantenimiento().leer(mantenimiento).size());

        Path averias = escribir(directorio, "averias202609",
                "# averias de prueba", "", "  ", "03d11h47m:TB07:1", "# fin");
        List<LectorAverias.AveriaProgramada> leidas = new LectorAverias(calendario).leer(averias);
        assertEquals(1, leidas.size());
        assertEquals("TB07", leidas.get(0).codigoUnidad());
        assertEquals(1, leidas.get(0).tipo().codigo());
        // Dia 3 a las 11:47 son 2 dias completos mas 707 minutos.
        assertEquals(2 * 1440 + 11 * 60 + 47, leidas.get(0).minutoAveria());
    }

    @Test
    @DisplayName("El lector de averias rechaza los registros mal formados y normaliza el codigo")
    void averiasMalFormadas(@TempDir Path directorio) throws IOException {
        CalendarioEscenario calendario = CalendarioEscenario.desde(MES);
        LectorAverias lector = new LectorAverias(calendario);

        assertThrows(IllegalArgumentException.class,
                () -> lector.leer(escribir(directorio, "averias202609", "03d11h47m:TB07")),
                "faltan campos");
        assertThrows(IllegalArgumentException.class,
                () -> lector.leer(escribir(directorio, "averias202609", "03d11h47m:TB07:4")),
                "el tipo 4 no existe");
        assertThrows(IllegalArgumentException.class,
                () -> lector.leer(escribir(directorio, "averias202609", "03d11h47m:XX07:1")),
                "el prefijo de tipo de unidad es desconocido");
        assertThrows(IllegalArgumentException.class,
                () -> lector.leer(escribir(directorio, "averias202609", "03-11h47m:TB07:1")),
                "el instante no sigue el formato");
        assertThrows(IllegalArgumentException.class,
                () -> lector.leer(escribir(directorio, "averias202609", "31d11h47m:TB07:1")),
                "septiembre no tiene 31 dias");

        // El codigo se normaliza a mayusculas y se admiten espacios alrededor de los campos.
        List<LectorAverias.AveriaProgramada> leidas =
                lector.leer(escribir(directorio, "averias202609", " 03d11h47m : tb07 : 2 "));
        assertEquals("TB07", leidas.get(0).codigoUnidad());
        assertEquals(2, leidas.get(0).tipo().codigo());

        assertFalse(LectorAverias.esArchivoDeAverias("ventas202609"));
        assertFalse(LectorAverias.esArchivoDeAverias("averias2026"));
        assertTrue(LectorAverias.esArchivoDeAverias("averias202609.txt"));
        assertThrows(IllegalArgumentException.class, () -> LectorAverias.mesDeArchivo("averias202613"));
    }

    /** Escribe un archivo de prueba con las lineas indicadas y devuelve su ruta. */
    private static Path escribir(Path directorio, String nombre, String... lineas) throws IOException {
        Path archivo = directorio.resolve(nombre);
        StringBuilder sb = new StringBuilder();
        for (String linea : lineas) {
            sb.append(linea).append('\n');
        }
        Files.writeString(archivo, sb.toString(), StandardCharsets.ISO_8859_1);
        return archivo;
    }
}
