package org.kindbox.core.evaluacion;

import java.util.Arrays;
import java.util.Map;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Funcion objetivo jerarquica H/U/S, con urgencia efectiva antes del costo.
 *
 * <p>El nivel 1, dominante, minimiza {@code H}, el numero de pedidos que quedan con
 * remanente sin asignar. A igual H se minimiza la urgencia de los no atendidos; despues se minimiza {@code S}, la suma sobre las
 * unidades de los kilometros recorridos por el costo por kilometro de su tipo. La
 * comparacion lexicografica vive en {@link ValorObjetivo}: una solucion con {@code H} mayor
 * que cero nunca se prefiere a una con {@code H} igual a cero, sea cual sea su costo.</p>
 *
 * <p>El termino de penalizacion recoge la desviacion respecto del plan vigente del apartado
 * 11.4, multiplicada por {@link #PESO_ESTABILIDAD}. Es un termino blando: no participa en la
 * comparacion de niveles, solo desempata dentro del nivel 2, de modo que la estabilidad del
 * plan nunca prevalece sobre el cumplimiento de un plazo.</p>
 *
 * <h2>Determinismo</h2>
 * <p>El apartado 12.4 del ISA exige que los dos algoritmos produzcan identico valor objetivo
 * ante la misma solucion, de modo que el resultado no puede depender del orden en que se
 * recorra ninguna coleccion. Dos precauciones lo garantizan. La suma en coma flotante de
 * {@code S} no acumula ruta a ruta, sino que acumula kilometros enteros por tipo de unidad y
 * multiplica al final en el orden fijo del enumerado, con lo que ni el orden de las rutas ni
 * su reparticion cambian el ultimo bit del resultado. Y {@code H} se recalcula a partir de
 * las entregas de las rutas y de las cantidades pendientes de la instancia, sin confiar en el
 * banco que cada algoritmo lleve por su cuenta.</p>
 *
 * <p>La clase no guarda estado, de modo que una sola instancia puede compartirse entre los
 * dos algoritmos y entre hilos.</p>
 */
public final class FuncionObjetivoJerarquica implements FuncionObjetivo {

    /** Tipos de unidad en orden fijo, para que la suma de {@code S} sea reproducible. */
    private static final TipoUnidad[] TIPOS = TipoUnidad.values();

    @Override
    public ValorObjetivo evaluar(InstanciaPlanificacion instancia, Solucion solucion) {
        final int cantidadPedidos = instancia.cantidadPedidos();
        int[] entregado = new int[cantidadPedidos];
        int[] kilometrosPorTipo = new int[TIPOS.length];

        for (Ruta ruta : solucion.rutas()) {
            int kilometros = 0;
            for (Parada parada : ruta.paradas()) {
                kilometros += parada.kmDesdeAnterior();
                if (parada.tipo() == TipoParada.ENTREGA) {
                    int indice = instancia.indiceDePedido(parada.idPedido());
                    // Un pedido ajeno a esta fotografia no aporta a H: ya fue atendido antes.
                    if (indice >= 0) {
                        entregado[indice] += parada.cantidad();
                    }
                }
            }
            kilometrosPorTipo[ruta.tipoUnidad().ordinal()] += kilometros;
        }

        int pedidosNoAtendidos = 0;
        double urgencia = 0.0;
        for (int i = 0; i < cantidadPedidos; i++) {
            if (entregado[i] < instancia.pedidoCantidad(i)) {
                pedidosNoAtendidos++;
                urgencia += ValorObjetivo.urgenciaDe(instancia.pedidoMinutoLimiteEfectivo(i) - instancia.minutoActual());
            }
        }

        double costo = 0.0;
        for (TipoUnidad tipo : TIPOS) {
            costo += kilometrosPorTipo[tipo.ordinal()] * tipo.costoPorKm();
        }

        double penalizacion = PESO_ESTABILIDAD * desviacionDelPlanVigente(instancia, solucion);
        return new ValorObjetivo(pedidosNoAtendidos, urgencia, costo, penalizacion);
    }

    @Override
    public double costoRuta(Ruta ruta) {
        return ruta.kilometros() * ruta.tipoUnidad().costoPorKm();
    }

    /**
     * Numero de asignaciones del plan vigente que el plan nuevo no respeta. Se cuenta sobre
     * las entradas del plan vigente y no sobre las del nuevo porque un pedido admite entregas
     * parciales repartidas entre varias unidades: preguntar si la unidad que lo tenia
     * asignada sigue atendiendolo es una pregunta bien definida, mientras que preguntar cual
     * es "la" unidad de un pedido repartido dependeria del orden de las rutas.
     *
     * <p>Las claves del plan nuevo se ordenan en un arreglo de enteros largos y se consultan
     * por busqueda binaria, de modo que no interviene ninguna tabla asociativa cuyo recorrido
     * pudiera alterar el resultado.</p>
     */
    @Override
    public int desviacionDelPlanVigente(InstanciaPlanificacion instancia, Solucion solucion) {
        Map<Integer, String> vigente = instancia.asignacionVigente();
        if (vigente.isEmpty()) {
            return 0;
        }

        int cantidadEntregas = 0;
        for (Ruta ruta : solucion.rutas()) {
            cantidadEntregas += ruta.cantidadEntregas();
        }
        long[] claves = new long[cantidadEntregas];
        int n = 0;
        for (Ruta ruta : solucion.rutas()) {
            int indiceUnidad = instancia.indiceDeUnidad(ruta.codigoUnidad());
            for (Parada parada : ruta.paradas()) {
                if (parada.tipo() == TipoParada.ENTREGA) {
                    claves[n++] = clave(parada.idPedido(), indiceUnidad);
                }
            }
        }
        Arrays.sort(claves, 0, n);

        int desviacion = 0;
        for (Map.Entry<Integer, String> asignacion : vigente.entrySet()) {
            int indiceUnidad = instancia.indiceDeUnidad(asignacion.getValue());
            // Una unidad que ya no esta disponible no puede honrar su asignacion previa.
            if (indiceUnidad < 0 || Arrays.binarySearch(claves, 0, n, clave(asignacion.getKey(), indiceUnidad)) < 0) {
                desviacion++;
            }
        }
        return desviacion;
    }

    /** Empaqueta el par pedido-unidad en un entero largo comparable. */
    private static long clave(int idPedido, int indiceUnidad) {
        return ((long) idPedido << 32) | (indiceUnidad & 0xFFFFFFFFL);
    }
}
