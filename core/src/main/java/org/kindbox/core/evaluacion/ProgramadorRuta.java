package org.kindbox.core.evaluacion;

import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;

/**
 * Decodificador de secuencias de visita. Dada una unidad y la lista ordenada de pedidos que
 * debe atender, construye la ruta concreta: intercala las paradas de abastecimiento que
 * hagan falta, ubica la pausa de alimentacion, calcula instantes de llegada y de salida y
 * decide si la ruta cumple las restricciones duras del apartado 2.6 del ISA.
 *
 * <p>Es el componente compartido mas caliente del sistema. El procedimiento Split de la
 * busqueda genetica hibrida lo invoca para cortar cada cromosoma en rutas (apartado 6.3.2)
 * y los operadores de insercion de la busqueda adaptativa de vecindad amplia lo invocan
 * para valorar cada posicion candidata (apartado 7.3.2). Que los dos algoritmos decodifiquen
 * con este mismo codigo es lo que permite comparar mecanismos de busqueda y no modelos del
 * problema, y es el sustento de la verificacion de validez del apartado 12.4.</p>
 *
 * <h2>Abastecimiento</h2>
 * <p>Cuando la carga a bordo no alcanza para la siguiente entrega se inserta una parada de
 * abastecimiento en el almacen que minimiza el desvio, es decir la suma de la distancia
 * desde la posicion actual hasta el almacen y desde el almacen hasta el destino, entre los
 * almacenes cuyo inventario disponible cubre el faltante. El almacen central tiene
 * inventario ilimitado. Se carga hasta completar la capacidad de la unidad, acotado por el
 * inventario disponible y por lo que resta por entregar en la ruta: cargar de mas gastaria
 * inventario de un almacen intermedio sin necesidad, y ese inventario es un recurso
 * compartido por todas las rutas del plan. El tiempo de carga es despreciable, de modo que
 * la parada no consume minutos, pero si los kilometros del desvio.</p>
 *
 * <h2>Pausa de alimentacion</h2>
 * <p>El apartado 6.3.2 del ISA advierte que la formula cerrada de Vidal para ubicar la pausa
 * de almuerzo es incorrecta, segun la nota de Garaix y Skiredj (2024). Aqui no se usa formula
 * cerrada alguna: se evalua de forma explicita cada posicion candidata de insercion y se
 * conserva la de menor costo entre las que resultan factibles. Como una entrega consume una
 * hora de acondicionamiento, una ruta dentro de un turno de ocho horas no pasa de unas siete
 * paradas, de modo que la enumeracion es barata y, sobre todo, exacta: elimina de raiz el
 * riesgo RA-03 del ISA. Todas las posiciones recorren los mismos kilometros, de modo que a
 * igualdad de plazos incumplidos el criterio de menor costo se reduce a terminar lo antes
 * posible, y a igualdad de instante de fin se prefiere la pausa mas temprana.</p>
 *
 * <p>La pausa es exigible <b>por jornada</b> y no por ruta. Una ruta es solo el tramo de la
 * jornada que alcanza a planificarse en esta iteracion, de modo que cuando el turno ya
 * arranco hace mas de lo que permite la ventana, o cuando ninguna posicion admite la pausa
 * sin incumplir un plazo o el cierre del turno, la ruta se programa sin pausa y <b>no</b> se
 * marca infactible por ese motivo. {@code VerificadorRestricciones} sostiene la misma
 * interpretacion: comprueba que la pausa presente este bien ubicada, no que exista.</p>
 *
 * <h2>Estado y concurrencia</h2>
 * <p>El inventario de los almacenes intermedios es un recurso compartido entre todas las
 * rutas de un mismo plan, de modo que el programador lo mantiene como estado propio.
 * {@link #reiniciarInventarios()} lo devuelve al valor de la instancia antes de construir un
 * plan nuevo, y programar con {@code consumirInventario} en {@code false} permite valorar
 * movimientos candidatos sin ensuciarlo.</p>
 *
 * <p>Los arreglos de trabajo son campos de instancia y se reutilizan entre llamadas, de modo
 * que la clase <b>no</b> es segura para uso concurrente: cada algoritmo, y cada hilo dentro
 * de el, debe usar su propia instancia. Los resultados de la ultima programacion viven en
 * campos escalares hasta la siguiente llamada.</p>
 */
public final class ProgramadorRuta {

