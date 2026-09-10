package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Nucleo compartido de los tres operadores de reconstruccion del apartado 7.3.2 del ISA.
 *
 * <p>La insercion voraz, la insercion por arrepentimiento de orden k y la insercion voraz con
 * parpadeo se diferencian unicamente en dos numeros: el orden del arrepentimiento y la
 * probabilidad de descartar una posicion candidata. Todo lo demas (la enumeracion de
 * posiciones, la comprobacion de factibilidad dura, la cache de la mejor posicion por unidad y
 * la actualizacion incremental tras cada colocacion) es identico, y aqui vive una sola vez.
 * Los arreglos de trabajo, que son la parte cara, se reservan tambien una sola vez y los tres
 * operadores comparten esta instancia.</p>
 *
 * <h2>Factibilidad dura</h2>
 * <p>Una posicion candidata solo se acepta cuando {@link ProgramadorRuta} programa la ruta
 * resultante y la declara factible, con lo que las siete restricciones del apartado 2.6
 * quedan comprobadas por el decodificador y no por una copia de sus reglas. La valoracion usa
 * el modo que <b>no consume inventario</b>: tantear un movimiento no puede gastar el
 * inventario de un almacen intermedio, que es un recurso compartido por todas las rutas del
 * plan.</p>
 *
 * <h2>Costo de una pasada</h2>
 * <p>Una reconstruccion coloca hasta {@code q} tareas y tras cada colocacion solo cambia una
 * ruta, de modo que basta con volver a valorar esa columna de la cache. Las unidades
 * candidatas de una tarea se restringen ademas a las que atienden alguno de sus vecinos
 * cercanos, mas todas las de ruta vacia, que es la granularidad del vecindario del apartado 10
 * del ISA. Si esa restriccion no encontrase ninguna posicion factible se recurre al recorrido
 * completo de la flota, de modo que la granularidad nunca puede aumentar la H del nivel 1.</p>
 */
public final class MotorInsercion {

    /** Marca de posicion inexistente o infactible. */
    private static final double INFINITO = Double.MAX_VALUE;
    /**
     * Aporte al arrepentimiento de una unidad que no admite la tarea. Domina cualquier
     * diferencia real de costo, de modo que una tarea que solo cabe en pocas rutas se coloca
     * antes que uno que cabe en muchas, que es el efecto que busca Ropke y Pisinger (2006).
     */
    private static final double APORTE_SIN_UNIDAD = 1.0e9;

    private final InstanciaPlanificacion instancia;
    private final TareasAlns tareas;
    private final MatrizDistancias matriz;
    private final int cantidadAlmacenes;
    private final int cantidadPedidos;
    private final int cantidadTareas;
    private final int cantidadUnidades;
    private final int ventana;
    private final int maximoUnidadesCandidatas;

    /** Costo de la ruta resultante de insertar la tarea en la unidad, indexado por par. */
    private final double[] costoNuevo;
    /** Kilometros de esa ruta resultante. */
    private final int[] kilometrosNuevos;
    /** Posicion de insercion que produce ese costo. */
    private final int[] posicionNueva;
    /** Unidades consideradas para cada tarea en la pasada en curso. */
    private final int[] candidatasDe;
    /** Marca de pertenencia al conjunto de unidades candidatas, indexada por par. */
    private final byte[] esCandidata;
    private final int[] cantidadCandidatas;

    private final int[] mejorUnidad;
    private final double[] mejorDelta;
    private final double[] arrepentimiento;
    private final int[] pendientes;

    private int[] secuencia;
    private int[] cantidades;

    /**
     * @param instancia  fotografia del problema
     * @param parametros parametros calibrables del apartado 7.4
     */
    public MotorInsercion(InstanciaPlanificacion instancia, ParametrosAlns parametros) {
        this(instancia, new TareasAlns(instancia), parametros);
    }

