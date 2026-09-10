package org.kindbox.core.evaluacion;

import java.util.List;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;

/**
 * Comprobacion de las siete restricciones duras del apartado 2.6 del ISA sobre un plan ya
 * construido.
 *
 * <p>Recorre la solucion y acumula <b>todas</b> las infracciones que encuentra, no solo la
 * primera, porque la verificacion de validez del apartado 12.4 necesita el detalle completo
 * para diagnosticar una corrida invalida.</p>
 *
 * <p>La clave del componente es que <b>no confia en los valores que trae la ruta</b>.
 * Instantes de llegada, cargas a bordo y distancias se recalculan a partir de la instancia:
 * las distancias con la matriz, los tiempos con la velocidad vigente del tipo de unidad y la
 * carga acumulando abastecimientos y entregas desde la carga inicial de la unidad. Su razon
 * de ser es precisamente detectar que un algoritmo haya calculado mal alguno de esos valores,
 * de modo que tomarlos por buenos anularia la comprobacion.</p>
 *
 * <p>Donde la ruta declara un instante <em>posterior</em> al que se recalcula se respeta el
 * declarado, porque una espera deliberada es legitima y nunca vuelve factible lo que no lo
 * era; donde declara uno anterior manda el recalculado, que es el unico fisicamente posible
 * con la velocidad vigente.</p>
 *
 * <p>Interpretacion de la restriccion 4, la de alimentacion: la hora continua es exigible por
 * <b>jornada</b> y no por ruta, ya que una ruta es solo el tramo de la jornada que alcanzo a
 * planificarse en esta iteracion. Por eso se comprueba que la pausa presente dure lo debido y
 * arranque dentro de la ventana admisible del turno, y que no haya mas de una, pero su
 * ausencia no se declara infraccion. {@link ProgramadorRuta} sostiene la misma
 * interpretacion.</p>
 *
 * <p><b>Instante contra el que se comprueban los bloqueos.</b> Los bloqueos se activan y se
 * desactivan a lo largo del dia, mientras que el planificador resuelve una fotografia
 * estatica: su matriz de distancias se construye con los tramos bloqueados en el instante de
 * la fotografia, conforme al apartado 11.2 del ISA. Una ruta que dura horas puede por tanto
 * cruzar una calle que se bloquea despues de haberse planificado.</p>
 *
 * <p>Por eso el verificador comprueba la restriccion 6 <b>en el instante de la fotografia</b>
 * y no en el de paso: juzga al planificador con el mismo conjunto de informacion que el
 * planificador tuvo. Comprobarla en el instante de paso haria que la verificacion de
 * factibilidad del apartado 12.4 no pudiera superarse nunca por construccion, y estaria
 * midiendo la clarividencia del algoritmo en lugar de su correccion.</p>
 *
 * <p>Esa informacion no se descarta. El verificador de ejecucion que devuelve
 * {@link #deEjecucion(RegistroBloqueos)} comprueba cada tramo en el instante real de paso y
 * sirve de diagnostico: senala las rutas que toparan con un bloqueo sobrevenido y que, en
 * consecuencia, necesitaran la maniobra de retorno y la penalizacion de tiempo y distancia
 * del apartado 11.2, con el pedido reincorporandose a la siguiente ejecucion del
 * planificador con su holgura ya reducida.</p>
 *
 * <p>La restriccion 6, la de tramos bloqueados, necesita el registro de bloqueos del
 * escenario. Se admite {@code null}, y en ese caso esa unica comprobacion se omite en
 * silencio: es lo que corresponde en las pruebas de operadores y en los escenarios que no
 * cargan el archivo mensual de bloqueos. Las otras seis restricciones se comprueban
 * igualmente.</p>
 */
public final class VerificadorRestricciones implements VerificadorFactibilidad {

    private final RegistroBloqueos bloqueos;
    private final boolean bloqueosEnInstanteDeRecorrido;

    /** Verificador sin registro de bloqueos: omite la restriccion de tramos bloqueados. */
    public VerificadorRestricciones() {
        this(null);
    }

