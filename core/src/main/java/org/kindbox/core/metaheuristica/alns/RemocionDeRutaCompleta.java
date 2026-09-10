package org.kindbox.core.metaheuristica.alns;

import org.kindbox.core.util.Aleatorio;

/**
 * Remocion de ruta o turno completo. Es un operador propio del proyecto, previsto en el
 * apartado 7.3.2 del ISA: vacia por completo la ruta de una unidad y devuelve todos sus
 * pedidos al banco.
 *
 * <p>Tiene dos razones de ser. Como operador de busqueda es el unico que libera de golpe una
 * unidad entera, lo que permite que la reconstruccion decida no usarla y reparta su carga
 * entre las demas: con la funcion de costo del proyecto, que cobra por kilometro y por tipo,
 * suprimir una ruta corta de un auto y repartirla entre bicicletas cercanas puede ser una
 * mejora que ningun movimiento local alcanza.</p>
 *
 * <p>Y como operador de operacion es el que se activa ante una averia, segun el apartado 11.3
 * del ISA: cuando una unidad deja de estar disponible, el replanificador vacia su ruta con
 * {@link #vaciarRutaDe} y deja que el resto del algoritmo recoloque sus pedidos. Que el
 * mecanismo de la averia sea un operador mas del conjunto, y no un camino aparte, es lo que
 * garantiza que la respuesta a la averia produzca un plan factible por la misma via que
 * cualquier otra iteracion.</p>
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

    /**
     * Vacia la ruta de una unidad concreta. Es la entrada que usa el replanificador cuando
     * el motor de simulacion notifica una averia (apartado 11.3 del ISA).
     *
     * @return numero de pedidos devueltos al banco
     */
    public int vaciarRutaDe(EstadoAlns estado, int unidad) {
        return estado.vaciarRuta(unidad);
    }
}