    /** Marca de parada de abastecimiento en los arreglos de trabajo. */
    private static final byte ABASTECIMIENTO = 0;
    /** Marca de parada de entrega en los arreglos de trabajo. */
    private static final byte ENTREGA = 1;
    /** Instante limite de una parada que no impone plazo, como el abastecimiento. */
    private static final long SIN_PLAZO = Long.MAX_VALUE / 4;
    /** Capacidad inicial de los arreglos de trabajo. */
    private static final int CAPACIDAD_INICIAL = 32;

    private final InstanciaPlanificacion instancia;
    private final int cantidadAlmacenes;

    /** Inventario disponible por almacen, compartido por todas las rutas del plan en curso. */
    private final int[] inventario;
    /** Copia sobre la que trabaja la programacion en curso; solo se confirma si se pide. */
    private final int[] inventarioTrabajo;

    // Arreglos de trabajo de la ruta en construccion. Se reutilizan entre llamadas para no
    // asignar memoria por movimiento evaluado.
    private byte[] claseParada = new byte[CAPACIDAD_INICIAL];
    private int[] nodoParada = new int[CAPACIDAD_INICIAL];
    private int[] identificadorParada = new int[CAPACIDAD_INICIAL];
    private int[] cantidadParada = new int[CAPACIDAD_INICIAL];
    private int[] kmParada = new int[CAPACIDAD_INICIAL];
    private long[] llegadaParada = new long[CAPACIDAD_INICIAL];
    private long[] salidaParada = new long[CAPACIDAD_INICIAL];
    private long[] limiteParada = new long[CAPACIDAD_INICIAL];
    private long[] holguraSufijo = new long[CAPACIDAD_INICIAL + 1];
    private int[] demandaSufijo = new int[CAPACIDAD_INICIAL];

    // Resultado de la ultima programacion.
    private boolean ultimaFactible;
    private int ultimoDesfase;
    private int ultimosKilometros;
    private double ultimoCosto;
    private int ultimasUnidadesEntregadas;
    private int ultimasEntregas;
    private long ultimoMinutoFin;
    private int cantidadParadas;
    private int posicionPausa;
    private long inicioPausa;
    private long desplazamientoPausa;

    /** Kilometros hasta el almacen que eligio la ultima llamada a {@link #mejorAlmacen}. */
    private int kmHastaAlmacen;

    /**
     * Crea un programador para la fotografia dada, con el inventario de los almacenes en su
     * valor inicial.
     *
     * @param instancia fotografia del problema sobre la que se decodifica; el programador no
     *                  la modifica
     */
    public ProgramadorRuta(InstanciaPlanificacion instancia) {
        this.instancia = instancia;
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();
        this.inventario = new int[cantidadAlmacenes];
        this.inventarioTrabajo = new int[cantidadAlmacenes];
        reiniciarInventarios();
    }

    /** Instancia sobre la que trabaja el programador. */
    public InstanciaPlanificacion instancia() {
        return instancia;
    }

    // ------------------------------------------------------------ inventarios

    /**
     * Devuelve el inventario de todos los almacenes al valor de la instancia. Debe llamarse
     * antes de construir cada plan completo, porque el consumo de un plan anterior no puede
     * arrastrarse al siguiente.
     */
    public void reiniciarInventarios() {
        for (int a = 0; a < cantidadAlmacenes; a++) {
            inventario[a] = instancia.almacenInventario(a);
        }
    }

    /** Inventario disponible del almacen, ya descontado el consumo del plan en curso. */
    public int inventarioDisponible(int indiceAlmacen) {
        return inventario[indiceAlmacen];
    }

    // ------------------------------------------------------------ programacion

    /**
     * Programa la secuencia de visitas y devuelve la ruta concreta con todas sus paradas y
     * sus instantes, en el orden real de recorrido, de modo que el visualizador pueda
     * dibujarla.
     *
     * @param indiceUnidad       indice local de la unidad en la instancia
     * @param pedidos            indices locales de pedido, en orden de visita
     * @param cantidades         unidades a entregar en cada visita; permite entregas parciales
     * @param longitud           numero de visitas validas al inicio de los arreglos
     * @param consumirInventario si {@code true}, descuenta del inventario compartido lo
     *                           cargado en los almacenes intermedios; los operadores que
     *                           solo tantean movimientos deben pasar {@code false}
     * @return la programacion, o {@link Programacion#INFACTIBLE} si la secuencia no admite
     *         ninguna ruta con esta unidad
     */
    public Programacion programar(int indiceUnidad, int[] pedidos, int[] cantidades, int longitud,
                                  boolean consumirInventario) {
        if (longitud <= 0) {
            return programacionVacia(indiceUnidad);
        }
        if (!ejecutar(indiceUnidad, pedidos, cantidades, longitud)) {
            return Programacion.INFACTIBLE;
        }
        if (consumirInventario) {
            System.arraycopy(inventarioTrabajo, 0, inventario, 0, cantidadAlmacenes);
        }
        Ruta ruta = materializar(indiceUnidad);
        return new Programacion(ultimaFactible, ultimoDesfase, ultimosKilometros, ultimoCosto,
                ultimasEntregas, ultimasUnidadesEntregadas, ultimoMinutoFin, ruta);
    }