    /**
     * Verificador que comprueba los bloqueos con el mismo conjunto de informacion que tuvo
     * el planificador, es decir con los tramos bloqueados en el instante de la fotografia.
     *
     * @param bloqueos registro de bloqueos del escenario, o {@code null} para omitir la
     *                 comprobacion de tramos bloqueados
     */
    public VerificadorRestricciones(RegistroBloqueos bloqueos) {
        this(bloqueos, false);
    }

    /**
     * @param bloqueos                     registro de bloqueos, o {@code null} para omitir
     * @param bloqueosEnInstanteDeRecorrido {@code false} comprueba cada tramo contra los
     *        bloqueos vigentes en el instante de la fotografia, que es el conjunto de
     *        informacion del planificador; {@code true} lo comprueba contra los vigentes en
     *        el instante en que la unidad recorreria ese tramo. Ver el javadoc de clase.
     */
    public VerificadorRestricciones(RegistroBloqueos bloqueos, boolean bloqueosEnInstanteDeRecorrido) {
        this.bloqueos = bloqueos;
        this.bloqueosEnInstanteDeRecorrido = bloqueosEnInstanteDeRecorrido;
    }

    /**
     * Verificador de ejecucion, que comprueba los bloqueos en el instante en que cada tramo
     * se recorre. No sirve para la verificacion de validez del apartado 12.4 sino como
     * diagnostico: identifica las rutas que toparan con un tramo que se bloquea despues de
     * la fotografia y que, por tanto, necesitaran la maniobra de retorno del apartado 11.2.
     */
    public static VerificadorRestricciones deEjecucion(RegistroBloqueos bloqueos) {
        return new VerificadorRestricciones(bloqueos, true);
    }

    @Override
    public ResultadoVerificacion verificar(InstanciaPlanificacion instancia, Solucion solucion) {
        ResultadoVerificacion.Acumulador acumulador = ResultadoVerificacion.acumulador();
        int[] inventario = inventarioInicial(instancia);
        int[] entregado = new int[instancia.cantidadPedidos()];
        for (Ruta ruta : solucion.rutas()) {
            revisarRuta(instancia, ruta, inventario, entregado, acumulador);
        }
        revisarEntregasExcedidas(instancia, entregado, acumulador);
        return acumulador.construir();
    }

    @Override
    public ResultadoVerificacion verificarRuta(InstanciaPlanificacion instancia, Ruta ruta) {
        ResultadoVerificacion.Acumulador acumulador = ResultadoVerificacion.acumulador();
        int[] inventario = inventarioInicial(instancia);
        int[] entregado = new int[instancia.cantidadPedidos()];
        revisarRuta(instancia, ruta, inventario, entregado, acumulador);
        revisarEntregasExcedidas(instancia, entregado, acumulador);
        return acumulador.construir();
    }

    // ----------------------------------------------------------------- internos

