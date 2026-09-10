package org.kindbox.core.metaheuristica.alns;

/**
 * Ordenacion de un arreglo de indices por una clave real paralela, sin envoltorios ni
 * asignacion de memoria.
 *
 * <p>La ordenacion de la biblioteca estandar exige {@code Integer[]} y un comparador para
 * ordenar indices por una clave externa, lo que introduce autoboxing en el bucle mas caliente
 * de los operadores de destruccion. El apartado 13 del ISA descarta esa via, de modo que aqui
 * se implementa una ordenacion rapida de tres particiones sobre arreglos primitivos.</p>
 */
final class OrdenPorClave {

    /** Por debajo de este tamano la ordenacion por insercion es mas rapida. */
    private static final int UMBRAL_INSERCION = 12;

    private OrdenPorClave() {
    }

    /**
     * Ordena los primeros {@code n} elementos de {@code indices} de forma que sus claves
     * queden en orden creciente.
     *
     * @param clave   claves indexadas por el valor guardado en {@code indices}
     * @param indices indices a ordenar, modificado en el lugar
     * @param n       numero de indices validos
     */
    static void ascendente(double[] clave, int[] indices, int n) {
        ordenar(clave, indices, 0, n - 1);
    }

    /** Ordena los primeros {@code n} indices de forma que sus claves queden en orden decreciente. */
    static void descendente(double[] clave, int[] indices, int n) {
        ordenar(clave, indices, 0, n - 1);
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            int t = indices[i];
            indices[i] = indices[j];
            indices[j] = t;
        }
    }

    private static void ordenar(double[] clave, int[] indices, int desde, int hasta) {
        while (desde < hasta) {
            if (hasta - desde < UMBRAL_INSERCION) {
                porInsercion(clave, indices, desde, hasta);
                return;
            }
            double pivote = clave[indices[desde + ((hasta - desde) >>> 1)]];
            int menor = desde;
            int mayor = hasta;
            int i = desde;
            while (i <= mayor) {
                double actual = clave[indices[i]];
                if (actual < pivote) {
                    intercambiar(indices, menor++, i++);
                } else if (actual > pivote) {
                    intercambiar(indices, i, mayor--);
                } else {
                    i++;
                }
            }
            // Se recurre sobre el lado corto y se itera sobre el largo, para acotar la pila.
            if (menor - desde < hasta - mayor) {
                ordenar(clave, indices, desde, menor - 1);
                desde = mayor + 1;
            } else {
                ordenar(clave, indices, mayor + 1, hasta);
                hasta = menor - 1;
            }
        }
    }

    private static void porInsercion(double[] clave, int[] indices, int desde, int hasta) {
        for (int i = desde + 1; i <= hasta; i++) {
            int actual = indices[i];
            double valor = clave[actual];
            int j = i - 1;
            while (j >= desde && clave[indices[j]] > valor) {
                indices[j + 1] = indices[j];
                j--;
            }
            indices[j + 1] = actual;
        }
    }

    private static void intercambiar(int[] indices, int i, int j) {
        int t = indices[i];
        indices[i] = indices[j];
        indices[j] = t;
    }

    /**
     * Indice sesgado de Ropke y Pisinger (2006) sobre una lista ya ordenada de {@code n}
     * candidatos: {@code (int) (y^determinismo * n)} con {@code y} uniforme en {@code [0,1)}.
     * Con determinismo uno la eleccion es uniforme y cuanto mayor es mas se concentra en los
     * primeros puestos, que son los mejores segun el criterio del operador.
     */
    static int indiceSesgado(int n, double determinismo, org.kindbox.core.util.Aleatorio aleatorio) {
        if (n <= 1) {
            return 0;
        }
        double y = Math.pow(aleatorio.siguienteDouble(), determinismo);
        int indice = (int) (y * n);
        return indice >= n ? n - 1 : indice;
    }
}
