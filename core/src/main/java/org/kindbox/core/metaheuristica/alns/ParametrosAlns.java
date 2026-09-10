package org.kindbox.core.metaheuristica.alns;

/**
 * Parametros calibrables de la busqueda adaptativa de vecindad amplia, conforme al
 * apartado 7.4 del ISA.
 *
 * <p>Los valores por defecto son los que el apartado fija como punto de partida: grado de
 * destruccion entre el 5 y el 40 por ciento de los pedidos ya colocados, longitud maxima de
 * cadena 10, probabilidad de parpadeo entre 0.01 y 0.10, orden de arrepentimiento entre 2 y
 * 3, segmento adaptativo de 100 iteraciones y tasa de reaccion entre 0.1 y 0.5. El resto de
 * valores son los habituales de Ropke y Pisinger (2006) y de Christiaens y Vanden Berghe
 * (2020).</p>
 *
 * <p>La clase es un contenedor con validacion y encadenamiento de asignaciones, no un
 * registro inmutable, porque el banco de experimentos del apartado 12 necesita barrer cada
 * parametro por separado sin reescribir la construccion entera.</p>
 */
public final class ParametrosAlns {

    private double fraccionMinimaDestruccion = 0.05;
    private double fraccionMaximaDestruccion = 0.40;
    private int maximoAbsolutoDestruccion = 20;
    private int longitudMaximaCadena = 10;
    private double parpadeoMinimo = 0.01;
    private double parpadeoMaximo = 0.10;
    private int ordenArrepentimientoMinimo = 2;
    private int ordenArrepentimientoMaximo = 3;
    private int longitudSegmento = 100;
    private double tasaReaccion = 0.30;

    private double puntajeNuevaMejor = 33.0;
    private double puntajeMejora = 9.0;
    private double puntajeAceptada = 13.0;
    private double pesoMinimoOperador = 0.05;

    private double fraccionTemperaturaInicial = 0.05;
    private double fraccionTemperaturaFinal = 1.0e-5;
    private double factorPenalizacionBanco = 10.0;

    private long maximoIteracionesSinMejora = 0L;
    private long iteracionesParaReinicio = 1500L;

    private double determinismoSeleccion = 3.0;
    private int ventanaVecindad = 40;
    private int maximoUnidadesCandidatas = 12;

    private double pesoAfinidadDistancia = 0.6;
    private double pesoAfinidadPlazo = 0.3;
    private double pesoAfinidadUnidad = 0.1;

    /** Parametros con los valores iniciales del apartado 7.4 del ISA. */
    public static ParametrosAlns porDefecto() {
        return new ParametrosAlns();
    }

    // ------------------------------------------------- grado de destruccion

    /** Fraccion minima de los pedidos colocados que retira un operador de destruccion. */
    public double fraccionMinimaDestruccion() {
        return fraccionMinimaDestruccion;
    }

    /** Fraccion maxima de los pedidos colocados que retira un operador de destruccion. */
    public double fraccionMaximaDestruccion() {
        return fraccionMaximaDestruccion;
    }

    /** Fija el rango del grado de destruccion, expresado en fraccion de pedidos colocados. */
    public ParametrosAlns rangoDestruccion(double minima, double maxima) {
        if (minima <= 0.0 || maxima < minima || maxima > 1.0) {
            throw new IllegalArgumentException("Rango de destruccion invalido: " + minima + ".." + maxima);
        }
        this.fraccionMinimaDestruccion = minima;
        this.fraccionMaximaDestruccion = maxima;
        return this;
    }

