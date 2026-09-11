package org.kindbox.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Generador aleatorio de la corrida, apartado 10 del ISA: una instancia unica con semilla
 * fijada por corrida, de modo que cada ejecucion sea reproducible.
 *
 * <p>Se comprueban las dos propiedades de las que depende la experimentacion numerica del
 * apartado 12: la reproducibilidad a partir de la semilla, sin la cual una corrida no se
 * puede repetir, y una uniformidad gruesa de {@code siguienteEntero} y de {@code ruleta},
 * sin la cual la capa adaptativa de ALNS del apartado 7.3.3 elegiria operadores con un
 * sesgo que no es el de sus pesos. La comprobacion de uniformidad es deliberadamente
 * gruesa: no pretende ser un contraste estadistico, solo detectar un sesgo estructural.</p>
 */
class AleatorioTest {

    @Test
    @DisplayName("La misma semilla produce exactamente la misma secuencia")
    void mismaSemillaMismaSecuencia() {
        Aleatorio uno = new Aleatorio(20260901L);
        Aleatorio otro = new Aleatorio(20260901L);
        assertEquals(20260901L, uno.semilla());
        for (int i = 0; i < 1000; i++) {
            assertEquals(uno.siguienteLong(), otro.siguienteLong(), "divergen en el valor " + i);
        }

        Aleatorio enteros = new Aleatorio(7L);
        Aleatorio enterosBis = new Aleatorio(7L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(enteros.siguienteEntero(97), enterosBis.siguienteEntero(97));
            assertEquals(enteros.siguienteDouble(), enterosBis.siguienteDouble());
        }
    }

    @Test
    @DisplayName("Semillas distintas producen secuencias distintas")
    void semillasDistintasSecuenciasDistintas() {
        Aleatorio uno = new Aleatorio(1L);
        Aleatorio otro = new Aleatorio(2L);
        int iguales = 0;
        for (int i = 0; i < 1000; i++) {
            if (uno.siguienteLong() == otro.siguienteLong()) {
                iguales++;
            }
        }
        assertEquals(0, iguales, "dos semillas distintas no deberian coincidir en ningun valor de 64 bits");
        // Semillas contiguas tambien deben separarse: el estado se siembra con SplitMix64.
        assertNotEquals(new Aleatorio(100L).siguienteLong(), new Aleatorio(101L).siguienteLong());
    }

    @Test
    @DisplayName("La semilla derivada es determinista y separa flujos consecutivos")
    void semillaDerivadaSeparaFlujos() {
        final long maestra = 20260901L;
        final long pasoWeyl = 0x9E3779B97F4A7C15L;
        assertEquals(Aleatorio.derivarSemilla(maestra, 3L), Aleatorio.derivarSemilla(maestra, 3L));

        long[] semillas = new long[1000];
        long bitsDistintos = 0L;
        long primeroAnterior = 0L;
        for (int k = 0; k < semillas.length; k++) {
            semillas[k] = Aleatorio.derivarSemilla(maestra, k);
            assertNotEquals(maestra, semillas[k]);
            long primero = new Aleatorio(semillas[k]).siguienteLong();
            if (k > 0) {
                // Sin la mezcla, flujos consecutivos quedarian a un paso de Weyl y sus
                // generadores compartirian palabras de estado desplazadas.
                assertNotEquals(pasoWeyl, semillas[k] - semillas[k - 1]);
                bitsDistintos += Long.bitCount(primero ^ primeroAnterior);
            }
            primeroAnterior = primero;
        }
        long[] ordenadas = semillas.clone();
        Arrays.sort(ordenadas);
        for (int k = 1; k < ordenadas.length; k++) {
            assertNotEquals(ordenadas[k - 1], ordenadas[k], "dos flujos con la misma semilla");
        }
        // Los primeros valores de flujos consecutivos difieren en la mitad de los bits, en media.
        double media = (double) bitsDistintos / (semillas.length - 1);
        assertTrue(media > 30.0 && media < 34.0, "media de bits distintos: " + media);
        assertNotEquals(Aleatorio.derivarSemilla(maestra, 0L), Aleatorio.derivarSemilla(maestra + 1L, 0L));
    }

