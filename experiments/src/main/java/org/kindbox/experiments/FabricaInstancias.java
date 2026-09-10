package org.kindbox.experiments;

import java.util.ArrayList;
import java.util.List;
import org.kindbox.core.grafo.MatrizDistanciasReticula;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;

/**
 * Construye fotografias de planificacion a partir de un escenario cargado del disco.
 *
 * <p>Es el puente entre los datos del equipo docente y las instancias estaticas sobre las
 * que se ejecuta la experimentacion numerica del apartado 12 del ISA. Aisla en un solo
 * lugar la decision de que se considera pendiente en un instante dado, de modo que todas
 * las corridas comparen exactamente el mismo recorte.</p>
 */
public final class FabricaInstancias {

    private final RepositorioDatos.DatosEscenario datos;
    private final RegistroBloqueos bloqueos;

    public FabricaInstancias(RepositorioDatos.DatosEscenario datos) {
        this.datos = datos;
        this.bloqueos = new RegistroBloqueos(datos.bloqueos());
    }

    /** Registro de bloqueos del escenario, que el verificador tambien necesita. */
    public RegistroBloqueos bloqueos() {
        return bloqueos;
    }

    /** Escenario del que proceden las fotografias. */
    public RepositorioDatos.DatosEscenario datos() {
        return datos;
    }

    /**
     * Pedidos ya registrados en el instante dado y todavia dentro de plazo o vencidos.
     * Es el conjunto de pendientes que veria el planificador si nada se hubiese entregado.
     */
    public List<Pedido> pendientesEn(long minuto) {
        List<Pedido> pendientes = new ArrayList<>();
        for (Pedido p : datos.pedidos()) {
            if (p.minutoRegistro() <= minuto) {
                pendientes.add(p);
            }
        }
        return pendientes;
    }

    /**
     * Fotografia de planificacion en el instante dado.
     *
     * @param minuto           instante de la fotografia
     * @param maximoPedidos    recorte del numero de pendientes, o un valor no positivo para todos
     * @param maximoUnidades   recorte de la flota, o un valor no positivo para toda
     * @param parametros       parametros de operacion vigentes
     */
    public InstanciaPlanificacion fotografia(long minuto, int maximoPedidos, int maximoUnidades,
                                             ParametrosOperacion parametros) {
        List<Pedido> pendientes = pendientesEn(minuto);
        if (maximoPedidos > 0 && pendientes.size() > maximoPedidos) {
            pendientes = pendientes.subList(pendientes.size() - maximoPedidos, pendientes.size());
        }
        List<UnidadTransporte> unidades = datos.unidades();
        if (maximoUnidades > 0 && unidades.size() > maximoUnidades) {
            unidades = unidades.subList(0, maximoUnidades);
        }
        List<Almacen> almacenes = Almacen.todos();

        int[] nodos = new int[almacenes.size() + pendientes.size() + unidades.size()];
        int k = 0;
        for (Almacen a : almacenes) {
            nodos[k++] = a.nodo();
        }
        for (Pedido p : pendientes) {
            nodos[k++] = p.nodoDestino();
        }
        for (UnidadTransporte u : unidades) {
            nodos[k++] = u.nodo();
        }
        var matriz = MatrizDistanciasReticula.construir(nodos, bloqueos.mascaraBloqueada(minuto));

        var constructor = InstanciaPlanificacion.constructor()
                .minutoActual(minuto)
                .parametros(parametros.instantanea())
                .matriz(matriz);
        for (Almacen a : almacenes) {
            constructor.almacen(a, a.central() ? Integer.MAX_VALUE : a.capacidad());
        }
        for (Pedido p : pendientes) {
            constructor.pedido(p, p.cantidad());
        }
        for (UnidadTransporte u : unidades) {
            u.minutoDisponibleDesde(minuto);
            constructor.unidad(u);
        }
        return constructor.construir();
    }
}
