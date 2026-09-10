package org.kindbox.core.metaheuristica.alns;

import java.util.Arrays;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Remocion de cadenas adyacentes de Christiaens y Vanden Berghe (2020), recogida en el
 * apartado 7.3.2 del ISA: retira cadenas de paradas contiguas en rutas geograficamente
 * proximas entre si.
 *
 * <p>El operador elige un pedido semilla y recorre sus vecinos mas cercanos. Cada vez que
 * encuentra un vecino atendido por una unidad que aun no ha tocado, retira de esa ruta una
 * cadena de paradas consecutivas que contiene al vecino. El resultado es que varias rutas
 * proximas quedan con un hueco a la vez.</p>
 *
 * <p>Esa forma de destruir induce dos holguras de manera simultanea. Retirar paradas
 * <em>contiguas</em> de una misma ruta libera capacidad y minutos concentrados en un tramo, en
 * lugar de repartir la holgura por toda la jornada; y hacerlo en rutas <em>proximas</em>
 * libera espacio geografico en una misma zona de la ciudad. La reinsercion puede entonces
 * reasignar la zona entera entre esas unidades, que es el movimiento que ningun operador de
 * remocion disperso alcanza a proponer.</p>
 */
public final class RemocionCadenasAdyacentes implements OperadorDestruccion {

    private final InstanciaPlanificacion instancia;
    private final TareasAlns tareas;
    private final MatrizDistancias matriz;
    private final int cantidadAlmacenes;
    private final int cantidadPedidos;
    private final int longitudMaximaCadena;
    private final int ventana;
    private final boolean[] rutaTocada;

    public RemocionCadenasAdyacentes(InstanciaPlanificacion instancia, ParametrosAlns parametros) {
        this(instancia, new TareasAlns(instancia), parametros);
    }

    /**
     * @param instancia  fotografia del problema
     * @param tareas     descomposicion de los pedidos en visitas, compartida con el estado
     * @param parametros parametros calibrables del apartado 7.4
     */
    public RemocionCadenasAdyacentes(InstanciaPlanificacion instancia, TareasAlns tareas,
                                     ParametrosAlns parametros) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.matriz = instancia.matriz();
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();
        this.cantidadPedidos = instancia.cantidadPedidos();
        this.longitudMaximaCadena = parametros.longitudMaximaCadena();
        this.ventana = parametros.ventanaVecindad();
        this.rutaTocada = new boolean[instancia.cantidadUnidades()];
    }

    @Override
    public String nombre() {
        return "remocion-cadenas-adyacentes";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        int colocados = estado.asignados();
        if (colocados == 0) {
            return 0;
        }
        Arrays.fill(rutaTocada, false);
        int objetivo = Math.min(grado, colocados);
        int semilla = tareaColocadaAlAzar(estado, aleatorio);
        if (semilla < 0) {
            return 0;
        }
        int retirados = retirarCadena(estado, semilla, objetivo, 0, aleatorio);

        int[] vecinos = matriz.vecinosCercanos(tareas.punto(semilla));
        int limite = Math.min(vecinos.length, ventana * 4);
        for (int i = 0; i < limite && retirados < objetivo; i++) {
            int pedido = pedidoDelPunto(vecinos[i]);
            if (pedido < 0) {
                continue;
            }
            int tarea = estado.tareaColocadaDe(pedido);
            if (tarea < 0 || rutaTocada[estado.unidadDe(tarea)]) {
                continue;
            }
            retirados = retirarCadena(estado, tarea, objetivo, retirados, aleatorio);
        }
        return retirados;
    }

    /**
     * Retira de la ruta de la tarea dada una cadena de paradas consecutivas que la contiene.
     * La longitud se sortea entre uno y el maximo admisible, acotado por lo que resta del
     * grado de destruccion y por la propia longitud de la ruta.
     */
    private int retirarCadena(EstadoAlns estado, int tarea, int objetivo, int retirados,
                              Aleatorio aleatorio) {
        int unidad = estado.unidadDe(tarea);
        rutaTocada[unidad] = true;
        int longitudRuta = estado.longitudRuta(unidad);
        int maxima = Math.min(longitudMaximaCadena, Math.min(longitudRuta, objetivo - retirados));
        if (maxima <= 0) {
            return retirados;
        }
        int cadena = 1 + aleatorio.siguienteEntero(maxima);
        int posicion = estado.posicionDe(tarea);
        // La tarea puede quedar en cualquier lugar de la cadena, de modo que la ventana se
        // desplaza al azar y luego se ajusta para no salirse de la ruta.
        int inicio = posicion - aleatorio.siguienteEntero(cadena);
        if (inicio < 0) {
            inicio = 0;
        }
        if (inicio + cadena > longitudRuta) {
            inicio = longitudRuta - cadena;
        }
        for (int i = 0; i < cadena; i++) {
            estado.quitarEn(unidad, inicio);
        }
        return retirados + cadena;
    }

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
