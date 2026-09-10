package org.kindbox.core.metaheuristica.hgs;

/**
 * Subpoblacion de la busqueda genetica hibrida con control adaptativo de la diversidad,
 * conforme al apartado 6.3.3 del ISA.
 *
 * <p>El algoritmo mantiene dos subpoblaciones, una de individuos factibles y otra de
 * infactibles, y cada una es una instancia de esta clase. La razon de esa separacion es que
 * en un problema con ventanas duras las mejores soluciones suelen estar pegadas a la
 * frontera de factibilidad: prohibir la infactibilidad aisla regiones del espacio de busqueda
 * que solo se alcanzan atravesandola, y admitirla sin control degrada la poblacion entera.
 * Dos subpoblaciones con su propio criterio de supervivencia resuelven el dilema.</p>
 *
 * <h2>Aptitud combinada</h2>
 * <p>La aptitud de un individuo <b>no es su valor objetivo</b>. Es la combinacion del rango
 * de ese valor y del rango de su contribucion a la diversidad, medida como distancia media a
 * los individuos mas proximos de su subpoblacion:</p>
 * <pre>
 *   aptitud(i) = rangoObjetivo(i) + (1 - nbElite/n) * rangoDiversidad(i)
 * </pre>
 * <p>con los dos rangos normalizados a {@code [0,1]} y menor es mejor. La consecuencia
 * buscada es que un individuo mediocre pero distinto puede sobrevivir a uno bueno pero
 * redundante, que es lo que evita la convergencia prematura sin recurrir a reinicios.</p>
 *
 * <h2>Supervivencia</h2>
 * <p>Cuando la subpoblacion alcanza el tamano minimo mas el tamano de generacion se
 * seleccionan supervivientes hasta volver al tamano minimo. Se eliminan primero los clones,
 * es decir los individuos a distancia cero de otro, y despues los de peor aptitud combinada.
 * Los mejores individuos por valor objetivo tienen supervivencia garantizada.</p>
 *
 * <p>La matriz de distancias entre individuos se mantiene incrementalmente: insertar cuesta
 * una fila y eliminar es un intercambio con el ultimo. No se asigna memoria durante la
 * corrida.</p>
 */
public final class Poblacion {

    private final ParametrosHgs parametros;
    private final int capacidad;

    private final Individuo[] individuos;
    private final double[][] distancia;
    private final int[] ordenObjetivo;
    private final int[] ordenDiversidad;
    private final int[] rangoObjetivo;
    private final int[] rangoDiversidad;
    private final double[] proximas;
    private int cantidad;

    /**
     * @param parametros      parametros de la corrida
     * @param cantidadTareas  longitud del cromosoma, para dimensionar los individuos
     * @param maximoRutas     cota superior del numero de rutas de un individuo
     */
    public Poblacion(ParametrosHgs parametros, int cantidadTareas, int maximoRutas) {
        this.parametros = parametros;
        this.capacidad = parametros.tamanoMinimoPoblacion() + parametros.tamanoGeneracion() + 1;
        this.individuos = new Individuo[capacidad];
        for (int i = 0; i < capacidad; i++) {
            individuos[i] = new Individuo(cantidadTareas, maximoRutas);
        }
        this.distancia = new double[capacidad][capacidad];
        this.ordenObjetivo = new int[capacidad];
        this.ordenDiversidad = new int[capacidad];
        this.rangoObjetivo = new int[capacidad];
        this.rangoDiversidad = new int[capacidad];
        this.proximas = new double[capacidad];
    }

    /** Numero de individuos vivos. */
    public int cantidad() {
        return cantidad;
    }

    /** Individuo en la posicion dada. */
    public Individuo individuo(int posicion) {
        return individuos[posicion];
    }

    /** Indica si la subpoblacion esta vacia. */
    public boolean vacia() {
        return cantidad == 0;
    }

    /**
     * Copia el individuo dado dentro de la subpoblacion y, si con el se alcanza el tamano
     * minimo mas el tamano de generacion, selecciona supervivientes.
     *
     * @return {@code true} si la insercion desencadeno una seleccion de supervivientes
     */
    public boolean insertar(Individuo nuevo) {
        if (cantidad == capacidad) {
            seleccionarSupervivientes();
        }
        final int posicion = cantidad;
        individuos[posicion].copiarDe(nuevo);
        for (int i = 0; i < posicion; i++) {
            double d = individuos[posicion].distancia(individuos[i]);
            distancia[posicion][i] = d;
            distancia[i][posicion] = d;
        }
        distancia[posicion][posicion] = 0.0;
        cantidad++;

        if (cantidad >= parametros.tamanoMinimoPoblacion() + parametros.tamanoGeneracion()) {
            seleccionarSupervivientes();
            return true;
        }
        return false;
    }

    /**
     * Mejor individuo por valor objetivo jerarquico, con la estabilidad desempatando dentro
     * del nivel 2, o {@code null} si esta vacia.
     */
    public Individuo mejor() {
        Individuo elegido = null;
        for (int i = 0; i < cantidad; i++) {
            if (individuos[i].mejorQue(elegido, parametros.pesoEstabilidad())) {
                elegido = individuos[i];
            }
        }
        return elegido;
    }

