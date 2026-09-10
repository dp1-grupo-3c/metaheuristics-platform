package org.kindbox.core.simulacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.kindbox.core.modelo.Averia;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.util.Aleatorio;

/**
 * Reglas de generacion de averias del motor de simulacion.
 *
 * <p>La respuesta 3 del cuestionario, recogida en el apartado 7 del documento de dominio,
 * dice que las averias llegan por dos vias: el registro desde el visualizador, individual o
 * por carga masiva de archivo, y unas reglas de generacion dentro de la simulacion. El
 * equipo docente <b>no publico</b> esas reglas, de modo que las que siguen son una
 * <b>decision propia del equipo</b> y se documentan aqui como tal.</p>
 *
 * <h2>Regla 1: solo se averia lo que puede operar durante el turno</h2>
 * <p>El sorteo se aplica una vez por unidad y por turno, con la probabilidad
 * {@code averiasPorUnidadPorTurno} de {@link ConfiguracionEscenario}, sobre las unidades
 * que <em>pueden operar</em> en el turno que arranca. Quedan fuera unicamente las que ya
 * estan fuera de servicio al empezarlo: {@link EstadoUnidad#AVERIADA} y
 * {@link EstadoUnidad#EN_MANTENIMIENTO}.</p>
 *
 * <p>La condicion de que la averia le ocurra a una unidad <em>en operacion</em> sigue en
 * pie, pero se comprueba donde corresponde: en el instante del incidente y por el motor de
 * simulacion, que descarta la averia generada si al dispararse el suceso la unidad no esta
 * circulando ni detenida en una parada. Exigirla ademas en el instante del sorteo la
 * vaciaba de contenido: el motor consulta este generador al atender
 * {@link TipoEvento#CAMBIO_TURNO}, y en ese instante exacto ninguna unidad esta en ruta,
 * porque los itinerarios se programan para cerrar antes del fin del turno y el plan del
 * turno nuevo aun no se ha comprometido. Con el filtro anterior una corrida de cinco dias
 * generaba <b>cero</b> averias incluso con probabilidad uno; el filtro se mantenia
 * verdadero en las pruebas unitarias, que fijan el estado a mano, y falso en la
 * simulacion.</p>
 *
 * <h2>Regla 2: la probabilidad depende del tipo de unidad</h2>
 * <p>La probabilidad configurada se modula por un factor por tipo. Se eligen
 * {@value #FACTOR_AUTO} para los autos, {@value #FACTOR_MOTO} para las motos y
 * {@value #FACTOR_BICICLETA} para las bicicletas, de modo que las bicicletas se averien mas
 * que las motos y estas mas que los autos. La justificacion es doble. Por un lado la
 * robustez mecanica: un auto es un vehiculo cerrado con mantenimiento preventivo
 * programado, una moto esta mas expuesta y una bicicleta concentra los fallos tipicos de
 * pinchazo, cadena y frenos, que son frecuentes y no requieren taller. Por otro lado la
 * exposicion: a igualdad de turno la bicicleta circula a 12 Km/h frente a los 40 Km/h del
 * auto, de modo que atraviesa muchas mas esquinas y bordillos por kilometro util. El factor
 * de la moto se fija en 1.0 a proposito, con lo que el parametro configurado se lee
 * directamente como la probabilidad por turno de una moto y los otros dos tipos quedan
 * referidos a ella. Los tres factores son parametricos: el constructor largo los recibe.</p>
 *
 * <h2>Regla 3: reparto entre los tres tipos de averia</h2>
 * <p>Producida la averia, su tipo se sortea con el reparto {@link #REPARTO_POR_DEFECTO}, es
 * decir un {@value #PORCENTAJE_TIPO_1} por ciento de tipo 1, un {@value #PORCENTAJE_TIPO_2}
 * por ciento de tipo 2 y un {@value #PORCENTAJE_TIPO_3} por ciento de tipo 3. Las menores
 * son mucho mas frecuentes que las mayores porque el tipo 3 implica al menos dos dias de
 * taller (apartado 7) y una flota en la que uno de cada veinte incidentes retira la unidad
 * dos dias ya es un escenario exigente. El reparto tambien es parametrico.</p>
 *
 * <h2>Reproducibilidad</h2>
 * <p>La generacion es determinista a partir de la semilla de la corrida. El sorteo de cada
 * unidad en cada turno usa su propia corriente de {@link Aleatorio}, sembrada mezclando la
 * semilla de la configuracion con el instante de arranque del turno y con el codigo de la
 * unidad. Esa eleccion es mas fuerte que llevar una unica corriente compartida: el
 * resultado de una unidad en un turno no depende de cuantas unidades hayan entrado antes en
 * el sorteo ni de cuantos turnos se hayan simulado, de modo que dos corridas con la misma
 * semilla producen la misma secuencia de averias aunque una arranque a mitad del horizonte.
 * Se usa {@link Aleatorio} y nunca {@code java.util.Random}, conforme al apartado 10 del
 * ISA.</p>
 *
 * <p>La clase es inmutable y no guarda estado entre llamadas, de modo que puede compartirse
 * entre hilos.</p>
 */