    @Test
    @DisplayName("siguienteEntero respeta su cota y no la alcanza nunca")
    void enteroDentroDeLaCota() {
        Aleatorio aleatorio = new Aleatorio(31L);
        for (int cota : new int[] {1, 2, 3, 7, 37, 1000}) {
            for (int i = 0; i < 5000; i++) {
                int valor = aleatorio.siguienteEntero(cota);
                assertTrue(valor >= 0 && valor < cota, "valor " + valor + " fuera de [0," + cota + ")");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Aleatorio(1L).siguienteEntero(0));
        assertThrows(IllegalArgumentException.class, () -> new Aleatorio(1L).siguienteEntero(-5));
    }

    @Test
    @DisplayName("siguienteEntero reparte de forma uniforme entre sus valores")
    void uniformidadGruesaDelEntero() {
        int caras = 10;
        int tiradas = 200_000;
        int[] cuenta = new int[caras];
        Aleatorio aleatorio = new Aleatorio(987654321L);
        for (int i = 0; i < tiradas; i++) {
            cuenta[aleatorio.siguienteEntero(caras)]++;
        }
        double esperado = (double) tiradas / caras;
        for (int c = 0; c < caras; c++) {
            double desvio = Math.abs(cuenta[c] - esperado) / esperado;
            assertTrue(desvio < 0.05, "la cara " + c + " salio " + cuenta[c] + " veces frente a las "
                    + esperado + " esperadas");
        }
    }

    @Test
    @DisplayName("siguienteEntero con rango explicito cubre ambos extremos")
    void enteroEnRangoCerrado() {
        Aleatorio aleatorio = new Aleatorio(555L);
        boolean vioMinimo = false;
        boolean vioMaximo = false;
        for (int i = 0; i < 10_000; i++) {
            int valor = aleatorio.siguienteEntero(-3, 4);
            assertTrue(valor >= -3 && valor <= 4, "valor " + valor + " fuera de [-3,4]");
            vioMinimo |= valor == -3;
            vioMaximo |= valor == 4;
        }
        assertTrue(vioMinimo, "el extremo inferior del rango nunca salio");
        assertTrue(vioMaximo, "el extremo superior del rango nunca salio");
    }

    @Test
    @DisplayName("siguienteDouble vive en el intervalo [0,1) y reparte de forma pareja")
    void uniformidadDelReal() {
        Aleatorio aleatorio = new Aleatorio(13L);
        int[] cuenta = new int[10];
        int tiradas = 100_000;
        double suma = 0.0;
        for (int i = 0; i < tiradas; i++) {
            double valor = aleatorio.siguienteDouble();
            assertTrue(valor >= 0.0 && valor < 1.0, "valor " + valor + " fuera de [0,1)");
            cuenta[(int) (valor * 10)]++;
            suma += valor;
        }
        assertTrue(Math.abs(suma / tiradas - 0.5) < 0.01, "la media de 100 000 valores deberia rondar 0.5");
        double esperado = tiradas / 10.0;
        for (int d = 0; d < 10; d++) {
            assertTrue(Math.abs(cuenta[d] - esperado) / esperado < 0.06,
                    "el decil " + d + " salio " + cuenta[d] + " veces");
        }
    }

    @Test
    @DisplayName("ruleta elige cada indice en proporcion a su peso")
    void uniformidadGruesaDeLaRuleta() {
        double[] pesos = {1.0, 3.0, 6.0};
        int tiradas = 200_000;
        int[] cuenta = new int[pesos.length];
        Aleatorio aleatorio = new Aleatorio(2468L);
        for (int i = 0; i < tiradas; i++) {
            cuenta[aleatorio.ruleta(pesos)]++;
        }
        double[] esperado = {0.10, 0.30, 0.60};
        for (int i = 0; i < pesos.length; i++) {
            double fraccion = (double) cuenta[i] / tiradas;
            assertTrue(Math.abs(fraccion - esperado[i]) < 0.01,
                    "el indice " + i + " salio en el " + fraccion + " de las tiradas y se esperaba "
                            + esperado[i]);
        }
    }

    @Test
    @DisplayName("ruleta nunca elige un peso nulo y cae en uniforme si todos lo son")
    void ruletaConPesosDegenerados() {
        Aleatorio aleatorio = new Aleatorio(99L);
        double[] conCeros = {0.0, 5.0, 0.0};
        for (int i = 0; i < 1000; i++) {
            assertEquals(1, aleatorio.ruleta(conCeros), "un peso nulo no puede salir elegido");
        }
        double[] todosCero = {0.0, 0.0, 0.0, 0.0};
        int[] cuenta = new int[todosCero.length];
        for (int i = 0; i < 40_000; i++) {
            int elegido = aleatorio.ruleta(todosCero);
            assertTrue(elegido >= 0 && elegido < todosCero.length, "indice fuera de rango: " + elegido);
            cuenta[elegido]++;
        }
        for (int i = 0; i < todosCero.length; i++) {
            assertTrue(Math.abs(cuenta[i] - 10_000) < 600,
                    "con todos los pesos a cero el reparto debe ser uniforme, y el indice " + i
                            + " salio " + cuenta[i] + " veces");
        }
    }

    @Test
    @DisplayName("conProbabilidad respeta la probabilidad pedida y sus extremos")
    void probabilidadDeclarada() {
        Aleatorio aleatorio = new Aleatorio(1234L);
        int exitos = 0;
        int tiradas = 100_000;
        for (int i = 0; i < tiradas; i++) {
            if (aleatorio.conProbabilidad(0.25)) {
                exitos++;
            }
        }
        assertTrue(Math.abs((double) exitos / tiradas - 0.25) < 0.01,
                "la frecuencia observada fue " + (double) exitos / tiradas);
        for (int i = 0; i < 1000; i++) {
            assertTrue(aleatorio.conProbabilidad(1.0), "la probabilidad uno debe darse siempre");
            assertFalse(aleatorio.conProbabilidad(0.0), "la probabilidad cero no puede darse nunca");
        }
    }

    @Test
    @DisplayName("barajar produce una permutacion y no pierde ni repite elementos")
    void barajarEsUnaPermutacion() {
        int[] arreglo = new int[64];
        for (int i = 0; i < arreglo.length; i++) {
            arreglo[i] = i;
        }
        Aleatorio aleatorio = new Aleatorio(4321L);
        int[] barajado = arreglo.clone();
        aleatorio.barajar(barajado);
        int[] ordenado = barajado.clone();
        Arrays.sort(ordenado);
        assertArrayEquals(arreglo, ordenado, "barajar debe conservar exactamente los mismos elementos");
        assertFalse(Arrays.equals(arreglo, barajado), "barajar 64 elementos no deberia dejarlos en orden");

        // La misma semilla baraja igual, que es lo que hace repetible una corrida de HGS.
        int[] uno = arreglo.clone();
        int[] otro = arreglo.clone();
        new Aleatorio(88L).barajar(uno);
        new Aleatorio(88L).barajar(otro);
        assertArrayEquals(uno, otro);
    }

    @Test
    @DisplayName("barajar parcial solo toca el prefijo indicado")
    void barajarParcial() {
        int[] arreglo = new int[20];
        for (int i = 0; i < arreglo.length; i++) {
            arreglo[i] = i;
        }
        new Aleatorio(17L).barajar(arreglo, 10);
        for (int i = 10; i < arreglo.length; i++) {
            assertEquals(i, arreglo[i], "la cola del arreglo no debe moverse");
        }
        int[] prefijo = Arrays.copyOf(arreglo, 10);
        Arrays.sort(prefijo);
        for (int i = 0; i < 10; i++) {
            assertEquals(i, prefijo[i], "el prefijo debe seguir conteniendo los mismos elementos");
        }
    }
}
