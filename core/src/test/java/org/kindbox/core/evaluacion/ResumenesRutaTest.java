package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Validez de la cota inferior del desfase que usan como filtro los dos algoritmos, conforme
 * al apartado 10 del ISA.
 *
 * <p>La primera prueba enfrenta la cota con el decodificador sobre miles de inserciones
 * aleatorias: la cota en tiempo constante por prefijo y sufijo tiene que coincidir con la que
 * se acumula parada a parada sobre la secuencia ya insertada, no puede superar nunca el
 * desfase que mide {@link ProgramadorRuta} y tiene que valer cero siempre que el decodificador
 * declare la ruta factible. La segunda comprueba que la forma sobre arreglos primitivos de la
 * concatenacion es la misma operacion que la del registro {@link DatosSecuencia}, que es la
 * que respalda la prueba de equivalencia incremental del apartado 14.</p>
 */
class ResumenesRutaTest {

    private static final int RUTAS_POR_INSTANCIA = 150;

    @Test
    @DisplayName("La cota de insercion coincide con la acumulada y nunca supera el desfase del decodificador")
    void cotaValidaFrenteAlDecodificador() {
        Aleatorio aleatorio = new Aleatorio(20260911L);
        int comprobadas = 0;
        int conCotaPositiva = 0;
        int factibles = 0;
        for (InstanciaPlanificacion instancia : List.of(InstanciasDePrueba.instanciaVariada(14),
                InstanciasDePrueba.instanciaFlotaMixta(16), InstanciasDePrueba.instanciaPlazosAjustados(26))) {
            ProgramadorRuta programador = new ProgramadorRuta(instancia);
            ResumenesRuta resumenes = new ResumenesRuta(instancia);
            int n = instancia.cantidadPedidos();
            int[] orden = new int[n];
            int[] ruta = new int[n];
            int[] insertada = new int[n + 1];
            int[] cantidades = new int[n + 1];

            for (int intento = 0; intento < RUTAS_POR_INSTANCIA; intento++) {
                int unidad = aleatorio.siguienteEntero(instancia.cantidadUnidades());
                for (int i = 0; i < n; i++) {
                    orden[i] = i;
                }
                aleatorio.barajar(orden);
                int longitud = aleatorio.siguienteEntero(Math.min(8, n - 1));
                System.arraycopy(orden, 0, ruta, 0, longitud);
                int pedido = orden[longitud];
                resumenes.preparar(unidad, ruta, longitud);

                for (int posicion = 0; posicion <= longitud; posicion++) {
                    int k = 0;
                    for (int i = 0; i < posicion; i++) {
                        insertada[k++] = ruta[i];
                    }
                    insertada[k++] = pedido;
                    for (int i = posicion; i < longitud; i++) {
                        insertada[k++] = ruta[i];
                    }
                    long cota = resumenes.desfaseInsertando(posicion, pedido);

                    resumenes.iniciar(unidad);
                    for (int i = 0; i < k; i++) {
                        resumenes.anadir(insertada[i]);
                    }
                    assertEquals(resumenes.cerrar(), cota, "la cota por prefijo y sufijo debe ser la acumulada");

                    // Cantidades dentro de la capacidad, para que el decodificador llegue a programar.
                    int capacidad = instancia.unidadCapacidad(unidad);
                    for (int i = 0; i < k; i++) {
                        cantidades[i] = Math.min(capacidad, instancia.pedidoCantidad(insertada[i]));
                    }
                    if (!programador.evaluar(unidad, insertada, cantidades, k)) {
                        continue;
                    }
                    comprobadas++;
                    assertTrue(cota <= programador.ultimoDesfase(), "la cota " + cota
                            + " supera el desfase del decodificador " + programador.ultimoDesfase());
                    if (programador.ultimaFactible()) {
                        factibles++;
                        assertEquals(0L, cota, "una ruta factible no puede tener cota positiva");
                    }
                    if (cota > 0L) {
                        conCotaPositiva++;
                    }
                }
            }
        }
        assertTrue(comprobadas > 1000, "deben compararse miles de inserciones y solo hubo " + comprobadas);
        assertTrue(factibles > 100, "debe haber inserciones factibles y solo hubo " + factibles);
        assertTrue(conCotaPositiva > 100, "la cota debe ser positiva en una fraccion apreciable y solo lo fue "
                + conCotaPositiva + " veces");
    }

    @Test
    @DisplayName("La concatenacion sobre arreglos es la misma operacion que la del registro")
    void concatenacionPrimitivaIgualAlRegistro() {
        Aleatorio aleatorio = new Aleatorio(77L);
        long[] a = new long[DatosSecuencia.CAMPOS];
        long[] b = new long[DatosSecuencia.CAMPOS];
        long[] resultado = new long[DatosSecuencia.CAMPOS];
        for (int intento = 0; intento < 2000; intento++) {
            DatosSecuencia x = aleatoria(aleatorio);
            DatosSecuencia y = aleatoria(aleatorio);
            int viaje = aleatorio.siguienteEntero(180);
            DatosSecuencia esperado = DatosSecuencia.concatenar(x, y, viaje, 0);
            copiar(x, a);
            copiar(y, b);
            DatosSecuencia.concatenar(a, 0, b, 0, viaje, resultado, 0);
            assertEquals(esperado.duracion(), resultado[DatosSecuencia.DURACION], "duracion");
            assertEquals(esperado.desfase(), resultado[DatosSecuencia.DESFASE], "desfase");
            assertEquals(esperado.inicioMasTemprano(), resultado[DatosSecuencia.TEMPRANO], "inicio mas temprano");
            assertEquals(esperado.inicioMasTardio(), resultado[DatosSecuencia.TARDIO], "inicio mas tardio");
        }
    }

    /** Resumen de una secuencia de hasta cuatro paradas con ventanas y tramos al azar. */
    private static DatosSecuencia aleatoria(Aleatorio aleatorio) {
        int paradas = 1 + aleatorio.siguienteEntero(4);
        DatosSecuencia acumulado = null;
        for (int i = 0; i < paradas; i++) {
            int temprano = aleatorio.siguienteEntero(600);
            int limite = temprano + aleatorio.siguienteEntero(600) - 100;
            DatosSecuencia parada = DatosSecuencia.deParada(temprano, limite, 60, 1);
            acumulado = acumulado == null ? parada
                    : DatosSecuencia.concatenar(acumulado, parada, aleatorio.siguienteEntero(120), 0);
        }
        return acumulado;
    }

    private static void copiar(DatosSecuencia datos, long[] destino) {
        destino[DatosSecuencia.DURACION] = datos.duracion();
        destino[DatosSecuencia.DESFASE] = datos.desfase();
        destino[DatosSecuencia.TEMPRANO] = datos.inicioMasTemprano();
        destino[DatosSecuencia.TARDIO] = datos.inicioMasTardio();
    }
}