    /**
     * Recalcula la contribucion a la diversidad y la aptitud combinada de todos los
     * individuos vivos.
     */
    public void actualizarAptitudes() {
        if (cantidad == 0) {
            return;
        }
        final int cercanos = Math.min(parametros.vecinosProximosDiversidad(), Math.max(1, cantidad - 1));
        for (int i = 0; i < cantidad; i++) {
            individuos[i].contribucionDiversidad(distanciaMediaProxima(i, cercanos));
            ordenObjetivo[i] = i;
            ordenDiversidad[i] = i;
        }
        ordenarPorObjetivo(ordenObjetivo, cantidad);
        ordenarPorDiversidad(ordenDiversidad, cantidad);
        for (int posicion = 0; posicion < cantidad; posicion++) {
            rangoObjetivo[ordenObjetivo[posicion]] = posicion;
            rangoDiversidad[ordenDiversidad[posicion]] = posicion;
        }

        final double divisor = Math.max(1, cantidad - 1);
        final double pesoDiversidad = Math.max(0.0,
                1.0 - (double) parametros.individuosElite() / Math.max(1, cantidad));
        for (int i = 0; i < cantidad; i++) {
            double aptitud = rangoObjetivo[i] / divisor + pesoDiversidad * (rangoDiversidad[i] / divisor);
            individuos[i].aptitud(aptitud);
        }
    }

    /**
     * Reduce la subpoblacion al tamano minimo. Elimina primero los clones y despues los
     * individuos de peor aptitud combinada, con la supervivencia de los mejores por valor
     * objetivo garantizada.
     */
    public void seleccionarSupervivientes() {
        while (cantidad > parametros.tamanoMinimoPoblacion()) {
            actualizarAptitudes();
            int victima = elegirVictima();
            if (victima < 0) {
                return;
            }
            eliminar(victima);
        }
    }

    /**
     * Individuo a eliminar: el peor por aptitud combinada entre los que tienen un clon, y si
     * no hay clones el peor por aptitud combinada, saltando siempre a los de elite.
     */
    private int elegirVictima() {
        int victimaClon = -1;
        double peorClon = Double.NEGATIVE_INFINITY;
        int victima = -1;
        double peor = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cantidad; i++) {
            if (rangoObjetivo[i] < parametros.individuosElite()) {
                continue;
            }
            double aptitud = individuos[i].aptitud();
            if (tieneClon(i) && aptitud > peorClon) {
                peorClon = aptitud;
                victimaClon = i;
            }
            if (aptitud > peor) {
                peor = aptitud;
                victima = i;
            }
        }
        return victimaClon >= 0 ? victimaClon : victima;
    }

    private boolean tieneClon(int i) {
        for (int j = 0; j < cantidad; j++) {
            if (j != i && distancia[i][j] <= 0.0) {
                return true;
            }
        }
        return false;
    }

    /** Elimina el individuo intercambiandolo con el ultimo, tambien en la matriz de distancias. */
    private void eliminar(int posicion) {
        final int ultimo = cantidad - 1;
        if (posicion != ultimo) {
            Individuo intercambio = individuos[posicion];
            individuos[posicion] = individuos[ultimo];
            individuos[ultimo] = intercambio;
            for (int i = 0; i < cantidad; i++) {
                double d = distancia[ultimo][i];
                distancia[posicion][i] = d;
                distancia[i][posicion] = d;
            }
            distancia[posicion][posicion] = 0.0;
        }
        cantidad--;
    }

    /** Distancia media a los {@code cercanos} individuos mas proximos. */
    private double distanciaMediaProxima(int i, int cercanos) {
        int n = 0;
        for (int j = 0; j < cantidad; j++) {
            if (j == i) {
                continue;
            }
            double d = distancia[i][j];
            int posicion = n;
            while (posicion > 0 && proximas[posicion - 1] > d) {
                if (posicion < cercanos) {
                    proximas[posicion] = proximas[posicion - 1];
                }
                posicion--;
            }
            if (posicion < cercanos) {
                proximas[posicion] = d;
                if (n < cercanos) {
                    n++;
                }
            }
        }
        if (n == 0) {
            return 0.0;
        }
        double suma = 0.0;
        for (int k = 0; k < n; k++) {
            suma += proximas[k];
        }
        return suma / n;
    }

    /**
     * Ordenacion por insercion de indices segun el objetivo jerarquico, mejor primero. La
     * comparacion incluye el termino blando de estabilidad del apartado 11.4, de modo que el
     * rango por valor objetivo con el que se arma la aptitud combinada, y con ella el torneo de
     * seleccion y la supervivencia, tambien prefiere el plan que se aparta menos del vigente.
     */
    private void ordenarPorObjetivo(int[] orden, int n) {
        final double pesoEstabilidad = parametros.pesoEstabilidad();
        for (int i = 1; i < n; i++) {
            int actual = orden[i];
            int j = i - 1;
            while (j >= 0 && individuos[actual].mejorQue(individuos[orden[j]], pesoEstabilidad)) {
                orden[j + 1] = orden[j];
                j--;
            }
            orden[j + 1] = actual;
        }
    }

    /** Ordenacion por insercion de indices segun la diversidad, mayor contribucion primero. */
    private void ordenarPorDiversidad(int[] orden, int n) {
        for (int i = 1; i < n; i++) {
            int actual = orden[i];
            double clave = individuos[actual].contribucionDiversidad();
            int j = i - 1;
            while (j >= 0 && individuos[orden[j]].contribucionDiversidad() < clave) {
                orden[j + 1] = orden[j];
                j--;
            }
            orden[j + 1] = actual;
        }
    }
}