    /**
     * Tope absoluto de pedidos retirados por iteracion. Con el presupuesto de 2 a 18
     * segundos del apartado 2.3 una destruccion muy grande consume la iteracion entera en
     * la reconstruccion, de modo que el tope cambia iteraciones largas por muchas cortas.
     * Es el mismo recurso que Ropke y Pisinger (2006) emplean con su cota de 100, calibrado
     * aqui para un presupuesto tres ordenes de magnitud menor: sobre una fotografia saturada
     * de 307 pedidos pendientes, bajar el tope de 80 a 20 sube las iteraciones de 3 700 a
     * 4 800 en quince segundos y baja a la vez H y el costo.
     */
    public int maximoAbsolutoDestruccion() {
        return maximoAbsolutoDestruccion;
    }

    public ParametrosAlns maximoAbsolutoDestruccion(int maximo) {
        exigirPositivo(maximo, "maximo absoluto de destruccion");
        this.maximoAbsolutoDestruccion = maximo;
        return this;
    }

    // ------------------------------------------------------------ operadores

    /** Longitud maxima de una cadena de paradas contiguas retirada por el operador de cadenas. */
    public int longitudMaximaCadena() {
        return longitudMaximaCadena;
    }

    public ParametrosAlns longitudMaximaCadena(int longitud) {
        exigirPositivo(longitud, "longitud maxima de cadena");
        this.longitudMaximaCadena = longitud;
        return this;
    }

    /** Extremo inferior de la probabilidad de parpadeo de la insercion voraz con parpadeo. */
    public double parpadeoMinimo() {
        return parpadeoMinimo;
    }

    /** Extremo superior de la probabilidad de parpadeo de la insercion voraz con parpadeo. */
    public double parpadeoMaximo() {
        return parpadeoMaximo;
    }

    public ParametrosAlns rangoParpadeo(double minimo, double maximo) {
        if (minimo < 0.0 || maximo < minimo || maximo >= 1.0) {
            throw new IllegalArgumentException("Rango de parpadeo invalido: " + minimo + ".." + maximo);
        }
        this.parpadeoMinimo = minimo;
        this.parpadeoMaximo = maximo;
        return this;
    }

    /** Orden minimo del arrepentimiento. */
    public int ordenArrepentimientoMinimo() {
        return ordenArrepentimientoMinimo;
    }

    /** Orden maximo del arrepentimiento. */
    public int ordenArrepentimientoMaximo() {
        return ordenArrepentimientoMaximo;
    }

    public ParametrosAlns rangoArrepentimiento(int minimo, int maximo) {
        if (minimo < 2 || maximo < minimo) {
            throw new IllegalArgumentException("Rango de arrepentimiento invalido: " + minimo + ".." + maximo);
        }
        this.ordenArrepentimientoMinimo = minimo;
        this.ordenArrepentimientoMaximo = maximo;
        return this;
    }

    /**
     * Exponente de la seleccion sesgada de Ropke y Pisinger (2006): sobre una lista ordenada
     * de {@code n} candidatos se elige el de indice {@code (int) (y^d * n)} con {@code y}
     * uniforme en {@code [0,1)}. Con {@code d} igual a uno la eleccion es uniforme y cuanto
     * mayor es {@code d} mas se concentra en los primeros puestos.
     */
    public double determinismoSeleccion() {
        return determinismoSeleccion;
    }

    public ParametrosAlns determinismoSeleccion(double determinismo) {
        if (determinismo < 1.0) {
            throw new IllegalArgumentException("Determinismo de seleccion menor que uno: " + determinismo);
        }
        this.determinismoSeleccion = determinismo;
        return this;
    }

    /**
     * Numero de puntos de {@code MatrizDistancias.vecinosCercanos} que se recorren al
     * construir un vecindario. Es la granularidad del vecindario del apartado 10 del ISA.
     */
    public int ventanaVecindad() {
        return ventanaVecindad;
    }

    public ParametrosAlns ventanaVecindad(int ventana) {
        exigirPositivo(ventana, "ventana de vecindad");
        this.ventanaVecindad = ventana;
        return this;
    }