    /**
     * Recorre una ruta recalculando distancias, instantes y carga, y anota cuanta infraccion
     * encuentre. El inventario y las entregas acumuladas son de todo el plan, porque los
     * almacenes intermedios son un recurso compartido y un pedido admite entregas parciales
     * repartidas entre varias rutas.
     */
    private void revisarRuta(InstanciaPlanificacion instancia, Ruta ruta, int[] inventario,
                             int[] entregado, ResultadoVerificacion.Acumulador acumulador) {
        final String codigo = ruta.codigoUnidad();
        final int unidad = instancia.indiceDeUnidad(codigo);
        final ParametrosOperacion.Instantanea parametros = instancia.parametros();
        final MatrizDistancias matriz = instancia.matriz();

        // Con la unidad presente en la fotografia manda la fotografia; si no lo esta, por
        // ejemplo al revisar una ruta historica, se acepta la cabecera de la propia ruta.
        final TipoUnidad tipo = unidad >= 0 ? instancia.unidadTipo(unidad) : ruta.tipoUnidad();
        final int capacidad = tipo.capacidad();
        final long minutoInicio = unidad >= 0
                ? Math.max(ruta.minutoInicio(), instancia.unidadMinutoDisponible(unidad))
                : ruta.minutoInicio();
        final long finTurno = unidad >= 0
                ? instancia.unidadMinutoFinTurno(unidad)
                : Turno.finDelTurno(minutoInicio);

        int carga = unidad >= 0 ? instancia.unidadCarga(unidad) : 0;
        if (carga > capacidad) {
            acumulador.agregar(Infraccion.CAPACIDAD_EXCEDIDA, codigo, -1,
                    "la unidad arranca con " + carga + " paquetes y su capacidad es " + capacidad);
        }

        int punto = unidad >= 0 ? instancia.puntoUnidad(unidad) : -1;
        long instante = minutoInicio;
        int pausas = 0;

        final long inicioTurno = Turno.inicioDelTurno(minutoInicio);
        final int minutosAlimentacion = parametros.minutosAlimentacion();
        final int separacion = parametros.minutosSeparacionCambioTurno();
        final long pausaDesde = inicioTurno + separacion;
        final long pausaHasta = inicioTurno + Turno.DURACION_MIN - separacion - minutosAlimentacion;

        final List<Parada> paradas = ruta.paradas();
        for (int i = 0; i < paradas.size(); i++) {
            final Parada parada = paradas.get(i);
            final int puntoDestino = parada.tipo() == TipoParada.ALIMENTACION
                    ? punto
                    : puntoDeParada(instancia, parada);

            int km = distanciaDelTramo(matriz, punto, puntoDestino, parada, codigo, i, acumulador);
            if (bloqueos != null && km > 0 && punto >= 0 && puntoDestino >= 0) {
                revisarBloqueos(instancia, tipo, punto, puntoDestino, instante, codigo, i, acumulador);
            }

            // La llegada declarada solo se acepta si no adelanta a la que permite la velocidad.
            long llegada = Math.max(instante + parametros.minutosDeViaje(tipo, km), parada.minutoLlegada());
            long duracionDeclarada = parada.minutoSalida() - parada.minutoLlegada();

            switch (parada.tipo()) {
                case ENTREGA -> {
                    duracionDeclarada = revisarEntrega(instancia, parada, llegada, duracionDeclarada,
                            carga, entregado, codigo, i, acumulador);
                    carga = Math.max(0, carga - parada.cantidad());
                }
                case ABASTECIMIENTO -> {
                    revisarAbastecimiento(instancia, parada, inventario, codigo, i, acumulador);
                    carga += parada.cantidad();
                    if (carga > capacidad) {
                        acumulador.agregar(Infraccion.CAPACIDAD_EXCEDIDA, codigo, i,
                                "la carga a bordo llega a " + carga + " paquetes y la capacidad es " + capacidad);
                    }
                    duracionDeclarada = Math.max(0, duracionDeclarada);
                }
                case ALIMENTACION -> {
                    pausas++;
                    duracionDeclarada = revisarAlimentacion(parada, llegada, duracionDeclarada, pausas,
                            minutosAlimentacion, pausaDesde, pausaHasta, codigo, i, acumulador);
                }
            }

            instante = llegada + duracionDeclarada;
            if (puntoDestino >= 0) {
                punto = puntoDestino;
            }
        }

        if (instante > finTurno) {
            acumulador.agregar(Infraccion.TURNO_EXCEDIDO, codigo, paradas.size() - 1,
                    "la ruta termina en el minuto " + instante + " y el turno cierra en el " + finTurno);
        }
    }

    /**
     * Distancia recalculada del tramo. Si alguno de los extremos no pertenece a la
     * fotografia se conserva la declarada, porque sin punto en la matriz no hay contra que
     * contrastarla; si no existe camino se anota como tramo bloqueado, que es la unica causa
     * posible en una ciudad cuyas poligonales son abiertas.
     */
    private int distanciaDelTramo(MatrizDistancias matriz, int puntoOrigen, int puntoDestino,
                                  Parada parada, String codigo, int indice,
                                  ResultadoVerificacion.Acumulador acumulador) {
        if (puntoOrigen < 0 || puntoDestino < 0) {
            return Math.max(0, parada.kmDesdeAnterior());
        }
        int km = matriz.km(puntoOrigen, puntoDestino);
        if (km >= MatrizDistancias.INALCANZABLE) {
            acumulador.agregar(Infraccion.TRAMO_BLOQUEADO, codigo, indice,
                    "no existe camino hasta " + Ciudad.texto(parada.nodo()) + " con los bloqueos vigentes");
            return Math.max(0, parada.kmDesdeAnterior());
        }
        return km;
    }

