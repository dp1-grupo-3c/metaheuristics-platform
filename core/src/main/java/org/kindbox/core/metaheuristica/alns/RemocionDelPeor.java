package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.util.Aleatorio;

/**
 * Remocion del peor del apartado 7.3.2 del ISA: retira los pedidos cuyo costo marginal de
 * permanencia es mayor.
 *
 * <p>El costo marginal de un pedido es lo que su ruta ahorraria si dejase de atenderlo, es
 * decir la diferencia entre el costo de la ruta y el de la misma ruta sin esa visita, medida
 * con el decodificador para que incluya los abastecimientos que la visita provoca. Retirar
 * los pedidos peor colocados y devolverlos al banco los expone a una reinsercion en mejor
 * sitio, que es el mecanismo con que el operador ataca el nivel 2 del objetivo.</p>
 *
 * <p>La eleccion no es estrictamente la de los {@code q} peores sino la seleccion sesgada de
 * Ropke y Pisinger (2006), que evita que el operador retire siempre el mismo grupo reducido de
 * pedidos y estanque la busqueda.</p>
 *
 * <p>El orden se calcula una sola vez por invocacion. Recalcularlo tras cada remocion seria
 * exacto pero costaria un factor {@code q} de tiempo, que con un presupuesto de segundos se
 * paga en iteraciones perdidas; la version de orden unico es la habitual en las
 * implementaciones que reporta el metaanalisis de Turkes, Sorensen y Hvattum (2021).</p>
 */
public final class RemocionDelPeor implements OperadorDestruccion {

    private final double determinismo;
    private final int[] colocados;
    /** Costo marginal de permanencia, indexado por tarea. */
    private final double[] marginal;
    /** Secuencia de trabajo con la ruta sin la visita valorada. */
    private int[] secuencia;
    private int[] cantidades;

    /**
     * @param cantidadTareas numero de tareas de entrega de la fotografia
     * @param determinismo   exponente de la seleccion sesgada de Ropke y Pisinger
     */
    public RemocionDelPeor(int cantidadTareas, double determinismo) {
        this.determinismo = determinismo;
        this.colocados = new int[Math.max(1, cantidadTareas)];
        this.marginal = new double[Math.max(1, cantidadTareas)];
        this.secuencia = new int[16];
        this.cantidades = new int[16];
    }

    @Override
    public String nombre() {
        return "remocion-del-peor";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        asegurarCapacidad(estado.capacidadRuta());
        ProgramadorRuta programador = estado.programador();
        int n = 0;
        for (int u = 0; u < estado.cantidadUnidades(); u++) {
            int longitud = estado.longitudRuta(u);
            if (longitud == 0) {
                continue;
            }
            int[] fila = estado.filaPedidos(u);
            int[] cargas = estado.filaCantidades(u);
            double costoRuta = estado.costoRuta(u);
            for (int i = 0; i < longitud; i++) {
                int tarea = estado.tareaEn(u, i);
                copiarSalvo(fila, cargas, longitud, i);
                double costoSin = 0.0;
                if (longitud > 1 && programador.evaluar(u, secuencia, cantidades, longitud - 1)) {
                    costoSin = programador.ultimoCosto();
                }
                marginal[tarea] = costoRuta - costoSin;
                colocados[n++] = tarea;
            }
        }
        if (n == 0) {
            return 0;
        }
        OrdenPorClave.descendente(marginal, colocados, n);

        int objetivo = Math.min(grado, n);
        int retirados = 0;
        int disponibles = n;
        while (retirados < objetivo && disponibles > 0) {
            int indice = OrdenPorClave.indiceSesgado(disponibles, determinismo, aleatorio);
            int tarea = colocados[indice];
            colocados[indice] = colocados[--disponibles];
            estado.quitar(tarea);
            retirados++;
        }
        return retirados;
    }

    /** Copia la ruta en la secuencia de trabajo omitiendo la visita indicada. */
    private void copiarSalvo(int[] fila, int[] cargas, int longitud, int omitida) {
        int j = 0;
        for (int i = 0; i < longitud; i++) {
            if (i == omitida) {
                continue;
            }
            secuencia[j] = fila[i];
            cantidades[j] = cargas[i];
            j++;
        }
    }

    private void asegurarCapacidad(int necesaria) {
        if (secuencia.length >= necesaria) {
            return;
        }
        secuencia = new int[necesaria];
        cantidades = new int[necesaria];
    }
}
