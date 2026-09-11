package org.kindbox.core.evaluacion;

import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Cota inferior del desfase de una ruta calculada con la concatenacion de
 * {@link DatosSecuencia} de Vidal, Crainic, Gendreau y Prins (2013). Es la pieza que hace
 * cierta la evaluacion en tiempo constante del apartado 10 del ISA: los dos algoritmos la
 * consultan <b>antes</b> de llamar a {@link ProgramadorRuta} y se ahorran la decodificacion
 * de los movimientos que la cota ya condena, sin cambiar ninguna decision.
 *
 * <h2>Modelo</h2>
 * <p>La ruta se modela como [arranque] + visitas + [fin virtual]. El arranque es un nodo sin
 * servicio en el punto de la unidad, con instante mas temprano igual a su minuto de
 * disponibilidad, que es donde arranca el decodificador. Cada visita dura el acondicionamiento
 * y tiene como instante limite el del pedido; no impone instante mas temprano porque el
 * decodificador nunca espera a que un pedido se registre. El fin virtual no tiene servicio ni
 * tramo de llegada y su instante limite es el cierre del turno, el mismo con que el
 * decodificador suma al desfase el exceso de la ruta. Cada tramo dura
 * {@code minutosDeViaje(tipo, km)} con los kilometros de la matriz entre sus dos extremos,
 * igual que en el decodificador.</p>
 *
 * <h2>Por que es cota inferior</h2>
 * <ol>
 * <li><b>Llegadas.</b> El decodificador recorre los mismos tramos, salvo que puede intercalar
 * un abastecimiento. La matriz son caminos minimos y cumple la desigualdad triangular, de modo
 * que el rodeo por un almacen suma al menos los kilometros del tramo directo, y el redondeo
 * hacia arriba de los minutos cumple {@code ceil(a) + ceil(b) >= ceil(a + b)}. Por induccion,
 * la llegada del modelo a cada parada no es posterior a la del decodificador. La igualdad de
 * redondeo exige que {@code km * 60 / velocidad} no quede a menos de una milmillonesima por
 * encima de un entero, que el margen de {@code minutosDeViaje} trataria como entero; con las
 * velocidades del enunciado la parte fraccionaria es multiplo de un quinto y no ocurre.</li>
 * <li><b>Pausa.</b> La pausa de alimentacion nunca crea desfase: el decodificador solo la
 * ubica donde su desplazamiento cabe en la holgura de todas las paradas siguientes y en el
 * turno, y si no cabe en ninguna posicion la omite. Solo puede retrasar lo que ya tenia
 * holgura, de modo que ignorarla no rebaja la cota.</li>
 * <li><b>Desfase.</b> El time warp de Vidal devuelve el reloj al instante limite de la parada
 * incumplida, de modo que cada minuto de retraso se cuenta una sola vez; el decodificador
 * arrastra el retraso a las paradas siguientes y lo vuelve a contar en cada una. Sin esperas
 * intermedias el time warp de la ruta es el mayor retraso de sus paradas, que no supera la
 * suma de retrasos que mide el decodificador.</li>
 * </ol>
 * <p>La cota solo sirve para <b>descartar</b>: un desfase de cero no prueba nada, porque el
 * decodificador comprueba ademas la capacidad por visita, el inventario de los almacenes y la
 * alcanzabilidad de los abastecimientos. Un tramo directo sin camino si prueba que el
 * decodificador fallaria, porque un camino por un almacen seria tambien un camino directo, y
 * se informa como {@link #INALCANZABLE}.</p>
 *
 * <h2>Dos formas de uso</h2>
 * <p>Para enumerar las posiciones de insercion de una visita en una ruta fija, que es el
 * bucle interno de los operadores de reconstruccion de ALNS (apartado 7.3.2),
 * {@link #preparar} precalcula en tiempo lineal los resumenes de todos los prefijos y sufijos
 * de la ruta y {@link #desfaseInsertando} valora cada posicion con dos concatenaciones, en
 * tiempo constante. Para un movimiento de la busqueda local de HGS (apartado 6.3.3), cuya
 * secuencia resultante ya esta escrita en un arreglo porque el decodificador la necesita,
 * {@link #iniciar}, {@link #anadir} y {@link #cerrar} la resumen parada a parada en la misma
 * pasada que la cota de kilometros que ya existia.</p>
 *
 * <p>Los arreglos de trabajo son campos de instancia y se reutilizan entre llamadas, de modo
 * que no hay asignaciones por movimiento y la clase no es segura para uso concurrente: cada
 * hilo de busqueda usa su propia instancia.</p>
 */
public final class ResumenesRuta {

    /** Resultado de una secuencia con algun tramo directo sin camino. */
    public static final long INALCANZABLE = Long.MAX_VALUE;

    private static final int CAMPOS = DatosSecuencia.CAMPOS;
    /** Rutas de hasta esta longitud caben en los arreglos iniciales. */
    private static final int CAPACIDAD_INICIAL = 16;

    private final InstanciaPlanificacion instancia;
    private final MatrizDistancias matriz;
    private final ParametrosOperacion.Instantanea parametros;
    private final int minutosServicio;

    /** Resumen del prefijo {@code k}: arranque mas las {@code k} primeras visitas. */
    private long[] prefijo = new long[CAMPOS * (CAPACIDAD_INICIAL + 1)];
    /** Resumen del sufijo {@code k}: visitas desde la {@code k} mas el fin virtual. */
    private long[] sufijo = new long[CAMPOS * (CAPACIDAD_INICIAL + 1)];
    /** Punto de la matriz de cada visita de la ruta preparada. */
    private int[] puntos = new int[CAPACIDAD_INICIAL];
    /** Resumenes intermedios de una valoracion: visita, prefijo con visita y resultado. */
    private final long[] trabajo = new long[CAMPOS * 3];

    // Ruta preparada.
    private TipoUnidad tipo;
    private int puntoArranque;
    private int longitud;
    /** Mayor {@code k} cuyo prefijo no tiene tramos sin camino. */
    private int ultimoPrefijoValido;
    /** Menor {@code k} cuyo sufijo no tiene tramos sin camino. */
    private int primerSufijoValido;

    // Acumulador de una secuencia recorrida parada a parada.
    private final long[] acumulado = new long[CAMPOS];
    private int unidadAcumulada;
    private TipoUnidad tipoAcumulado;
    private int puntoAcumulado;
    private long kilometrosAcumulados;
    private boolean acumuladoAlcanzable;

    /**
     * @param instancia fotografia del problema; la matriz y los parametros de operacion son
     *                  los mismos que usa el decodificador sobre ella
     */
    public ResumenesRuta(InstanciaPlanificacion instancia) {
        this.instancia = instancia;
        this.matriz = instancia.matriz();
        this.parametros = instancia.parametros();
        this.minutosServicio = parametros.minutosAcondicionamiento();
    }

    // ------------------------------------------------- prefijos y sufijos

    /**
     * Precalcula los resumenes de todos los prefijos y sufijos de la ruta, en tiempo lineal.
     * Quedan vigentes hasta la siguiente llamada.
     *
     * @param unidad   indice local de la unidad que atiende la ruta
     * @param pedidos  indices locales de pedido, en orden de visita
     * @param longitud numero de visitas validas al inicio del arreglo
     */
    public void preparar(int unidad, int[] pedidos, int longitud) {
        asegurarCapacidad(longitud);
        this.longitud = longitud;
        this.tipo = instancia.unidadTipo(unidad);
        this.puntoArranque = instancia.puntoUnidad(unidad);
        for (int k = 0; k < longitud; k++) {
            puntos[k] = instancia.puntoPedido(pedidos[k]);
        }

        fijarArranque(prefijo, 0, unidad);
        ultimoPrefijoValido = longitud;
        int punto = puntoArranque;
        for (int k = 0; k < longitud; k++) {
            int km = matriz.km(punto, puntos[k]);
            if (km >= MatrizDistancias.INALCANZABLE) {
                ultimoPrefijoValido = k;
                break;
            }
            fijarVisita(trabajo, 0, pedidos[k]);
            DatosSecuencia.concatenar(prefijo, k * CAMPOS, trabajo, 0, minutos(tipo, km),
                    prefijo, (k + 1) * CAMPOS);
            punto = puntos[k];
        }

        fijarFin(sufijo, longitud * CAMPOS, unidad);
        primerSufijoValido = 0;
        for (int k = longitud - 1; k >= 0; k--) {
            long viaje = 0L;
            if (k + 1 < longitud) {
                int km = matriz.km(puntos[k], puntos[k + 1]);
                if (km >= MatrizDistancias.INALCANZABLE) {
                    primerSufijoValido = k + 1;
                    break;
                }
                viaje = minutos(tipo, km);
            }
            fijarVisita(trabajo, 0, pedidos[k]);
            DatosSecuencia.concatenar(trabajo, 0, sufijo, (k + 1) * CAMPOS, viaje, sufijo, k * CAMPOS);
        }
    }

    /**
     * Cota inferior del desfase de la ruta preparada con una visita mas al pedido dado,
     * insertada delante de la visita que ocupa la posicion indicada. Se calcula como
     * {@code concatenar(prefijo, visita, sufijo)}, en tiempo constante.
     *
     * @param posicion posicion de insercion, de cero a la longitud de la ruta
     * @param pedido   indice local del pedido que se inserta
     * @return la cota en minutos, o {@link #INALCANZABLE} si algun tramo no tiene camino
     */
    public long desfaseInsertando(int posicion, int pedido) {
        if (posicion > ultimoPrefijoValido || posicion < primerSufijoValido) {
            return INALCANZABLE;
        }
        final int punto = instancia.puntoPedido(pedido);
        final int anterior = posicion == 0 ? puntoArranque : puntos[posicion - 1];
        final int kmLlegada = matriz.km(anterior, punto);
        if (kmLlegada >= MatrizDistancias.INALCANZABLE) {
            return INALCANZABLE;
        }
        long viajeSalida = 0L;
        if (posicion < longitud) {
            int kmSalida = matriz.km(punto, puntos[posicion]);
            if (kmSalida >= MatrizDistancias.INALCANZABLE) {
                return INALCANZABLE;
            }
            viajeSalida = minutos(tipo, kmSalida);
        }
        fijarVisita(trabajo, 0, pedido);
        DatosSecuencia.concatenar(prefijo, posicion * CAMPOS, trabajo, 0, minutos(tipo, kmLlegada),
                trabajo, CAMPOS);
        DatosSecuencia.concatenar(trabajo, CAMPOS, sufijo, posicion * CAMPOS, viajeSalida,
                trabajo, 2 * CAMPOS);
        return trabajo[2 * CAMPOS + DatosSecuencia.DESFASE];
    }

    // ---------------------------------------------------------- acumulador

    /** Empieza a resumir una secuencia atendida por la unidad dada, desde su arranque. */
    public void iniciar(int unidad) {
        unidadAcumulada = unidad;
        tipoAcumulado = instancia.unidadTipo(unidad);
        puntoAcumulado = instancia.puntoUnidad(unidad);
        kilometrosAcumulados = 0L;
        acumuladoAlcanzable = true;
        fijarArranque(acumulado, 0, unidad);
    }

    /**
     * Anade al resumen en curso una visita al pedido dado.
     *
     * @return {@code false} si el tramo hasta la visita no tiene camino; la secuencia ya no
     *         admite programacion y el resto de visitas puede omitirse
     */
    public boolean anadir(int pedido) {
        final int punto = instancia.puntoPedido(pedido);
        final int km = matriz.km(puntoAcumulado, punto);
        if (km >= MatrizDistancias.INALCANZABLE) {
            acumuladoAlcanzable = false;
            return false;
        }
        kilometrosAcumulados += km;
        fijarVisita(trabajo, 0, pedido);
        DatosSecuencia.concatenar(acumulado, 0, trabajo, 0, minutos(tipoAcumulado, km), acumulado, 0);
        puntoAcumulado = punto;
        return true;
    }

    /**
     * Cierra el resumen en curso con el fin virtual del turno y devuelve la cota inferior del
     * desfase de la secuencia, o {@link #INALCANZABLE} si algun tramo no tenia camino.
     */
    public long cerrar() {
        if (!acumuladoAlcanzable) {
            return INALCANZABLE;
        }
        fijarFin(trabajo, 0, unidadAcumulada);
        DatosSecuencia.concatenar(acumulado, 0, trabajo, 0, 0L, acumulado, 0);
        return acumulado[DatosSecuencia.DESFASE];
    }

    /**
     * Kilometros de visita a visita de la secuencia en curso, sin abastecimientos. Es la misma
     * cota de kilometros que {@link ProgramadorRuta#cotaInferiorKilometros}.
     */
    public long kilometrosAcumulados() {
        return kilometrosAcumulados;
    }

    // ------------------------------------------------------------- internos

    private int minutos(TipoUnidad tipoUnidad, int km) {
        return parametros.minutosDeViaje(tipoUnidad, km);
    }

    private void fijarArranque(long[] destino, int id, int unidad) {
        DatosSecuencia.fijar(destino, id, 0L, instancia.unidadMinutoDisponible(unidad),
                DatosSecuencia.SIN_LIMITE_LARGO);
    }

    private void fijarVisita(long[] destino, int id, int pedido) {
        DatosSecuencia.fijar(destino, id, minutosServicio, DatosSecuencia.SIN_ESPERA_LARGO,
                instancia.pedidoMinutoLimite(pedido));
    }

    private void fijarFin(long[] destino, int id, int unidad) {
        DatosSecuencia.fijar(destino, id, 0L, DatosSecuencia.SIN_ESPERA_LARGO,
                instancia.unidadMinutoFinTurno(unidad));
    }

    private void asegurarCapacidad(int longitudRuta) {
        if (puntos.length >= longitudRuta) {
            return;
        }
        int nueva = Math.max(longitudRuta, puntos.length * 2);
        puntos = new int[nueva];
        prefijo = new long[CAMPOS * (nueva + 1)];
        sufijo = new long[CAMPOS * (nueva + 1)];
    }
}