    /**
     * Numero maximo de unidades con ruta no vacia que se consideran al insertar un pedido.
     * Las unidades con ruta vacia se consideran siempre, porque valorarlas cuesta una sola
     * posicion y son la unica via de abrir una ruta nueva.
     */
    public int maximoUnidadesCandidatas() {
        return maximoUnidadesCandidatas;
    }

    public ParametrosAlns maximoUnidadesCandidatas(int maximo) {
        exigirPositivo(maximo, "maximo de unidades candidatas");
        this.maximoUnidadesCandidatas = maximo;
        return this;
    }

    /** Peso de la proximidad geografica en la relacion de Shaw (1998). */
    public double pesoAfinidadDistancia() {
        return pesoAfinidadDistancia;
    }

    /** Peso de la cercania de plazo en la relacion de Shaw (1998). */
    public double pesoAfinidadPlazo() {
        return pesoAfinidadPlazo;
    }

    /** Peso de la coincidencia de unidad asignada en la relacion de Shaw (1998). */
    public double pesoAfinidadUnidad() {
        return pesoAfinidadUnidad;
    }

    public ParametrosAlns pesosAfinidad(double distancia, double plazo, double unidad) {
        if (distancia < 0.0 || plazo < 0.0 || unidad < 0.0 || distancia + plazo + unidad <= 0.0) {
            throw new IllegalArgumentException("Pesos de afinidad invalidos");
        }
        this.pesoAfinidadDistancia = distancia;
        this.pesoAfinidadPlazo = plazo;
        this.pesoAfinidadUnidad = unidad;
        return this;
    }

    // ------------------------------------------------------- capa adaptativa

    /** Iteraciones que componen un segmento de actualizacion de pesos. */
    public int longitudSegmento() {
        return longitudSegmento;
    }

    public ParametrosAlns longitudSegmento(int longitud) {
        exigirPositivo(longitud, "longitud de segmento");
        this.longitudSegmento = longitud;
        return this;
    }

    /** Tasa de reaccion de la actualizacion de pesos. El apartado 7.4 la situa entre 0.1 y 0.5. */
    public double tasaReaccion() {
        return tasaReaccion;
    }

    public ParametrosAlns tasaReaccion(double tasa) {
        if (tasa <= 0.0 || tasa > 1.0) {
            throw new IllegalArgumentException("Tasa de reaccion fuera de (0,1]: " + tasa);
        }
        this.tasaReaccion = tasa;
        return this;
    }

    /** Puntuacion de una iteracion que produce una nueva mejor solucion global. */
    public double puntajeNuevaMejor() {
        return puntajeNuevaMejor;
    }

    /** Puntuacion de una iteracion que mejora la solucion vigente. */
    public double puntajeMejora() {
        return puntajeMejora;
    }

    /** Puntuacion de una iteracion aceptada por el recocido pese a ser peor. */
    public double puntajeAceptada() {
        return puntajeAceptada;
    }

    public ParametrosAlns puntajes(double nuevaMejor, double mejora, double aceptada) {
        if (nuevaMejor < 0.0 || mejora < 0.0 || aceptada < 0.0) {
            throw new IllegalArgumentException("Puntajes negativos en la capa adaptativa");
        }
        this.puntajeNuevaMejor = nuevaMejor;
        this.puntajeMejora = mejora;
        this.puntajeAceptada = aceptada;
        return this;
    }

    /** Peso minimo de un operador, que impide que la ruleta lo apague por completo. */
    public double pesoMinimoOperador() {
        return pesoMinimoOperador;
    }

    public ParametrosAlns pesoMinimoOperador(double peso) {
        if (peso <= 0.0) {
            throw new IllegalArgumentException("Peso minimo de operador no positivo: " + peso);
        }
        this.pesoMinimoOperador = peso;
        return this;
    }

    // ------------------------------------------------------------- aceptacion

    /**
     * Temperatura inicial, expresada como la fraccion de empeoramiento del costo de la
     * solucion inicial que se acepta con probabilidad un medio.
     */
    public double fraccionTemperaturaInicial() {
        return fraccionTemperaturaInicial;
    }