    /**
     * Programa la secuencia sin materializar la ruta ni tocar el inventario compartido, para
     * el bucle interno de los operadores. Es el mismo calculo que {@link #programar}, de modo
     * que los valores coinciden exactamente; lo unico que se ahorra es la creacion de la
     * {@link Ruta} y sus {@link Parada}, que son los unicos objetos del decodificador. Los
     * resultados quedan en {@link #ultimaFactible()}, {@link #ultimoDesfase()},
     * {@link #ultimosKilometros()}, {@link #ultimoCosto()}, {@link #ultimoMinutoFin()} y
     * {@link #ultimasUnidadesEntregadas()} hasta la siguiente llamada.
     *
     * @return {@code false} si la secuencia no admite ninguna ruta con esta unidad
     */
    public boolean evaluar(int indiceUnidad, int[] pedidos, int[] cantidades, int longitud) {
        if (longitud <= 0) {
            prepararResultadoVacio(indiceUnidad);
            return true;
        }
        return ejecutar(indiceUnidad, pedidos, cantidades, longitud);
    }

    /**
     * Cota inferior de los kilometros de la secuencia: suma las distancias de visita a visita
     * sin resolver los abastecimientos ni la pausa. Sirve para podar candidatos antes de
     * programarlos, que es entre uno y dos ordenes de magnitud mas barato.
     *
     * <p>Es una cota valida porque las distancias de la matriz son caminos minimos sobre la
     * reticula y por tanto cumplen la desigualdad triangular: intercalar una parada de
     * abastecimiento nunca acorta el recorrido. Devuelve {@link MatrizDistancias#INALCANZABLE}
     * si algun tramo no tiene camino con los bloqueos vigentes.</p>
     */
    public int cotaInferiorKilometros(int indiceUnidad, int[] pedidos, int longitud) {
        if (longitud <= 0) {
            return 0;
        }
        MatrizDistancias matriz = instancia.matriz();
        int punto = instancia.puntoUnidad(indiceUnidad);
        int km = 0;
        for (int i = 0; i < longitud; i++) {
            int siguiente = instancia.puntoPedido(pedidos[i]);
            int tramo = matriz.km(punto, siguiente);
            if (tramo >= MatrizDistancias.INALCANZABLE) {
                return MatrizDistancias.INALCANZABLE;
            }
            km += tramo;
            punto = siguiente;
        }
        return km;
    }

    // -------------------------------------------- resultado de la ultima corrida

    /** Indica si la ultima programacion cumple plazos y cierre de turno. */
    public boolean ultimaFactible() {
        return ultimaFactible;
    }

    /** Minutos totales de incumplimiento de instantes limite de la ultima programacion. */
    public int ultimoDesfase() {
        return ultimoDesfase;
    }

    /** Kilometros de la ultima programacion, desvios de abastecimiento incluidos. */
    public int ultimosKilometros() {
        return ultimosKilometros;
    }

    /** Costo en soles de la ultima programacion. */
    public double ultimoCosto() {
        return ultimoCosto;
    }

    /** Instante en que termina la ultima programacion. */
    public long ultimoMinutoFin() {
        return ultimoMinutoFin;
    }

    /** Unidades del producto P entregadas por la ultima programacion. */
    public int ultimasUnidadesEntregadas() {
        return ultimasUnidadesEntregadas;
    }

    /** Numero de visitas de entrega de la ultima programacion. */
    public int ultimasEntregas() {
        return ultimasEntregas;
    }

    /** Indica si la ultima programacion incluye la pausa de alimentacion. */
    public boolean ultimaIncluyePausa() {
        return posicionPausa >= 0;
    }

    // ----------------------------------------------------------------- internos