public final class GeneradorAverias {

    /** Factor de la probabilidad de averia de un auto, el vehiculo mas robusto. */
    public static final double FACTOR_AUTO = 0.60;
    /** Factor de la probabilidad de averia de una moto, que es la referencia. */
    public static final double FACTOR_MOTO = 1.00;
    /** Factor de la probabilidad de averia de una bicicleta, el vehiculo mas fragil. */
    public static final double FACTOR_BICICLETA = 1.80;

    /** Porcentaje de averias menores, de tipo 1. */
    public static final int PORCENTAJE_TIPO_1 = 70;
    /** Porcentaje de averias intermedias, de tipo 2. */
    public static final int PORCENTAJE_TIPO_2 = 25;
    /** Porcentaje de averias mayores, de tipo 3. */
    public static final int PORCENTAJE_TIPO_3 = 5;

    /**
     * Factores por tipo de unidad, indexados por {@code TipoUnidad.ordinal()}: auto, moto
     * y bicicleta en ese orden.
     */
    public static final double[] FACTORES_POR_DEFECTO = {FACTOR_AUTO, FACTOR_MOTO, FACTOR_BICICLETA};

    /** Reparto entre averias de tipo 1, 2 y 3, en ese orden. */
    public static final double[] REPARTO_POR_DEFECTO = {
            PORCENTAJE_TIPO_1 / 100.0, PORCENTAJE_TIPO_2 / 100.0, PORCENTAJE_TIPO_3 / 100.0};

    /** Tipos de averia en el orden en que los pondera el reparto. */
    private static final TipoAveria[] TIPOS_AVERIA = {TipoAveria.TIPO_1, TipoAveria.TIPO_2, TipoAveria.TIPO_3};

    /** Desplazamiento de dispersion de SplitMix64, para mezclar la semilla del sorteo. */
    private static final long DISPERSION_A = 0x9E3779B97F4A7C15L;
    private static final long DISPERSION_B = 0xBF58476D1CE4E5B9L;
    private static final long DISPERSION_C = 0x94D049BB133111EBL;
    /** Constantes de FNV-1a de 64 bits, para el resumen del codigo de unidad. */
    private static final long FNV_BASE = 0xCBF29CE484222325L;
    private static final long FNV_PRIMO = 0x100000001B3L;

    private final long semilla;
    private final double probabilidadPorUnidadPorTurno;
    private final double[] factorPorTipoUnidad;
    private final double[] repartoPorTipoAveria;
    private final boolean activo;

    /**
     * Generador con los factores y el reparto por defecto.
     *
     * @param semilla                       semilla de la corrida
     * @param probabilidadPorUnidadPorTurno probabilidad de que una moto en ruta se averie
     *                                      durante un turno completo
     */
    public GeneradorAverias(long semilla, double probabilidadPorUnidadPorTurno) {
        this(semilla, probabilidadPorUnidadPorTurno, FACTORES_POR_DEFECTO, REPARTO_POR_DEFECTO, true);
    }