    /**
     * Temperatura final, expresada como fraccion del costo de la solucion inicial. Al
     * agotarse el presupuesto la probabilidad de aceptar un empeoramiento debe ser
     * despreciable.
     */
    public double fraccionTemperaturaFinal() {
        return fraccionTemperaturaFinal;
    }

    public ParametrosAlns temperaturas(double fraccionInicial, double fraccionFinal) {
        if (fraccionInicial <= 0.0 || fraccionFinal <= 0.0 || fraccionFinal >= fraccionInicial) {
            throw new IllegalArgumentException("Fracciones de temperatura invalidas: "
                    + fraccionInicial + " y " + fraccionFinal);
        }
        this.fraccionTemperaturaInicial = fraccionInicial;
        this.fraccionTemperaturaFinal = fraccionFinal;
        return this;
    }

    /**
     * Multiplo del costo medio por pedido atendido con que se penaliza cada pedido del banco
     * en el escalar interno de aceptacion. Debe bastar para que abandonar un pedido nunca
     * resulte rentable, que es como el nivel 1 del objetivo se traslada al recocido.
     */
    public double factorPenalizacionBanco() {
        return factorPenalizacionBanco;
    }

    public ParametrosAlns factorPenalizacionBanco(double factor) {
        if (factor <= 0.0) {
            throw new IllegalArgumentException("Factor de penalizacion del banco no positivo: " + factor);
        }
        this.factorPenalizacionBanco = factor;
        return this;
    }

    // ------------------------------------------------------- criterio de parada

    /**
     * Iteraciones consecutivas sin mejorar la mejor solucion tras las cuales la corrida se
     * detiene, con {@code 0} para dejar que mande solo el presupuesto. Es el criterio de
     * parada del apartado 7.3.4, identico en estructura al de la busqueda genetica hibrida
     * pero referido a iteraciones en lugar de generaciones. Bajo el presupuesto de 2 a 18
     * segundos del apartado 2.3 la parada efectiva es casi siempre el reloj, de modo que el
     * valor por defecto no lo activa.
     */
    public long maximoIteracionesSinMejora() {
        return maximoIteracionesSinMejora;
    }

    public ParametrosAlns maximoIteracionesSinMejora(long iteraciones) {
        if (iteraciones < 0L) {
            throw new IllegalArgumentException("Maximo de iteraciones sin mejora negativo");
        }
        this.maximoIteracionesSinMejora = iteraciones;
        return this;
    }

    /**
     * Iteraciones consecutivas sin mejorar la mejor solucion tras las cuales la solucion
     * vigente vuelve a la mejor conocida. Evita que el recocido deje la busqueda vagando por
     * una region peor durante el tramo caliente sin llegar a detenerla.
     */
    public long iteracionesParaReinicio() {
        return iteracionesParaReinicio;
    }

    public ParametrosAlns iteracionesParaReinicio(long iteraciones) {
        if (iteraciones <= 0L) {
            throw new IllegalArgumentException("Iteraciones para reinicio no positivas");
        }
        this.iteracionesParaReinicio = iteraciones;
        return this;
    }

    private static void exigirPositivo(int valor, String que) {
        if (valor <= 0) {
            throw new IllegalArgumentException("Valor no positivo de " + que + ": " + valor);
        }
    }

    @Override
    public String toString() {
        return "ParametrosAlns[q=" + fraccionMinimaDestruccion + ".." + fraccionMaximaDestruccion
                + " tope=" + maximoAbsolutoDestruccion + " cadena=" + longitudMaximaCadena
                + " parpadeo=" + parpadeoMinimo + ".." + parpadeoMaximo
                + " k=" + ordenArrepentimientoMinimo + ".." + ordenArrepentimientoMaximo
                + " segmento=" + longitudSegmento + " reaccion=" + tasaReaccion + "]";
    }
}
