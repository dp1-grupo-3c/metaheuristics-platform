package org.kindbox.core.metaheuristica.hgs;

import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Cuenta los pedidos que un individuo deja asignados con menos margen que el exigido, que es
 * el nivel entre la urgencia y el costo de {@link FuncionObjetivoJerarquica}. Sin el, HGS solo
 * veria ese nivel al materializar la mejor solucion y su busqueda interna seguiria prefiriendo
 * el plan mas barato aunque llegue al filo del plazo.
 *
 * <p>Programa cada ruta con el decodificador compartido, en el modo que no consume
 * inventario, y toma la ultima llegada de cada pedido. Un pedido con alguna tarea en el banco
 * no cuenta: ya lo recoge H.</p>
 */
final class ContadorMargen {

    private final InstanciaPlanificacion instancia;
    private final TareasEntrega tareas;
    private final ProgramadorRuta programador;
    private final long[] margenExigido;
    private final long[] ultimaLlegada;
    private final int[] tareasColocadas;
    private int[] pedidos = new int[16];
    private int[] cantidades = new int[16];
    private long[] llegadas = new long[16];

    ContadorMargen(InstanciaPlanificacion instancia, TareasEntrega tareas, ProgramadorRuta programador,
                   double fraccionMargenPlazo) {
        this.instancia = instancia;
        this.tareas = tareas;
        this.programador = programador;
        int n = Math.max(1, instancia.cantidadPedidos());
        this.margenExigido = new long[n];
        this.ultimaLlegada = new long[n];
        this.tareasColocadas = new int[n];
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            margenExigido[p] = FuncionObjetivoJerarquica.margenExigido(instancia, p, fraccionMargenPlazo);
        }
    }

    /**
     * Pedidos de la ultima secuencia que programo el decodificador que llegan con menos margen
     * que el exigido. Es la cuenta local de una ruta con la que la educacion valora sus
     * movimientos; la del individuo completo la hace {@link #contar}.
     */
    int sinMargenDeUltima(int[] pedidosSecuencia, int n) {
        if (llegadas.length < n) {
            llegadas = new long[n];
        }
        int conLlegada = programador.copiarLlegadasEntregas(llegadas);
        int sinMargen = 0;
        for (int i = 0; i < n; i++) {
            int p = pedidosSecuencia[i];
            if (i >= conLlegada || instancia.pedidoMinutoLimite(p) - llegadas[i] < margenExigido[p]) {
                sinMargen++;
            }
        }
        return sinMargen;
    }

    /** Deja en el individuo el numero de pedidos sin margen de su decodificacion actual. */
    void contar(Individuo individuo) {
        final int cantidadPedidos = instancia.cantidadPedidos();
        java.util.Arrays.fill(ultimaLlegada, 0, cantidadPedidos, Long.MIN_VALUE);
        java.util.Arrays.fill(tareasColocadas, 0, cantidadPedidos, 0);
        final int[] visitas = individuo.visitas();
        final int[] inicio = individuo.rutaInicio();
        final int[] longitud = individuo.rutaLongitud();
        final int[] unidad = individuo.rutaUnidad();
        for (int r = 0; r < individuo.cantidadRutas(); r++) {
            int n = longitud[r];
            if (pedidos.length < n) {
                pedidos = new int[n];
                cantidades = new int[n];
                llegadas = new long[n];
            }
            for (int i = 0; i < n; i++) {
                int tarea = visitas[inicio[r] + i];
                pedidos[i] = tareas.pedido(tarea);
                cantidades[i] = tareas.cantidadUnidades(tarea);
            }
            // Una ruta que el decodificador no sabe programar deja sus pedidos sin llegada:
            // cuentan como sin margen, que es lo prudente.
            int conLlegada = programador.evaluar(unidad[r], pedidos, cantidades, n)
                    ? programador.copiarLlegadasEntregas(llegadas) : 0;
            for (int i = 0; i < n; i++) {
                int p = pedidos[i];
                tareasColocadas[p]++;
                ultimaLlegada[p] = Math.max(ultimaLlegada[p], i < conLlegada ? llegadas[i] : Long.MAX_VALUE);
            }
        }
        int sinMargen = 0;
        for (int p = 0; p < cantidadPedidos; p++) {
            if (tareasColocadas[p] == tareas.tareasDePedido(p) && tareasColocadas[p] > 0
                    && (ultimaLlegada[p] == Long.MAX_VALUE
                        || instancia.pedidoMinutoLimite(p) - ultimaLlegada[p] < margenExigido[p])) {
                sinMargen++;
            }
        }
        individuo.pedidosSinMargen(sinMargen);
    }
}