    /**
     * Nucleo del decodificador. Recorre la secuencia, resuelve abastecimientos, ubica la
     * pausa y deja el resultado en los campos escalares y en los arreglos de trabajo.
     *
     * @return {@code false} si la secuencia no admite programacion alguna
     */
    private boolean ejecutar(int indiceUnidad, int[] pedidos, int[] cantidades, int longitud) {
        asegurarCapacidad(longitud);
        System.arraycopy(inventario, 0, inventarioTrabajo, 0, cantidadAlmacenes);

        final MatrizDistancias matriz = instancia.matriz();
        final ParametrosOperacion.Instantanea parametros = instancia.parametros();
        final TipoUnidad tipo = instancia.unidadTipo(indiceUnidad);
        final int capacidad = instancia.unidadCapacidad(indiceUnidad);
        final int minutosServicio = parametros.minutosAcondicionamiento();
        final long minutoInicio = instancia.unidadMinutoDisponible(indiceUnidad);

        // Lo que resta por entregar desde cada posicion. Acota la carga de cada
        // abastecimiento para no gastar inventario intermedio sin necesidad.
        int acumulado = 0;
        for (int i = longitud - 1; i >= 0; i--) {
            acumulado += cantidades[i];
            demandaSufijo[i] = acumulado;
        }

        int carga = instancia.unidadCarga(indiceUnidad);
        int punto = instancia.puntoUnidad(indiceUnidad);
        long instante = minutoInicio;
        long desfase = 0;
        int kilometros = 0;
        int paradas = 0;
        int entregadas = 0;

        for (int i = 0; i < longitud; i++) {
            final int pedido = pedidos[i];
            final int cantidad = cantidades[i];
            // Una visita que supera la capacidad de la unidad no puede atenderse de una vez:
            // el llamante debe partirla en entregas parciales antes de programar.
            if (cantidad <= 0 || cantidad > capacidad) {
                return false;
            }
            final int puntoDestino = instancia.puntoPedido(pedido);

            if (carga < cantidad) {
                int almacen = mejorAlmacen(punto, puntoDestino, cantidad - carga);
                if (almacen < 0) {
                    return false;
                }
                int recarga = Math.min(capacidad - carga,
                        Math.min(inventarioTrabajo[almacen], demandaSufijo[i] - carga));
                instante += parametros.minutosDeViaje(tipo, kmHastaAlmacen);
                kilometros += kmHastaAlmacen;

                claseParada[paradas] = ABASTECIMIENTO;
                nodoParada[paradas] = instancia.almacenNodo(almacen);
                identificadorParada[paradas] = instancia.almacenId(almacen);
                cantidadParada[paradas] = recarga;
                kmParada[paradas] = kmHastaAlmacen;
                llegadaParada[paradas] = instante;
                salidaParada[paradas] = instante;
                limiteParada[paradas] = SIN_PLAZO;
                paradas++;

                carga += recarga;
                // El almacen central tiene inventario ilimitado y no se descuenta.
                if (!instancia.almacenEsCentral(almacen)) {
                    inventarioTrabajo[almacen] -= recarga;
                }
                punto = instancia.puntoAlmacen(almacen);
            }

            int kmTramo = matriz.km(punto, puntoDestino);
            if (kmTramo >= MatrizDistancias.INALCANZABLE) {
                return false;
            }
            instante += parametros.minutosDeViaje(tipo, kmTramo);
            kilometros += kmTramo;

            long limite = instancia.pedidoMinutoLimite(pedido);
            if (instante > limite) {
                desfase += instante - limite;
            }

            claseParada[paradas] = ENTREGA;
            nodoParada[paradas] = instancia.pedidoNodo(pedido);
            identificadorParada[paradas] = instancia.pedidoId(pedido);
            cantidadParada[paradas] = cantidad;
            kmParada[paradas] = kmTramo;
            llegadaParada[paradas] = instante;
            // El acondicionamiento de una hora se cobra por visita, incluso si es parcial,
            // pero queda fuera del plazo: la restriccion es sobre el instante de llegada.
            instante += minutosServicio;
            salidaParada[paradas] = instante;
            limiteParada[paradas] = limite;
            paradas++;

            carga -= cantidad;
            entregadas += cantidad;
            punto = puntoDestino;
        }

        final long finTurno = instancia.unidadMinutoFinTurno(indiceUnidad);
        if (instancia.unidadPausaCumplida(indiceUnidad)) {
            // La unidad ya almorzo en este turno: no se le exige otra pausa.
            posicionPausa = -1;
            inicioPausa = 0;
            desplazamientoPausa = 0;
        } else {
            ubicarPausa(paradas, minutoInicio, instante, finTurno, parametros);
        }

        long minutoFin = instante + desplazamientoPausa;
        // El exceso sobre el cierre del turno es una violacion temporal mas, del mismo tipo
        // que el incumplimiento de un plazo, y se acumula en el mismo desfase. Sin esto la
        // subpoblacion infactible de HGS (apartado 6.3.1 del ISA) no tendria por que
        // descender: una ruta que se pasa del turno saldria infactible con desfase cero y
        // la penalizacion no distinguiria pasarse un minuto de pasarse tres horas.
        desfase += Math.max(0L, minutoFin - finTurno);

        cantidadParadas = paradas;
        ultimasEntregas = longitud;
        ultimasUnidadesEntregadas = entregadas;
        ultimosKilometros = kilometros;
        ultimoCosto = parametros.costo(tipo, kilometros);
        ultimoDesfase = (int) Math.min(desfase, Programacion.DESFASE_INFINITO);
        ultimoMinutoFin = minutoFin;
        ultimaFactible = desfase == 0;
        return true;
    }