    /**
     * Recorre nodo a nodo el camino minimo del tramo y comprueba que ninguna calle este
     * bloqueada.
     *
     * <p>El instante contra el que se comprueba depende del modo. En el modo por defecto es
     * el de la fotografia, que es el unico conjunto de informacion que tuvo el planificador.
     * En el modo de ejecucion es el instante real de paso, obtenido del tiempo de viaje de
     * los kilometros ya recorridos con la misma formula que usa el planificador.</p>
     */
    private void revisarBloqueos(InstanciaPlanificacion instancia, TipoUnidad tipo, int puntoOrigen,
                                 int puntoDestino, long minutoSalida, String codigo, int indice,
                                 ResultadoVerificacion.Acumulador acumulador) {
        int[] camino = instancia.matriz().camino(puntoOrigen, puntoDestino);
        ParametrosOperacion.Instantanea parametros = instancia.parametros();
        for (int j = 0; j + 1 < camino.length; j++) {
            if (RegistroBloqueos.aristaEntre(camino[j], camino[j + 1]) < 0) {
                continue;
            }
            long minuto = bloqueosEnInstanteDeRecorrido
                    ? minutoSalida + parametros.minutosDeViaje(tipo, j)
                    : instancia.minutoActual();
            if (bloqueos.bloqueada(camino[j], camino[j + 1], minuto)) {
                acumulador.agregar(Infraccion.TRAMO_BLOQUEADO, codigo, indice,
                        "la calle " + Ciudad.texto(camino[j]) + "-" + Ciudad.texto(camino[j + 1])
                                + " esta bloqueada en el minuto " + minuto);
                return;
            }
        }
    }

    /**
     * Comprueba plazo, carga disponible y tiempo de servicio de una entrega.
     *
     * @return la duracion de servicio que debe aplicarse, nunca menor que el acondicionamiento
     */
    private long revisarEntrega(InstanciaPlanificacion instancia, Parada parada, long llegada,
                                long duracionDeclarada, int carga, int[] entregado, String codigo,
                                int indice, ResultadoVerificacion.Acumulador acumulador) {
        int pedido = instancia.indiceDePedido(parada.idPedido());
        if (pedido >= 0) {
            entregado[pedido] += parada.cantidad();
            long limite = instancia.pedidoMinutoLimite(pedido);
            if (llegada > limite) {
                acumulador.agregar(Infraccion.PLAZO_INCUMPLIDO, codigo, indice,
                        "llegada al pedido " + parada.idPedido() + " en el minuto " + llegada
                                + " y su limite es el " + limite);
            }
        }
        if (parada.cantidad() <= 0) {
            acumulador.agregar(Infraccion.CARGA_INCONSISTENTE, codigo, indice,
                    "entrega del pedido " + parada.idPedido() + " sin unidades de producto");
        } else if (parada.cantidad() > carga) {
            acumulador.agregar(Infraccion.CARGA_INCONSISTENTE, codigo, indice,
                    "se entregan " + parada.cantidad() + " paquetes y a bordo solo hay " + carga);
        }
        int servicio = instancia.parametros().minutosAcondicionamiento();
        if (duracionDeclarada < servicio) {
            acumulador.agregar(Infraccion.SERVICIO_NO_CONTABILIZADO, codigo, indice,
                    "la visita registra " + duracionDeclarada + " minutos de servicio y corresponden " + servicio);
        }
        return Math.max(duracionDeclarada, servicio);
    }