    /**
     * @param instancia  fotografia del problema
     * @param tareas     descomposicion de los pedidos en visitas, compartida con los estados
     * @param parametros parametros calibrables del apartado 7.4
     */
    public MotorInsercion(InstanciaPlanificacion instancia, TareasAlns tareas, ParametrosAlns parametros) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.matriz = instancia.matriz();
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();
        this.cantidadPedidos = instancia.cantidadPedidos();
        this.cantidadTareas = tareas.cantidad();
        this.cantidadUnidades = Math.max(1, instancia.cantidadUnidades());
        this.ventana = parametros.ventanaVecindad();
        this.maximoUnidadesCandidatas = parametros.maximoUnidadesCandidatas();
        int pares = Math.max(1, cantidadTareas) * cantidadUnidades;
        this.costoNuevo = new double[pares];
        this.kilometrosNuevos = new int[pares];
        this.posicionNueva = new int[pares];
        this.candidatasDe = new int[pares];
        this.esCandidata = new byte[pares];
        this.cantidadCandidatas = new int[Math.max(1, cantidadTareas)];
        this.mejorUnidad = new int[Math.max(1, cantidadTareas)];
        this.mejorDelta = new double[Math.max(1, cantidadTareas)];
        this.arrepentimiento = new double[Math.max(1, cantidadTareas)];
        this.pendientes = new int[Math.max(1, cantidadTareas)];
        this.secuencia = new int[16];
        this.cantidades = new int[16];
    }

    /**
     * Coloca en las rutas cuantos pedidos del banco admitan posicion factible.
     *
     * @param ordenArrepentimiento orden k del arrepentimiento; con valor menor que dos la
     *                             seleccion es puramente voraz
     * @param parpadeo             probabilidad de descartar cada posicion candidata
     */
    void reconstruir(EstadoAlns estado, Aleatorio aleatorio, PresupuestoComputo presupuesto,
                     int ordenArrepentimiento, double parpadeo) {
        int n = estado.tamanoBanco();
        if (n == 0) {
            return;
        }
        asegurarCapacidad(estado.capacidadRuta() + 1);
        for (int i = 0; i < n; i++) {
            pendientes[i] = estado.bancoEn(i);
        }
        for (int i = 0; i < n; i++) {
            if (presupuesto.agotado()) {
                return;
            }
            calcularTarea(estado, pendientes[i], aleatorio, parpadeo, ordenArrepentimiento);
        }

        while (n > 0) {
            if (presupuesto.agotado()) {
                return;
            }
            // Una tarea sin posicion factible ya no puede recuperarla en esta pasada: las
            // rutas solo crecen, de modo que se aparta y se queda en el banco.
            int i = 0;
            while (i < n) {
                if (mejorUnidad[pendientes[i]] < 0) {
                    pendientes[i] = pendientes[--n];
                } else {
                    i++;
                }
            }
            if (n == 0) {
                return;
            }
            int elegido = seleccionar(n, ordenArrepentimiento);
            int tarea = pendientes[elegido];
            int unidad = mejorUnidad[tarea];
            int par = tarea * cantidadUnidades + unidad;
            estado.insertarValorado(unidad, posicionNueva[par], tarea,
                    kilometrosNuevos[par], costoNuevo[par]);
            pendientes[elegido] = pendientes[--n];

            // Solo cambio una ruta, de modo que solo esa columna de la cache queda obsoleta.
            for (int j = 0; j < n; j++) {
                int otro = pendientes[j];
                if (esCandidata[otro * cantidadUnidades + unidad] != 0) {
                    valorarUnidad(estado, otro, unidad, aleatorio, parpadeo);
                }
                derivarMejor(estado, otro, ordenArrepentimiento);
            }
        }
    }

    /**
     * Indica si la unidad podria atender la tarea en el mejor de los casos: capacidad
     * suficiente, destino alcanzable y llegada directa dentro del plazo y del turno. La
     * llegada directa es una cota inferior valida del instante de entrega, porque las
     * distancias de la matriz son caminos minimos y por tanto cumplen la desigualdad
     * triangular. Sirve para descartar unidades enteras sin programar ninguna ruta.
     */
    static boolean unidadPuedeAtender(InstanciaPlanificacion instancia, TareasAlns tareas,
                                      int unidad, int tarea) {
        if (tareas.unidades(tarea) > instancia.unidadCapacidad(unidad)) {
            return false;
        }
        int km = instancia.matriz().km(instancia.puntoUnidad(unidad), tareas.punto(tarea));
        if (km >= MatrizDistancias.INALCANZABLE) {
            return false;
        }
        long llegada = instancia.unidadMinutoDisponible(unidad)
                + instancia.parametros().minutosDeViaje(instancia.unidadTipo(unidad), km);
        if (llegada > tareas.limite(tarea)) {
            return false;
        }
        return llegada + instancia.parametros().minutosAcondicionamiento()
                <= instancia.unidadMinutoFinTurno(unidad);
    }

    // ----------------------------------------------------------------- internos

    /** Elige la tarea que se coloca a continuacion segun el criterio del operador. */
    private int seleccionar(int n, int ordenArrepentimiento) {
        int elegido = 0;
        if (ordenArrepentimiento < 2) {
            double mejor = INFINITO;
            for (int i = 0; i < n; i++) {
                double delta = mejorDelta[pendientes[i]];
                if (delta < mejor) {
                    mejor = delta;
                    elegido = i;
                }
            }
            return elegido;
        }
        double mejorArrepentimiento = Double.NEGATIVE_INFINITY;
        double desempate = INFINITO;
        for (int i = 0; i < n; i++) {
            int tarea = pendientes[i];
            double valor = arrepentimiento[tarea];
            if (valor > mejorArrepentimiento
                    || (valor == mejorArrepentimiento && mejorDelta[tarea] < desempate)) {
                mejorArrepentimiento = valor;
                desempate = mejorDelta[tarea];
                elegido = i;
            }
        }
        return elegido;
    }

    /** Valora la tarea en todas sus unidades candidatas y deja listo su mejor movimiento. */
    private void calcularTarea(EstadoAlns estado, int tarea, Aleatorio aleatorio, double parpadeo,
                               int ordenArrepentimiento) {
        int base = tarea * cantidadUnidades;
        for (int j = 0; j < cantidadCandidatas[tarea]; j++) {
            esCandidata[base + candidatasDe[base + j]] = 0;
        }
        cantidadCandidatas[tarea] = reunirUnidades(estado, tarea);
        for (int j = 0; j < cantidadCandidatas[tarea]; j++) {
            valorarUnidad(estado, tarea, candidatasDe[base + j], aleatorio, parpadeo);
        }
        derivarMejor(estado, tarea, ordenArrepentimiento);
        if (mejorUnidad[tarea] < 0 && cantidadCandidatas[tarea] < cantidadUnidades) {
            ampliarATodaLaFlota(estado, tarea, aleatorio, parpadeo);
            derivarMejor(estado, tarea, ordenArrepentimiento);
        }
    }

    /**
     * Reune las unidades candidatas de la tarea: todas las de ruta vacia, porque valorarlas
     * cuesta una sola posicion y son la unica via de abrir una ruta nueva, mas las que
     * atienden alguno de sus vecinos cercanos.
     */
    private int reunirUnidades(EstadoAlns estado, int tarea) {
        int base = tarea * cantidadUnidades;
        int m = 0;
        for (int u = 0; u < cantidadUnidades; u++) {
            if (estado.longitudRuta(u) == 0 && unidadPuedeAtender(instancia, tareas, u, tarea)) {
                candidatasDe[base + m++] = u;
                esCandidata[base + u] = 1;
            }
        }
        int conRuta = 0;
        int[] vecinos = matriz.vecinosCercanos(tareas.punto(tarea));
        int limite = Math.min(vecinos.length, ventana * 4);
        for (int i = 0; i < limite && conRuta < maximoUnidadesCandidatas; i++) {
            int vecino = pedidoDelPunto(vecinos[i]);
            if (vecino < 0) {
                continue;
            }
            int unidad = estado.unidadDePedido(vecino);
            if (unidad < 0 || esCandidata[base + unidad] != 0
                    || !unidadPuedeAtender(instancia, tareas, unidad, tarea)) {
                continue;
            }
            candidatasDe[base + m++] = unidad;
            esCandidata[base + unidad] = 1;
            conRuta++;
        }
        return m;
    }

    /** Respaldo cuando la granularidad no halla ninguna posicion factible: recorre la flota. */
    private void ampliarATodaLaFlota(EstadoAlns estado, int tarea, Aleatorio aleatorio, double parpadeo) {
        int base = tarea * cantidadUnidades;
        int m = cantidadCandidatas[tarea];
        for (int u = 0; u < cantidadUnidades; u++) {
            if (esCandidata[base + u] != 0 || !unidadPuedeAtender(instancia, tareas, u, tarea)) {
                continue;
            }
            candidatasDe[base + m++] = u;
            esCandidata[base + u] = 1;
            // El respaldo no parpadea: su cometido es no perder ninguna posicion factible.
            valorarUnidad(estado, tarea, u, aleatorio, 0.0);
        }
        cantidadCandidatas[tarea] = m;
    }

    /**
     * Enumera todas las posiciones de insercion de la tarea en la ruta de la unidad y guarda
     * la mejor factible. La secuencia de trabajo se construye una sola vez y la visita se
     * desplaza una posicion hacia la izquierda con un intercambio, de modo que probar una
     * posicion mas cuesta tiempo constante.
     */
    private void valorarUnidad(EstadoAlns estado, int tarea, int unidad, Aleatorio aleatorio,
                               double parpadeo) {
        int par = tarea * cantidadUnidades + unidad;
        costoNuevo[par] = INFINITO;
        posicionNueva[par] = -1;
        kilometrosNuevos[par] = 0;

        final ProgramadorRuta programador = estado.programador();
        final int longitud = estado.longitudRuta(unidad);
        asegurarCapacidad(longitud + 1);
        final int[] fila = estado.filaPedidos(unidad);
        final double costoActual = estado.costoRuta(unidad);
        final double costoPorKm = instancia.unidadTipo(unidad).costoPorKm();

        final int puntoTarea = tareas.punto(tarea);
        System.arraycopy(fila, 0, secuencia, 0, longitud);
        System.arraycopy(estado.filaCantidades(unidad), 0, cantidades, 0, longitud);
        secuencia[longitud] = tareas.pedido(tarea);
        cantidades[longitud] = tareas.unidades(tarea);

        double mejorCosto = INFINITO;
        int mejorPosicion = -1;
        int mejorKilometros = 0;
        for (int i = longitud; i >= 0; i--) {
            boolean saltar = parpadeo > 0.0 && aleatorio.conProbabilidad(parpadeo);
            // Poda por el incremento directo de kilometros: si el rodeo que introduce la
            // visita ya cuesta mas que la mejor posicion hallada, programar la ruta entera
            // no puede mejorarla. Es una poda de costo, no de factibilidad: mientras no haya
            // ninguna posicion factible no descarta nada, de modo que nunca aumenta la H.
            if (!saltar && mejorPosicion >= 0
                    && costoPorKm * incrementoDirecto(unidad, fila, longitud, i, puntoTarea)
                        >= mejorCosto - costoActual) {
                saltar = true;
            }
            if (!saltar
                    && programador.evaluar(unidad, secuencia, cantidades, longitud + 1)
                    && programador.ultimaFactible()
                    && programador.ultimoCosto() < mejorCosto) {
                mejorCosto = programador.ultimoCosto();
                mejorPosicion = i;
                mejorKilometros = programador.ultimosKilometros();
            }
            if (i > 0) {
                int t = secuencia[i - 1];
                secuencia[i - 1] = secuencia[i];
                secuencia[i] = t;
                t = cantidades[i - 1];
                cantidades[i - 1] = cantidades[i];
                cantidades[i] = t;
            }
        }
        costoNuevo[par] = mejorCosto;
        posicionNueva[par] = mejorPosicion;
        kilometrosNuevos[par] = mejorKilometros;
    }

    /** Kilometros que anade la visita entre las dos paradas contiguas de la posicion dada. */
    private int incrementoDirecto(int unidad, int[] fila, int longitud, int posicion, int punto) {
        int anterior = posicion == 0
                ? instancia.puntoUnidad(unidad)
                : instancia.puntoPedido(fila[posicion - 1]);
        int ida = matriz.km(anterior, punto);
        if (posicion == longitud) {
            return ida;
        }
        int siguiente = instancia.puntoPedido(fila[posicion]);
        return ida + matriz.km(punto, siguiente) - matriz.km(anterior, siguiente);
    }

    /**
     * Deriva de la cache el mejor movimiento de la tarea y su arrepentimiento de orden k.
     *
     * <p>Todas las unidades de ruta vacia cuentan como una sola alternativa, representada por
     * la mas barata de ellas. Sin esa fusion el arrepentimiento seria siempre nulo mientras
     * quedasen dos unidades libres, porque la segunda mejor alternativa costaria practicamente
     * lo mismo que la primera y el operador degeneraria en la insercion voraz.</p>
     */
    private void derivarMejor(EstadoAlns estado, int tarea, int ordenArrepentimiento) {
        int base = tarea * cantidadUnidades;
        int total = cantidadCandidatas[tarea];

        double mejorVacia = INFINITO;
        int unidadVacia = -1;
        double primero = INFINITO;
        double segundo = INFINITO;
        double tercero = INFINITO;
        int unidadPrimera = -1;
        int alternativas = 0;

        for (int j = 0; j < total; j++) {
            int unidad = candidatasDe[base + j];
            double costo = costoNuevo[base + unidad];
            if (costo == INFINITO) {
                continue;
            }
            double delta = costo - estado.costoRuta(unidad);
            if (estado.longitudRuta(unidad) == 0) {
                if (delta < mejorVacia) {
                    mejorVacia = delta;
                    unidadVacia = unidad;
                }
                continue;
            }
            alternativas++;
            if (delta < primero) {
                tercero = segundo;
                segundo = primero;
                primero = delta;
                unidadPrimera = unidad;
            } else if (delta < segundo) {
                tercero = segundo;
                segundo = delta;
            } else if (delta < tercero) {
                tercero = delta;
            }
        }
        if (unidadVacia >= 0) {
            alternativas++;
            if (mejorVacia < primero) {
                tercero = segundo;
                segundo = primero;
                primero = mejorVacia;
                unidadPrimera = unidadVacia;
            } else if (mejorVacia < segundo) {
                tercero = segundo;
                segundo = mejorVacia;
            } else if (mejorVacia < tercero) {
                tercero = mejorVacia;
            }
        }

        mejorUnidad[tarea] = unidadPrimera;
        mejorDelta[tarea] = primero;
        if (ordenArrepentimiento < 2) {
            return;
        }
        if (unidadPrimera < 0) {
            arrepentimiento[tarea] = Double.NEGATIVE_INFINITY;
            return;
        }
        double suma = alternativas >= 2 ? segundo - primero : APORTE_SIN_UNIDAD;
        if (ordenArrepentimiento >= 3) {
            suma += alternativas >= 3 ? tercero - primero : APORTE_SIN_UNIDAD;
        }
        arrepentimiento[tarea] = suma;
    }

    private int pedidoDelPunto(int punto) {
        int pedido = punto - cantidadAlmacenes;
        return pedido >= 0 && pedido < cantidadPedidos ? pedido : -1;
    }

    private void asegurarCapacidad(int necesaria) {
        if (secuencia.length >= necesaria) {
            return;
        }
        secuencia = new int[necesaria];
        cantidades = new int[necesaria];
    }
}
