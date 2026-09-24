package org.kindbox.core.metaheuristica.hgs;

import org.kindbox.core.problema.ValorObjetivo;

/**
 * Individuo de la busqueda genetica hibrida, conforme a la representacion del apartado
 * 6.3.1 del ISA.
 *
 * <p>El cromosoma tiene dos partes. La primera es una <b>permutacion de las tareas de
 * entrega sin delimitadores de ruta</b>, el llamado giant tour de Prins (2004): no lleva
 * marcas de corte porque el corte optimo lo calcula el Split, de modo que el espacio de
 * busqueda queda libre de la redundancia que introduciria codificar el reparto en rutas. La
 * segunda es un <b>vector que asigna a cada tarea un tipo de unidad de transporte</b>, que
 * es lo que hace explorable una flota heterogenea: ese vector decide que tipos considera el
 * Split para cada arco de su grafo auxiliar.</p>
 *
 * <p>Junto al cromosoma se guarda su decodificacion, que es el resultado del Split y de la
 * educacion: las rutas ya asignadas a unidades concretas, las tareas que quedaron sin
 * asignar y el valor de la solucion. Todo vive en arreglos primitivos dimensionados una
 * sola vez, porque en un presupuesto de entre 2 y 18 segundos se decodifican y educan miles
 * de individuos y ninguna de esas operaciones puede asignar memoria.</p>
 *
 * <p>El arreglo de sucesores sostiene la medida de distancia entre individuos con la que el
 * control adaptativo de la diversidad calcula la aptitud combinada (apartado 6.3.3).</p>
 */
public final class Individuo {

    private final int[] permutacion;
    private final byte[] tipo;

    private final int[] visitas;
    private final int[] rutaInicio;
    private final int[] rutaLongitud;
    private final int[] rutaUnidad;
    private final double[] rutaCosto;
    private final int[] rutaDesfase;
    private int cantidadRutas;

    private final int[] banco;
    private int cantidadBanco;

    private final int[] sucesor;

    private int pedidosNoAtendidos;
    /** Suma de la urgencia de los pedidos con remanente sin asignar; desempata a igual H. */
    private double urgencia;
    private double costo;
    private int desfase;
    private int desviacionPlan;

    /** Aptitud combinada de valor objetivo y diversidad, que fija la subpoblacion. */
    private double aptitud;
    /** Distancia media a los individuos mas proximos de su subpoblacion. */
    private double contribucionDiversidad;

