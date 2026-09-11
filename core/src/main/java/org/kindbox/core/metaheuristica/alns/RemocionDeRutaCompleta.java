package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.util.Aleatorio;

/**
 * Remocion de ruta o turno completo. Es un operador de destruccion propio del proyecto,
 * previsto en el apartado 7.3.2 del ISA: vacia por completo la ruta de una o varias unidades,
 * elegidas al azar entre las que tienen ruta, y devuelve todos sus pedidos al banco.
 *
 * <p>Es el unico operador del conjunto que libera de golpe una unidad entera, lo que permite
 * que la reconstruccion decida no usarla y reparta su carga entre las demas: con la funcion de
 * costo del proyecto, que cobra por kilometro y por tipo, suprimir una ruta corta de un auto y
 * repartirla entre bicicletas cercanas puede ser una mejora que ningun movimiento local
 * alcanza.</p>
 *
 * <p>Es un operador de destruccion mas: compite con los otros cinco en la ruleta de la capa
 * adaptativa del apartado 7.3.3 y no tiene ningun otro punto de entrada. En particular no es
 * la via por la que se atiende una averia. Conforme al apartado 11.3 ninguno de los dos
 * algoritmos recibe aviso de averias: la unidad averiada, la que esta en mantenimiento
 * preventivo y la comprometida en un trasvase quedan fuera de la fotografia que construye el
 * motor de simulacion, y como el plan se rehace por completo en cada ejecucion (apartado 2.2)
 * los pedidos que no llego a servir vuelven a los pendientes y se recolocan por la misma via
 * que cualquier otro.</p>
 */
public final class RemocionDeRutaCompleta implements OperadorDestruccion {

    private final int[] candidatas;

    public RemocionDeRutaCompleta(int cantidadUnidades) {
        this.candidatas = new int[Math.max(1, cantidadUnidades)];
    }

    @Override
    public String nombre() {
        return "remocion-de-ruta-completa";
    }

    @Override
    public int destruir(EstadoAlns estado, int grado, Aleatorio aleatorio) {
        int m = 0;
        for (int u = 0; u < estado.cantidadUnidades(); u++) {
            if (estado.longitudRuta(u) > 0) {
                candidatas[m++] = u;
            }
        }
        int retirados = 0;
        // Se vacian rutas enteras hasta cubrir el grado de destruccion: el operador trabaja
        // a nivel de unidad, no de pedido, de modo que el grado solo decide cuantas rutas.
        while (retirados < grado && m > 0) {
            int indice = aleatorio.siguienteEntero(m);
            int unidad = candidatas[indice];
            candidatas[indice] = candidatas[--m];
            retirados += estado.vaciarRuta(unidad);
        }
        return retirados;
    }
}
