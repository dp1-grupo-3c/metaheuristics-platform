package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Remocion por criticidad. Es un operador propio del proyecto, previsto en el apartado 7.3.2
 * del ISA, y el unico del conjunto dirigido de forma explicita al nivel 1 de la funcion
 * objetivo del apartado 2.5.
 *
 * <p>Los demas operadores de destruccion buscan abaratar el plan; este busca vaciar el banco.
 * Trabaja en dos fases. Primero toma los pedidos del banco de menor holgura, que son los que
 * estan a punto de volverse imposibles, y despeja su entorno: retira de las rutas los pedidos
 * cercanos que atienden unidades capaces de llegar a tiempo hasta el pedido critico. Esos
 * huecos son exactamente los que el operador de insercion necesita para colocarlo. Si el
 * banco esta vacio, la segunda fase retira los pedidos <em>colocados</em> de menor holgura
 * junto con su entorno, que son los que encadenan el resto de la ruta y suelen impedir que
 * entren otros.</p>
 *
 * <p>La capacidad de una unidad para atender un pedido se decide con la llegada directa desde
 * su posicion inicial, que es una cota inferior valida del instante de entrega por la
 * desigualdad triangular de la matriz de caminos minimos. Descartar asi una unidad no cuesta
 * ninguna programacion de ruta.</p>
 */
public final class RemocionPorCriticidad implements OperadorDestruccion {

    private final InstanciaPlanificacion instancia;
    private final TareasAlns tareas;
    private final MatrizDistancias matriz;
    private final int cantidadAlmacenes;
    private final int cantidadPedidos;
    private final int cantidadTareas;
    private final int ventana;
    private final int[] semillas;
    /** Holgura de cada tarea respecto del instante de la fotografia, indexada por tarea. */
    private final double[] holgura;

    public RemocionPorCriticidad(InstanciaPlanificacion instancia, ParametrosAlns parametros) {
        this(instancia, new TareasAlns(instancia), parametros);
    }

    /**
     * @param instancia  fotografia del problema
     * @param tareas     descomposicion de los pedidos en visitas, compartida con el estado
     * @param parametros parametros calibrables del apartado 7.4
     */
    public RemocionPorCriticidad(InstanciaPlanificacion instancia, TareasAlns tareas,
                                 ParametrosAlns parametros) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.matriz = instancia.matriz();
        this.cantidadAlmacenes = instancia.cantidadAlmacenes();
        this.cantidadPedidos = instancia.cantidadPedidos();
        this.cantidadTareas = tareas.cantidad();
        this.ventana = parametros.ventanaVecindad();
        this.semillas = new int[Math.max(1, cantidadTareas)];
        this.holgura = new double[Math.max(1, cantidadTareas)];
    }

    @Override
    public String nombre() {
        return "remocion-por-criticidad";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        int colocados = estado.asignados();
        if (colocados == 0) {
            return 0;
        }
        int objetivo = Math.min(grado, colocados);
        int retirados = 0;

        int enBanco = estado.tamanoBanco();
        if (enBanco > 0) {
            for (int i = 0; i < enBanco; i++) {
                int tarea = estado.bancoEn(i);
                semillas[i] = tarea;
                holgura[tarea] = tareas.holgura(tarea);
            }
            OrdenPorClave.ascendente(holgura, semillas, enBanco);
            int cupoPorSemilla = Math.max(1, objetivo / Math.min(enBanco, Math.max(1, objetivo)));
            for (int i = 0; i < enBanco && retirados < objetivo; i++) {
                retirados += despejarEntorno(estado, semillas[i],
                        Math.min(cupoPorSemilla, objetivo - retirados));
            }
        }

        if (retirados < objetivo) {
            int n = 0;
            for (int u = 0; u < estado.cantidadUnidades(); u++) {
                int longitud = estado.longitudRuta(u);
                for (int i = 0; i < longitud; i++) {
                    int tarea = estado.tareaEn(u, i);
                    semillas[n++] = tarea;
                    holgura[tarea] = tareas.holgura(tarea);
                }
            }
            OrdenPorClave.ascendente(holgura, semillas, n);
            for (int i = 0; i < n && retirados < objetivo; i++) {
                int tarea = semillas[i];
                if (estado.enBanco(tarea)) {
                    continue;
                }
                estado.quitar(tarea);
                retirados++;
                retirados += despejarEntorno(estado, tarea, objetivo - retirados);
            }
        }
        return retirados;
    }

    /**
     * Retira de las rutas las tareas cercanas a la semilla que atienden unidades capaces de
     * llegar hasta ella dentro de plazo. Es lo que abre sitio para la entrega critica.
     *
     * @return numero de tareas retiradas
     */
    private int despejarEntorno(EstadoAlns estado, int semilla, int cupo) {
        if (cupo <= 0) {
            return 0;
        }
        int[] vecinos = matriz.vecinosCercanos(tareas.punto(semilla));
        int limite = Math.min(vecinos.length, ventana * 4);
        int retirados = 0;
        for (int i = 0; i < limite && retirados < cupo; i++) {
            int vecino = pedidoDelPunto(vecinos[i]);
            if (vecino < 0) {
                continue;
            }
            int tareaVecina = estado.tareaColocadaDe(vecino);
            if (tareaVecina < 0) {
                continue;
            }
            int unidad = estado.unidadDe(tareaVecina);
            if (!MotorInsercion.unidadPuedeAtender(instancia, tareas, unidad, semilla)) {
                continue;
            }
            estado.quitar(tareaVecina);
            retirados++;
        }
        return retirados;
    }

    private int pedidoDelPunto(int punto) {
        int pedido = punto - cantidadAlmacenes;
        return pedido >= 0 && pedido < cantidadPedidos ? pedido : -1;
    }
}