    /**
     * Generador con todas las reglas parametrizadas.
     *
     * @param semilla                       semilla de la corrida
     * @param probabilidadPorUnidadPorTurno probabilidad de referencia, la de una moto
     * @param factorPorTipoUnidad           factor por tipo, indexado por {@code TipoUnidad.ordinal()}
     * @param repartoPorTipoAveria          pesos de los tipos de averia 1, 2 y 3
     * @param activo                        si las reglas estan habilitadas en esta corrida
     */
    public GeneradorAverias(long semilla, double probabilidadPorUnidadPorTurno,
                            double[] factorPorTipoUnidad, double[] repartoPorTipoAveria, boolean activo) {
        if (probabilidadPorUnidadPorTurno < 0.0 || probabilidadPorUnidadPorTurno > 1.0
                || !Double.isFinite(probabilidadPorUnidadPorTurno)) {
            throw new IllegalArgumentException(
                    "Probabilidad de averia fuera de [0,1]: " + probabilidadPorUnidadPorTurno);
        }
        if (factorPorTipoUnidad == null || factorPorTipoUnidad.length != TipoUnidad.values().length) {
            throw new IllegalArgumentException("Se necesita un factor por cada tipo de unidad ("
                    + TipoUnidad.values().length + ")");
        }
        for (double f : factorPorTipoUnidad) {
            if (f < 0.0 || !Double.isFinite(f)) {
                throw new IllegalArgumentException("Factor de averia invalido: " + f);
            }
        }
        if (repartoPorTipoAveria == null || repartoPorTipoAveria.length != TIPOS_AVERIA.length) {
            throw new IllegalArgumentException("El reparto necesita un peso por cada tipo de averia ("
                    + TIPOS_AVERIA.length + ")");
        }
        double total = 0.0;
        for (double p : repartoPorTipoAveria) {
            if (p < 0.0 || !Double.isFinite(p)) {
                throw new IllegalArgumentException("Peso de reparto de averias invalido: " + p);
            }
            total += p;
        }
        if (total <= 0.0) {
            throw new IllegalArgumentException("El reparto entre tipos de averia no puede ser todo ceros");
        }
        this.semilla = semilla;
        this.probabilidadPorUnidadPorTurno = probabilidadPorUnidadPorTurno;
        this.factorPorTipoUnidad = factorPorTipoUnidad.clone();
        this.repartoPorTipoAveria = repartoPorTipoAveria.clone();
        this.activo = activo;
    }

    /**
     * Generador derivado de la configuracion de la corrida. Toma de ella la semilla, la
     * probabilidad por unidad y por turno y el interruptor {@code generarAverias}.
     */
    public static GeneradorAverias deConfiguracion(ConfiguracionEscenario configuracion) {
        if (configuracion == null) {
            throw new IllegalArgumentException("El generador de averias necesita una configuracion");
        }
        return new GeneradorAverias(configuracion.semilla(), configuracion.averiasPorUnidadPorTurno(),
                FACTORES_POR_DEFECTO, REPARTO_POR_DEFECTO, configuracion.generarAverias());
    }

    // ------------------------------------------------------------- parametros

    /** Semilla de la corrida, que es lo que hace reproducible la secuencia de averias. */
    public long semilla() {
        return semilla;
    }

    /** Probabilidad de referencia por unidad y por turno, es decir la de una moto. */
    public double probabilidadPorUnidadPorTurno() {
        return probabilidadPorUnidadPorTurno;
    }

    /** Indica si las reglas de generacion estan habilitadas en esta corrida. */
    public boolean activo() {
        return activo;
    }

    /** Factor que modula la probabilidad del tipo de unidad indicado. */
    public double factor(TipoUnidad tipo) {
        return factorPorTipoUnidad[tipo.ordinal()];
    }

    /** Probabilidad efectiva de que una unidad del tipo dado se averie durante un turno. */
    public double probabilidad(TipoUnidad tipo) {
        return Math.min(1.0, probabilidadPorUnidadPorTurno * factorPorTipoUnidad[tipo.ordinal()]);
    }

    /** Pesos del reparto entre los tipos de averia 1, 2 y 3, en una copia independiente. */
    public double[] reparto() {
        return repartoPorTipoAveria.clone();
    }

    // -------------------------------------------------------------- generacion