    /** Comprueba que el almacen exista y que su inventario alcance, y lo descuenta. */
    private void revisarAbastecimiento(InstanciaPlanificacion instancia, Parada parada, int[] inventario,
                                       String codigo, int indice, ResultadoVerificacion.Acumulador acumulador) {
        int almacen = indiceDeAlmacen(instancia, parada.idAlmacen());
        if (almacen < 0) {
            acumulador.agregar(Infraccion.INVENTARIO_INSUFICIENTE, codigo, indice,
                    "abastecimiento en el almacen " + parada.idAlmacen() + ", que no existe en la instancia");
            return;
        }
        if (instancia.almacenEsCentral(almacen)) {
            return;
        }
        if (parada.cantidad() > inventario[almacen]) {
            acumulador.agregar(Infraccion.INVENTARIO_INSUFICIENTE, codigo, indice,
                    "se cargan " + parada.cantidad() + " unidades en " + instancia.almacenNombre(almacen)
                            + " y solo quedaban " + inventario[almacen]);
            inventario[almacen] = 0;
            return;
        }
        inventario[almacen] -= parada.cantidad();
    }

    /**
     * Comprueba la pausa de alimentacion: una sola por jornada, de una hora continua y
     * arrancando dentro de la ventana que la separa al menos una hora de cada cambio de turno.
     *
     * @return la duracion que debe aplicarse, nunca menor que la hora de alimentacion
     */
    private long revisarAlimentacion(Parada parada, long llegada, long duracionDeclarada, int pausas,
                                     int minutosAlimentacion, long pausaDesde, long pausaHasta,
                                     String codigo, int indice, ResultadoVerificacion.Acumulador acumulador) {
        if (pausas > 1) {
            acumulador.agregar(Infraccion.ALIMENTACION_INVALIDA, codigo, indice,
                    "la jornada registra " + pausas + " pausas de alimentacion y corresponde una sola");
        }
        if (duracionDeclarada < minutosAlimentacion) {
            acumulador.agregar(Infraccion.ALIMENTACION_INVALIDA, codigo, indice,
                    "la pausa dura " + duracionDeclarada + " minutos y corresponden " + minutosAlimentacion);
        }
        if (llegada < pausaDesde || llegada > pausaHasta) {
            acumulador.agregar(Infraccion.ALIMENTACION_INVALIDA, codigo, indice,
                    "la pausa arranca en el minuto " + llegada + " y la ventana de la jornada es ["
                            + pausaDesde + "," + pausaHasta + "]");
        }
        return Math.max(duracionDeclarada, minutosAlimentacion);
    }

    /** Anota los pedidos que recibieron mas unidades de las que tenian pendientes. */
    private void revisarEntregasExcedidas(InstanciaPlanificacion instancia, int[] entregado,
                                          ResultadoVerificacion.Acumulador acumulador) {
        for (int i = 0; i < entregado.length; i++) {
            if (entregado[i] > instancia.pedidoCantidad(i)) {
                acumulador.agregar(Infraccion.ENTREGA_EXCEDIDA, null, -1,
                        "el pedido " + instancia.pedidoId(i) + " recibe " + entregado[i]
                                + " unidades y solo tenia " + instancia.pedidoCantidad(i) + " pendientes");
            }
        }
    }

    /** Punto de la matriz que corresponde a la parada, o {@code -1} si no esta en la fotografia. */
    private static int puntoDeParada(InstanciaPlanificacion instancia, Parada parada) {
        if (parada.tipo() == TipoParada.ENTREGA) {
            int pedido = instancia.indiceDePedido(parada.idPedido());
            return pedido < 0 ? -1 : instancia.puntoPedido(pedido);
        }
        int almacen = indiceDeAlmacen(instancia, parada.idAlmacen());
        return almacen < 0 ? -1 : instancia.puntoAlmacen(almacen);
    }

    /** Indice local del almacen a partir de su identificador, o {@code -1} si no existe. */
    private static int indiceDeAlmacen(InstanciaPlanificacion instancia, int idAlmacen) {
        for (int a = 0; a < instancia.cantidadAlmacenes(); a++) {
            if (instancia.almacenId(a) == idAlmacen) {
                return a;
            }
        }
        return -1;
    }

    /** Copia del inventario de la instancia, que el recorrido va descontando. */
    private static int[] inventarioInicial(InstanciaPlanificacion instancia) {
        int[] inventario = new int[instancia.cantidadAlmacenes()];
        for (int a = 0; a < inventario.length; a++) {
            inventario[a] = instancia.almacenInventario(a);
        }
        return inventario;
    }
}