    /**
     * @param cantidadTareas longitud del cromosoma
     * @param maximoRutas    cota superior del numero de rutas, el numero de unidades
     */
    public Individuo(int cantidadTareas, int maximoRutas) {
        this.permutacion = new int[cantidadTareas];
        this.tipo = new byte[cantidadTareas];
        this.visitas = new int[cantidadTareas];
        this.banco = new int[cantidadTareas];
        this.sucesor = new int[cantidadTareas];
        int rutas = Math.max(1, maximoRutas);
        this.rutaInicio = new int[rutas];
        this.rutaLongitud = new int[rutas];
        this.rutaUnidad = new int[rutas];
        this.rutaCosto = new double[rutas];
        this.rutaDesfase = new int[rutas];
        this.pedidosNoAtendidos = Integer.MAX_VALUE;
        this.costo = Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------- cromosoma

    /** Permutacion de tareas de entrega, sin delimitadores de ruta. */
    public int[] permutacion() {
        return permutacion;
    }

    /** Tipo de unidad asignado a cada tarea, por el ordinal de {@code TipoUnidad}. */
    public byte[] tipo() {
        return tipo;
    }

    /** Longitud del cromosoma. */
    public int longitud() {
        return permutacion.length;
    }

    // --------------------------------------------------------- decodificacion

    /** Tareas de todas las rutas, agrupadas por ruta y en orden de visita. */
    public int[] visitas() {
        return visitas;
    }

    /** Posicion en {@link #visitas()} en que arranca cada ruta. */
    public int[] rutaInicio() {
        return rutaInicio;
    }

    /** Numero de tareas de cada ruta. */
    public int[] rutaLongitud() {
        return rutaLongitud;
    }

    /** Indice local de la unidad que atiende cada ruta. */
    public int[] rutaUnidad() {
        return rutaUnidad;
    }

    /** Costo de operacion de cada ruta, en soles. */
    public double[] rutaCosto() {
        return rutaCosto;
    }

    /**
     * Minutos de violacion temporal de cada ruta. Distinguir la ruta que incumple de la que
     * cumple es lo que permite saber, sin materializar la solucion, cuanto vale la parte del
     * individuo que si es entregable.
     */
    public int[] rutaDesfase() {
        return rutaDesfase;
    }

    /** Numero de rutas con al menos una entrega. */
    public int cantidadRutas() {
        return cantidadRutas;
    }

    public void cantidadRutas(int valor) {
        this.cantidadRutas = valor;
    }

    /** Tareas que quedaron sin asignacion factible. */
    public int[] banco() {
        return banco;
    }

    /** Numero de tareas sin asignar. */
    public int cantidadBanco() {
        return cantidadBanco;
    }

    public void cantidadBanco(int valor) {
        this.cantidadBanco = valor;
    }

    /** Tarea que sigue a la dada dentro de su ruta, o {@code -1} si es la ultima o esta en el banco. */
    public int[] sucesor() {
        return sucesor;
    }

    // ------------------------------------------------------------- evaluacion

    /** Numero de pedidos con remanente sin asignar. Es la H del nivel 1 del objetivo. */
    public int pedidosNoAtendidos() {
        return pedidosNoAtendidos;
    }

    public void pedidosNoAtendidos(int valor) {
        this.pedidosNoAtendidos = valor;
    }

    /** Suma de 1/(holgura+1) de los pedidos con remanente sin asignar, como en {@link ValorObjetivo}. */
    public double urgencia() {
        return urgencia;
    }

    public void urgencia(double valor) {
        this.urgencia = valor;
    }

    /** Costo de operacion de las rutas, en soles. */
    public double costo() {
        return costo;
    }

    public void costo(double valor) {
        this.costo = valor;
    }

    /** Minutos totales de violacion temporal acumulados por las rutas. */
    public int desfase() {
        return desfase;
    }

    public void desfase(int valor) {
        this.desfase = valor;
    }

    /**
     * Pedidos que cambian de unidad respecto del plan vigente, es decir la desviacion del
     * apartado 11.4 del ISA medida sobre las rutas de este individuo. La calcula
     * {@link EstabilidadPlan} y la dejan aqui el {@link Split} y la {@link Educacion}.
     */
    public int desviacionPlan() {
        return desviacionPlan;
    }

    public void desviacionPlan(int valor) {
        this.desviacionPlan = valor;
    }

    /**
     * Indica si el individuo pertenece a la subpoblacion factible: ninguna de sus rutas
     * incumple un plazo ni se pasa del cierre del turno. Las tareas del banco no lo vuelven
     * infactible, porque no atender un pedido es el nivel 1 del objetivo y no una violacion
     * de restriccion dura.
     */
    public boolean factible() {
        return desfase == 0;
    }

    /** Valor de la funcion objetivo jerarquica que corresponde a la decodificacion. */
    public ValorObjetivo valor() {
        return new ValorObjetivo(pedidosNoAtendidos, urgencia, costo, 0.0);
    }

    /**
     * Costo de operacion mas la penalizacion blanda de estabilidad. Es el nivel 2 del objetivo
     * tal como lo ve la busqueda interna: el costo del apartado 2.5 del ISA mas el termino del
     * apartado 11.4, que desempata dentro de ese nivel y nunca por encima de el.
     */
    public double costoPenalizado(double pesoEstabilidad) {
        return costo + pesoEstabilidad * desviacionPlan;
    }

    /**
     * Valor con el que la busqueda interna compara individuos: costo de operacion mas la
     * penalizacion de las tareas sin atender, mas la penalizacion dinamica del desfase, mas la
     * penalizacion blanda de la desviacion respecto del plan vigente.
     */
    public double costoInterno(double pesoDesfase, double pesoEstabilidad) {
        return costo + ParametrosHgs.PENALIZACION_TAREA_NO_ATENDIDA * (cantidadBanco + urgencia)
                + pesoDesfase * desfase + pesoEstabilidad * desviacionPlan;
    }

    /**
     * Indica si este individuo es mejor que el otro segun el objetivo jerarquico (H, luego la
     * urgencia de lo no atendido, luego el costo), con la
     * estabilidad desempatando dentro del nivel 2. Es la comparacion que fija el rango por
     * valor objetivo de la aptitud combinada del apartado 6.3.3, y por tanto la que lleva el
     * termino de estabilidad hasta la seleccion de progenitores y la supervivencia.
     */
    public boolean mejorQue(Individuo otro, double pesoEstabilidad) {
        if (otro == null) {
            return true;
        }
        if (pedidosNoAtendidos != otro.pedidosNoAtendidos) {
            return pedidosNoAtendidos < otro.pedidosNoAtendidos;
        }
        if (Math.abs(urgencia - otro.urgencia) > ValorObjetivo.TOLERANCIA_URGENCIA) {
            return urgencia < otro.urgencia;
        }
        return costoPenalizado(pesoEstabilidad) < otro.costoPenalizado(pesoEstabilidad);
    }

    // ---------------------------------------------------------------- aptitud

    /** Aptitud combinada de rango de valor objetivo y rango de contribucion a la diversidad. */
    public double aptitud() {
        return aptitud;
    }

    public void aptitud(double valor) {
        this.aptitud = valor;
    }

    /** Distancia media a los individuos mas proximos de su subpoblacion. */
    public double contribucionDiversidad() {
        return contribucionDiversidad;
    }

    public void contribucionDiversidad(double valor) {
        this.contribucionDiversidad = valor;
    }

    // ----------------------------------------------------------------- copias

    /** Copia el estado completo del otro individuo sobre este, sin asignar memoria. */
    public void copiarDe(Individuo otro) {
        System.arraycopy(otro.permutacion, 0, permutacion, 0, permutacion.length);
        System.arraycopy(otro.tipo, 0, tipo, 0, tipo.length);
        System.arraycopy(otro.visitas, 0, visitas, 0, visitas.length);
        System.arraycopy(otro.sucesor, 0, sucesor, 0, sucesor.length);
        System.arraycopy(otro.banco, 0, banco, 0, banco.length);
        System.arraycopy(otro.rutaInicio, 0, rutaInicio, 0, rutaInicio.length);
        System.arraycopy(otro.rutaLongitud, 0, rutaLongitud, 0, rutaLongitud.length);
        System.arraycopy(otro.rutaUnidad, 0, rutaUnidad, 0, rutaUnidad.length);
        System.arraycopy(otro.rutaCosto, 0, rutaCosto, 0, rutaCosto.length);
        System.arraycopy(otro.rutaDesfase, 0, rutaDesfase, 0, rutaDesfase.length);
        this.cantidadRutas = otro.cantidadRutas;
        this.cantidadBanco = otro.cantidadBanco;
        this.pedidosNoAtendidos = otro.pedidosNoAtendidos;
        this.urgencia = otro.urgencia;
        this.costo = otro.costo;
        this.desfase = otro.desfase;
        this.desviacionPlan = otro.desviacionPlan;
        this.aptitud = otro.aptitud;
        this.contribucionDiversidad = otro.contribucionDiversidad;
    }

    /**
     * Distancia normalizada a otro individuo, en {@code [0,1]}. Es la distancia de pares
     * rotos de Prins (2009): se cuentan las tareas cuyo sucesor difiere, mas las tareas cuyo
     * tipo de unidad asignado difiere, porque en una flota heterogenea dos soluciones con el
     * mismo orden de visita pero distinto reparto por tipo son estructuralmente distintas.
     * Es la medida con la que el control adaptativo de la diversidad del apartado 6.3.3
     * decide si un individuo aporta algo a la poblacion.
     */
    public double distancia(Individuo otro) {
        final int n = permutacion.length;
        if (n == 0) {
            return 0.0;
        }
        int diferencias = 0;
        for (int t = 0; t < n; t++) {
            if (sucesor[t] != otro.sucesor[t]) {
                diferencias++;
            }
            if (tipo[t] != otro.tipo[t]) {
                diferencias++;
            }
        }
        return diferencias / (2.0 * n);
    }

    @Override
    public String toString() {
        return "Individuo[H=" + pedidosNoAtendidos + " costo=" + String.format("%.2f", costo)
                + " desfase=" + desfase + " desviacion=" + desviacionPlan
                + " rutas=" + cantidadRutas + " banco=" + cantidadBanco + "]";
    }
}