    /**
     * Evalua de forma explicita cada posicion de insercion de la pausa de alimentacion y
     * conserva la mejor, o deja la ruta sin pausa si ninguna resulta admisible.
     *
     * <p>Insertar la pausa tras la parada {@code k} retrasa todas las paradas siguientes en
     * el mismo numero de minutos, de modo que la posicion es admisible cuando ese retraso
     * cabe en la holgura de todas ellas y la ruta sigue cerrando dentro del turno. La holgura
     * minima de cada sufijo se precalcula una sola vez, con lo que valorar una posicion
     * cuesta tiempo constante.</p>
     *
     * @param paradas      numero de paradas ya programadas
     * @param minutoInicio instante de arranque de la ruta
     * @param minutoFinSinPausa instante de fin antes de insertar la pausa
     * @param finTurno     cierre del turno de la unidad
     */
    private void ubicarPausa(int paradas, long minutoInicio, long minutoFinSinPausa, long finTurno,
                             ParametrosOperacion.Instantanea parametros) {
        posicionPausa = -1;
        inicioPausa = 0;
        desplazamientoPausa = 0;
        if (paradas == 0) {
            return;
        }

        final int duracion = parametros.minutosAlimentacion();
        final int separacion = parametros.minutosSeparacionCambioTurno();
        // Ventana de la jornada: la pausa arranca al menos una separacion despues del inicio
        // del turno y termina al menos una separacion antes de su cierre.
        final long inicioTurno = Turno.inicioDelTurno(minutoInicio);
        final long ventanaDesde = inicioTurno + separacion;
        final long ventanaHasta = inicioTurno + Turno.DURACION_MIN - separacion - duracion;
        if (ventanaDesde > ventanaHasta) {
            return;
        }

        holguraSufijo[paradas] = SIN_PLAZO;
        for (int j = paradas - 1; j >= 0; j--) {
            long holgura = limiteParada[j] - llegadaParada[j];
            holguraSufijo[j] = Math.min(holguraSufijo[j + 1], holgura);
        }

        long mejorDesplazamiento = Long.MAX_VALUE;
        for (int k = 0; k <= paradas; k++) {
            long salidaPrevia = k == 0 ? minutoInicio : salidaParada[k - 1];
            // Si la unidad todavia no entro en la ventana, la pausa espera a que abra.
            long arranque = Math.max(salidaPrevia, ventanaDesde);
            if (arranque > ventanaHasta) {
                continue;
            }
            long desplazamiento = arranque + duracion - salidaPrevia;
            if (minutoFinSinPausa + desplazamiento > finTurno) {
                continue;
            }
            if (desplazamiento > holguraSufijo[k]) {
                continue;
            }
            if (desplazamiento < mejorDesplazamiento) {
                mejorDesplazamiento = desplazamiento;
                posicionPausa = k;
                inicioPausa = arranque;
            }
        }
        if (posicionPausa >= 0) {
            desplazamientoPausa = mejorDesplazamiento;
        }
    }

