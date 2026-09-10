package org.kindbox.core.util;

/**
 * Generador de numeros aleatorios de la corrida.
 *
 * <p>El apartado 10 del ISA exige una instancia unica con semilla fijada por corrida, de
 * modo que cada ejecucion sea reproducible y las corridas repetidas del apartado 12.3
 * sean independientes de forma controlada.</p>
 *
 * <p>Implementa xoshiro256++, que es rapido, no asigna memoria y tiene mejor calidad
 * estadistica que un congruencial lineal. Se implementa aqui, y no se toma de
 * {@code java.util.Random}, para poder reproducir una corrida a partir unicamente de su
 * semilla con independencia de la version de la maquina virtual.</p>
 */
public final class Aleatorio {

    private final long semilla;
    private long s0;
    private long s1;
    private long s2;
    private long s3;

    public Aleatorio(long semilla) {
        this.semilla = semilla;
        // SplitMix64 para sembrar el estado, segun la recomendacion de los autores.
        long x = semilla;
        this.s0 = splitMix64(x += 0x9E3779B97F4A7C15L);
        this.s1 = splitMix64(x += 0x9E3779B97F4A7C15L);
        this.s2 = splitMix64(x += 0x9E3779B97F4A7C15L);
        this.s3 = splitMix64(x + 0x9E3779B97F4A7C15L);
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Semilla con la que se creo el generador. */
    public long semilla() {
        return semilla;
    }

    /** Siguiente valor de 64 bits. */
    public long siguienteLong() {
        long resultado = Long.rotateLeft(s0 + s3, 23) + s0;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return resultado;
    }

    /** Entero uniforme en {@code [0, cota)}. */
    public int siguienteEntero(int cota) {
        if (cota <= 0) {
            throw new IllegalArgumentException("Cota no positiva: " + cota);
        }
        // Lemire: multiplicacion de 64 bits con rechazo del sesgo residual.
        long m = (siguienteLong() >>> 32) * cota;
        long l = m & 0xFFFFFFFFL;
        if (l < cota) {
            long umbral = Integer.toUnsignedLong(-cota) % cota;
            while (l < umbral) {
                m = (siguienteLong() >>> 32) * cota;
                l = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /** Entero uniforme en {@code [desde, hasta]}. */
    public int siguienteEntero(int desde, int hasta) {
        return desde + siguienteEntero(hasta - desde + 1);
    }

    /** Real uniforme en {@code [0,1)}. */
    public double siguienteDouble() {
        return (siguienteLong() >>> 11) * 0x1.0p-53;
    }

    /** Real uniforme en {@code [desde, hasta)}. */
    public double siguienteDouble(double desde, double hasta) {
        return desde + siguienteDouble() * (hasta - desde);
    }

    /** Devuelve {@code true} con la probabilidad indicada. */
    public boolean conProbabilidad(double p) {
        return siguienteDouble() < p;
    }

    /** Baraja el arreglo en el lugar, con el algoritmo de Fisher y Yates. */
    public void barajar(int[] arreglo) {
        for (int i = arreglo.length - 1; i > 0; i--) {
            int j = siguienteEntero(i + 1);
            int t = arreglo[i];
            arreglo[i] = arreglo[j];
            arreglo[j] = t;
        }
    }

    /** Baraja los primeros {@code n} elementos del arreglo en el lugar. */
    public void barajar(int[] arreglo, int n) {
        for (int i = n - 1; i > 0; i--) {
            int j = siguienteEntero(i + 1);
            int t = arreglo[i];
            arreglo[i] = arreglo[j];
            arreglo[j] = t;
        }
    }

    /**
     * Selecciona un indice por ruleta ponderada sobre los pesos dados.
     * Alimenta la capa adaptativa de ALNS del apartado 7.3.3.
     */
    public int ruleta(double[] pesos) {
        double total = 0.0;
        for (double p : pesos) {
            total += p;
        }
        if (total <= 0.0) {
            return siguienteEntero(pesos.length);
        }
        double corte = siguienteDouble() * total;
        double acumulado = 0.0;
        for (int i = 0; i < pesos.length; i++) {
            acumulado += pesos[i];
            if (corte < acumulado) {
                return i;
            }
        }
        return pesos.length - 1;
    }
}
