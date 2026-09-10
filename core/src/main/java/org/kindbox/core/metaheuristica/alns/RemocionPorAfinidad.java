package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Remocion por afinidad de Shaw (1998), recogida en el apartado 7.3.2 del ISA: retira un
 * pedido semilla y los {@code q-1} pedidos mas relacionados con el.
 *
 * <p>La relacion combina las tres dimensiones que hacen intercambiables a dos pedidos en
 * PaqRap: la proximidad geografica de sus destinos, la cercania de sus instantes limite y la
 * coincidencia de la unidad que los atiende. Retirar un grupo relacionado deja al operador de
 * insercion un problema pequeno con muchas recombinaciones posibles, que es justo lo que un
 * grupo de pedidos elegidos al azar no ofrece: esos se reinsertan casi siempre donde
 * estaban.</p>
 *
 * <p>El grupo de candidatos no se recorre entero. Se toma de
 * {@link MatrizDistancias#vecinosCercanos(int)}, que devuelve los puntos en orden de
 * distancia creciente, y solo esa ventana se ordena por la relacion completa. Esa es la
 * granularidad del vecindario del apartado 10 del ISA y es lo que hace que el operador cueste
 * tiempo casi constante por pedido retirado en lugar de lineal en el tamano de la
 * instancia.</p>
 */
public final class RemocionPorAfinidad implements OperadorDestruccion {

    private final InstanciaPlanificacion instancia;
    private final TareasAlns tareas;
    private final MatrizDistancias matriz;
    private final int cantidadAlmacenes;
    private final int cantidadPedidos;
    private final int cantidadTareas;
    private final double determinismo;
    private final int ventana;
    private final double pesoDistancia;
    private final double pesoPlazo;
    private final double pesoUnidad;
    /** Normalizador de distancia: la mayor distancia posible en la reticula. */
    private final double normalizadorDistancia;
    /** Normalizador de plazo: la mayor separacion entre dos instantes limite de la instancia. */
    private final double normalizadorPlazo;

    private final int[] retirados;
    private final int[] unidadDeRetirado;
    private final int[] candidatos;
    private final double[] relacion;

    public RemocionPorAfinidad(InstanciaPlanificacion instancia, ParametrosAlns parametros) {
        this(instancia, new TareasAlns(instancia), parametros);
    }

    /**
     * @param instancia  fotografia del problema
     * @param tareas     descomposicion de los pedidos en visitas, compartida con el estado
     * @param parametros parametros calibrables del apartado 7.4
     */
    public RemocionPorAfinidad(InstanciaPlanificacion instancia, TareasAlns tareas,
                               ParametrosAlns parametros) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.matriz = instancia.matriz();
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();
        this.cantidadPedidos = instancia.cantidadPedidos();
        this.cantidadTareas = tareas.cantidad();
        this.determinismo = parametros.determinismoSeleccion();
        this.ventana = parametros.ventanaVecindad();
        this.pesoDistancia = parametros.pesoAfinidadDistancia();
        this.pesoPlazo = parametros.pesoAfinidadPlazo();
        this.pesoUnidad = parametros.pesoAfinidadUnidad();
        this.retirados = new int[Math.max(1, cantidadTareas)];
        this.unidadDeRetirado = new int[Math.max(1, cantidadTareas)];
        this.candidatos = new int[Math.max(1, cantidadTareas)];
        this.relacion = new double[Math.max(1, cantidadTareas)];
        this.normalizadorDistancia = Math.max(1.0, mayorDistancia());
        this.normalizadorPlazo = Math.max(1.0, mayorSeparacionDePlazos(instancia));
    }

    /** Cota superior de la distancia entre dos nodos de la ciudad, en kilometros. */
    private double mayorDistancia() {
        return org.kindbox.core.modelo.Ciudad.LARGO_KM + org.kindbox.core.modelo.Ciudad.ANCHO_KM;
    }

    private static double mayorSeparacionDePlazos(InstanciaPlanificacion instancia) {
        long menor = Long.MAX_VALUE;
        long mayor = Long.MIN_VALUE;
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            long limite = instancia.pedidoMinutoLimite(p);
            if (limite < menor) {
                menor = limite;
            }
            if (limite > mayor) {
                mayor = limite;
            }
        }
        return instancia.cantidadPedidos() == 0 ? 1.0 : (double) (mayor - menor);
    }

    @Override
    public String nombre() {
        return "remocion-por-afinidad";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        int colocados = estado.asignados();
        if (colocados == 0) {
            return 0;
        }
        int objetivo = Math.min(grado, colocados);
        int semilla = tareaColocadaAlAzar(estado, aleatorio);
        if (semilla < 0) {
            return 0;
        }
        int cantidad = 0;
        unidadDeRetirado[cantidad] = estado.unidadDe(semilla);
        retirados[cantidad] = semilla;
        cantidad++;
        estado.quitar(semilla);

        while (cantidad < objetivo) {
            int referencia = aleatorio.siguienteEntero(cantidad);
            int tareaReferencia = retirados[referencia];
            int unidadReferencia = unidadDeRetirado[referencia];
            int m = reunirCandidatos(estado, tareaReferencia);
            if (m == 0) {
                break;
            }
            for (int i = 0; i < m; i++) {
                relacion[candidatos[i]] = relacionar(estado, tareaReferencia, unidadReferencia, candidatos[i]);
            }
            OrdenPorClave.ascendente(relacion, candidatos, m);
            int elegido = candidatos[OrdenPorClave.indiceSesgado(m, determinismo, aleatorio)];
            unidadDeRetirado[cantidad] = estado.unidadDe(elegido);
            retirados[cantidad] = elegido;
            cantidad++;
            estado.quitar(elegido);
        }
        return cantidad;
    }

    /**
     * Relacion de Shaw entre dos tareas: cuanto menor es el valor, mas intercambiables son.
     * Los tres terminos estan normalizados a {@code [0,1]} para que los pesos del apartado
     * 7.4 signifiquen lo mismo en instancias de tamanos distintos.
     */
    private double relacionar(EstadoAlns estado, int referencia, int unidadReferencia, int otro) {
        int km = matriz.km(tareas.punto(referencia), tareas.punto(otro));
        double termino = km >= MatrizDistancias.INALCANZABLE
                ? pesoDistancia
                : pesoDistancia * (km / normalizadorDistancia);
        long separacion = Math.abs(tareas.limite(referencia) - tareas.limite(otro));
        termino += pesoPlazo * Math.min(1.0, separacion / normalizadorPlazo);
        if (estado.unidadDe(otro) != unidadReferencia) {
            termino += pesoUnidad;
        }
        return termino;
    }

    /**
     * Reune hasta {@code ventana} tareas aun colocadas, recorriendo los puntos vecinos de la
     * tarea de referencia en orden de distancia creciente. Si el vecindario cercano quedo
     * vacio, se recurre a un recorrido completo para no dejar de retirar nada.
     */
    private int reunirCandidatos(EstadoAlns estado, int referencia) {
        int[] vecinos = matriz.vecinosCercanos(tareas.punto(referencia));
        int limite = Math.min(vecinos.length, ventana * 4);
        int m = 0;
        for (int i = 0; i < limite && m < ventana; i++) {
            int pedido = pedidoDelPunto(vecinos[i]);
            if (pedido < 0) {
                continue;
            }
            int primera = tareas.primeraDePedido(pedido);
            int fin = primera + tareas.tareasDePedido(pedido);
            for (int t = primera; t < fin && m < ventana; t++) {
                if (!estado.enBanco(t)) {
                    candidatos[m++] = t;
                }
            }
        }
        if (m > 0) {
            return m;
        }
        for (int t = 0; t < cantidadTareas && m < ventana; t++) {
            if (!estado.enBanco(t)) {
                candidatos[m++] = t;
            }
        }
        return m;
    }

    /** Indice de pedido que corresponde a un punto de la matriz, o {@code -1} si no lo es. */
    private int pedidoDelPunto(int punto) {
        int pedido = punto - cantidadAlmacenes;
        return pedido >= 0 && pedido < cantidadPedidos ? pedido : -1;
    }

    private int tareaColocadaAlAzar(EstadoAlns estado, Aleatorio aleatorio) {
        int elegido = aleatorio.siguienteEntero(estado.asignados());
        int visto = 0;
        for (int u = 0; u < estado.cantidadUnidades(); u++) {
            int longitud = estado.longitudRuta(u);
            if (visto + longitud > elegido) {
                return estado.tareaEn(u, elegido - visto);
            }
            visto += longitud;
        }
        return -1;
    }
}