    /**
     * Almacen que minimiza el desvio hacia el destino entre los que tienen inventario
     * suficiente, o {@code -1} si ninguno lo tiene o ninguno es alcanzable. Deja en
     * {@link #kmHastaAlmacen} la distancia hasta el elegido, que el llamante ya necesita.
     */
    private int mejorAlmacen(int puntoActual, int puntoDestino, int faltante) {
        MatrizDistancias matriz = instancia.matriz();
        int elegido = -1;
        int mejorDesvio = Integer.MAX_VALUE;
        for (int a = 0; a < cantidadAlmacenes; a++) {
            if (inventarioTrabajo[a] < faltante) {
                continue;
            }
            int puntoAlmacen = instancia.puntoAlmacen(a);
            int ida = matriz.km(puntoActual, puntoAlmacen);
            if (ida >= MatrizDistancias.INALCANZABLE) {
                continue;
            }
            int vuelta = matriz.km(puntoAlmacen, puntoDestino);
            if (vuelta >= MatrizDistancias.INALCANZABLE) {
                continue;
            }
            int desvio = ida + vuelta;
            // La comparacion estricta hace determinista el desempate: gana el de menor indice.
            if (desvio < mejorDesvio) {
                mejorDesvio = desvio;
                elegido = a;
                kmHastaAlmacen = ida;
            }
        }
        return elegido;
    }

    /** Convierte los arreglos de trabajo en la ruta que consume el visualizador. */
    private Ruta materializar(int indiceUnidad) {
        int total = cantidadParadas + (posicionPausa >= 0 ? 1 : 0);
        List<Parada> lista = new ArrayList<>(total);
        int nodoAnterior = instancia.unidadNodo(indiceUnidad);
        for (int j = 0; j < cantidadParadas; j++) {
            if (j == posicionPausa) {
                lista.add(Parada.alimentacion(nodoAnterior, inicioPausa, inicioPausa + duracionPausa()));
            }
            long corrimiento = posicionPausa >= 0 && j >= posicionPausa ? desplazamientoPausa : 0L;
            long llegada = llegadaParada[j] + corrimiento;
            long salida = salidaParada[j] + corrimiento;
            if (claseParada[j] == ENTREGA) {
                lista.add(Parada.entrega(nodoParada[j], identificadorParada[j], cantidadParada[j],
                        llegada, salida, kmParada[j]));
            } else {
                lista.add(Parada.abastecimiento(nodoParada[j], identificadorParada[j], cantidadParada[j],
                        llegada, salida, kmParada[j]));
            }
            nodoAnterior = nodoParada[j];
        }
        if (posicionPausa == cantidadParadas) {
            lista.add(Parada.alimentacion(nodoAnterior, inicioPausa, inicioPausa + duracionPausa()));
        }
        return new Ruta(instancia.unidadCodigo(indiceUnidad), instancia.unidadTipo(indiceUnidad),
                instancia.unidadNodo(indiceUnidad), instancia.unidadMinutoDisponible(indiceUnidad), lista);
    }

    private int duracionPausa() {
        return instancia.parametros().minutosAlimentacion();
    }

    /** Programacion de una unidad que no recibe ninguna visita. */
    private Programacion programacionVacia(int indiceUnidad) {
        prepararResultadoVacio(indiceUnidad);
        Ruta ruta = Ruta.vacia(instancia.unidadCodigo(indiceUnidad), instancia.unidadTipo(indiceUnidad),
                instancia.unidadNodo(indiceUnidad), instancia.unidadMinutoDisponible(indiceUnidad));
        return new Programacion(true, 0, 0, 0.0, 0, 0, ultimoMinutoFin, ruta);
    }

    private void prepararResultadoVacio(int indiceUnidad) {
        cantidadParadas = 0;
        posicionPausa = -1;
        inicioPausa = 0;
        desplazamientoPausa = 0;
        ultimaFactible = true;
        ultimoDesfase = 0;
        ultimosKilometros = 0;
        ultimoCosto = 0.0;
        ultimasEntregas = 0;
        ultimasUnidadesEntregadas = 0;
        ultimoMinutoFin = instancia.unidadMinutoDisponible(indiceUnidad);
    }

    /**
     * Asegura sitio para el peor caso: cada entrega puede exigir un abastecimiento previo,
     * de modo que una secuencia de {@code longitud} visitas produce hasta el doble de paradas.
     */
    private void asegurarCapacidad(int longitud) {
        int necesario = longitud * 2;
        if (claseParada.length >= necesario) {
            return;
        }
        int nuevo = Math.max(necesario, claseParada.length * 2);
        claseParada = new byte[nuevo];
        nodoParada = new int[nuevo];
        identificadorParada = new int[nuevo];
        cantidadParada = new int[nuevo];
        kmParada = new int[nuevo];
        llegadaParada = new long[nuevo];
        salidaParada = new long[nuevo];
        limiteParada = new long[nuevo];
        holguraSufijo = new long[nuevo + 1];
        demandaSufijo = new int[nuevo];
    }
}