    /**
     * Averias que ocurriran durante un turno, con su instante concreto dentro de el.
     *
     * <p>Es el punto de entrada que consume el motor de simulacion al atender el evento
     * {@link TipoEvento#CAMBIO_TURNO}: recibe el instante de arranque del turno y las
     * unidades de la flota, y devuelve los incidentes ya fechados para encolarlos como
     * eventos {@link TipoEvento#AVERIA}. Quedan fuera del sorteo las unidades que ya estan
     * fuera de servicio (averiadas o en mantenimiento), de modo que el llamante puede pasar
     * la flota completa sin filtrarla.</p>
     *
     * <p>El instante recibido se normaliza con {@link Turno#inicioDelTurno(long)}, de modo
     * que invocar a mitad de turno devuelve exactamente lo mismo que invocar en su
     * arranque. Las averias salen ordenadas por instante y, a igualdad de instante, por
     * codigo de unidad, con lo que el orden de encolado no depende del orden de la lista
     * recibida.</p>
     *
     * @param minutoDelTurno instante de arranque del turno, en minutos desde el inicio del
     *                       escenario
     * @param unidades       unidades candidatas; las que estan fuera de servicio se descartan
     * @return averias del turno, o una lista vacia si las reglas estan deshabilitadas
     */
    public List<Averia> averiasDelTurno(long minutoDelTurno, List<UnidadTransporte> unidades) {
        if (!activo || unidades == null || unidades.isEmpty()) {
            return List.of();
        }
        long inicioTurno = Turno.inicioDelTurno(minutoDelTurno);
        List<Averia> averias = new ArrayList<>();
        for (UnidadTransporte unidad : unidades) {
            Averia averia = averiaDe(unidad, inicioTurno);
            if (averia != null) {
                averias.add(averia);
            }
        }
        averias.sort(Comparator.comparingLong(Averia::minutoAveria).thenComparing(Averia::codigoUnidad));
        return averias;
    }

    /**
     * Averia que le corresponde a una unidad concreta durante el turno indicado, o
     * {@code null} si no se averia.
     *
     * <p>Es determinista: depende solo de la semilla, del turno y del codigo de la unidad,
     * nunca del orden en que se consulten las unidades. Devuelve {@code null} de inmediato
     * si la unidad no puede operar en el turno, es decir si ya esta averiada o en
     * mantenimiento, conforme a la regla 1.</p>
     *
     * @param unidad         unidad candidata, con su estado y su nodo actuales
     * @param minutoDelTurno cualquier instante del turno; se normaliza a su arranque
     */
    public Averia averiaDe(UnidadTransporte unidad, long minutoDelTurno) {
        if (!activo || unidad == null || !puedeOperar(unidad)) {
            return null;
        }
        long inicioTurno = Turno.inicioDelTurno(minutoDelTurno);
        Aleatorio aleatorio = new Aleatorio(semillaDeSorteo(inicioTurno, unidad.codigo()));
        if (!aleatorio.conProbabilidad(probabilidad(unidad.tipo()))) {
            return null;
        }
        TipoAveria tipo = TIPOS_AVERIA[aleatorio.ruleta(repartoPorTipoAveria)];
        long minutoAveria = inicioTurno + aleatorio.siguienteEntero(Turno.DURACION_MIN);
        return new Averia(unidad.codigo(), tipo, minutoAveria, unidad.nodo());
    }

    /**
     * Indica si la unidad puede operar durante el turno que arranca. Solo quedan fuera del
     * sorteo las que ya estan fuera de servicio: una unidad averiada sigue inmovilizada y
     * una en mantenimiento preventivo no sale del almacen central ese dia.
     */
    private static boolean puedeOperar(UnidadTransporte unidad) {
        EstadoUnidad estado = unidad.estado();
        return estado != EstadoUnidad.AVERIADA && estado != EstadoUnidad.EN_MANTENIMIENTO;
    }

    // ---------------------------------------------------------------- internos

    /**
     * Semilla de la corriente aleatoria de una unidad en un turno. Mezcla los tres
     * ingredientes con las constantes de dispersion de SplitMix64, de modo que turnos
     * contiguos y unidades de codigo contiguo den corrientes sin parecido entre si.
     */
    private long semillaDeSorteo(long inicioTurno, String codigo) {
        long mezcla = semilla * DISPERSION_A;
        mezcla ^= (inicioTurno + DISPERSION_A) * DISPERSION_B;
        mezcla ^= resumenDeCodigo(codigo) * DISPERSION_C;
        return mezcla;
    }

    /**
     * Resumen de 64 bits del codigo TTNN con el algoritmo FNV-1a. Se implementa aqui, y no
     * se usa {@code String.hashCode}, para que la reproducibilidad no dependa de nada
     * externo al nucleo.
     */
    private static long resumenDeCodigo(String codigo) {
        long resumen = FNV_BASE;
        for (int i = 0; i < codigo.length(); i++) {
            resumen = (resumen ^ codigo.charAt(i)) * FNV_PRIMO;
        }
        return resumen;
    }
}
