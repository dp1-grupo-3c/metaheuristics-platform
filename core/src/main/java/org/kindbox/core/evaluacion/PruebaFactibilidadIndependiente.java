package org.kindbox.core.evaluacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Comprueba factibilidad sin ejecutar ALNS, HGS ni la heuristica constructiva.
 *
 * <p>La prueba permite dividir un pedido en varios viajes. Ordena por vencimiento y
 * simula viajes individuales desde el almacen central, con retorno para recargar. Usa
 * distancias Manhattan sin bloqueos, mantenimiento ni turnos; un positivo es por tanto
 * un filtro optimista, no una garantia de la simulacion completa.</p>
 */
public final class PruebaFactibilidadIndependiente {

    private static final int MINUTOS_SERVICIO = ParametrosOperacion.MINUTOS_ACONDICIONAMIENTO_POR_DEFECTO;

    private PruebaFactibilidadIndependiente() {
    }

    public static Resultado evaluar(RepositorioDatos.DatosEscenario datos,
                                    ParametrosOperacion parametros) {
        if (datos == null || parametros == null) {
            throw new IllegalArgumentException("Los datos y los parametros son obligatorios");
        }
        datos.flota().aplicarVelocidades(parametros);
        return evaluarPedidos(datos.pedidos(), datos.unidades(), parametros);
    }

    /** Evalua una lista de pedidos sin cargar archivos ni invocar un algoritmo. */
    public static Resultado evaluarPedidos(List<Pedido> pedidos, List<UnidadTransporte> unidades,
                                           ParametrosOperacion parametros) {
        if (pedidos == null || unidades == null || parametros == null || unidades.isEmpty()) {
            throw new IllegalArgumentException("Se necesitan pedidos, unidades y parametros");
        }
        int central = Almacen.crearCentral().nodo();
        List<Pedido> ordenados = new ArrayList<>(pedidos);
        ordenados.sort(Comparator.comparingLong(Pedido::minutoLimite)
                .thenComparingLong(Pedido::minutoRegistro)
                .thenComparingInt(Pedido::id));
        List<EstadoUnidad> estados = new ArrayList<>();
        for (UnidadTransporte unidad : unidades) {
            estados.add(new EstadoUnidad(unidad.tipo(), unidad.capacidad(), central));
        }

        List<Diagnostico> diagnosticos = new ArrayList<>();
        int individualesImposibles = 0;
        int noProgramables = 0;
        for (Pedido pedido : ordenados) {
            int capacidadOptimista = capacidadOptimista(pedido, estados, central, parametros);
            if (capacidadOptimista < pedido.cantidad()) {
                individualesImposibles++;
                diagnosticos.add(new Diagnostico(pedido.id(), "IMPOSIBLE_INDIVIDUAL",
                        "la capacidad de viajes optimistas antes del plazo es "
                                + capacidadOptimista + " y se requieren " + pedido.cantidad()));
                continue;
            }

            int restante = pedido.cantidad();
            while (restante > 0) {
                EstadoUnidad elegida = null;
                long mejorLlegada = Long.MAX_VALUE;
                for (EstadoUnidad unidad : estados) {
                    long inicio = Math.max(pedido.minutoRegistro(), unidad.disponibleDesde);
                    long llegada = inicio + minutosDeViaje(unidad.tipo, unidad.nodo,
                            pedido.nodoDestino(), parametros);
                    if (llegada <= pedido.minutoLimite() && llegada < mejorLlegada) {
                        mejorLlegada = llegada;
                        elegida = unidad;
                    }
                }
                if (elegida == null) {
                    noProgramables++;
                    diagnosticos.add(new Diagnostico(pedido.id(), "NO_PROGRAMABLE",
                            "la politica independiente no encuentra viajes restantes a tiempo; "
                                    + "quedan " + restante + " paquetes"));
                    break;
                }
                restante -= Math.min(restante, elegida.capacidad);
                int vuelta = minutosDeViaje(elegida.tipo, pedido.nodoDestino(), central, parametros);
                elegida.nodo = central;
                elegida.disponibleDesde = mejorLlegada + MINUTOS_SERVICIO + vuelta;
            }
        }
        return new Resultado(individualesImposibles == 0 && noProgramables == 0,
                pedidos.size(), individualesImposibles, noProgramables, diagnosticos);
    }

    private static int capacidadOptimista(Pedido pedido, List<EstadoUnidad> unidades, int central,
                                          ParametrosOperacion parametros) {
        int total = 0;
        for (EstadoUnidad unidad : unidades) {
            int ida = minutosDeViaje(unidad.tipo, central, pedido.nodoDestino(), parametros);
            int vuelta = minutosDeViaje(unidad.tipo, pedido.nodoDestino(), central, parametros);
            int duracion = ida + MINUTOS_SERVICIO + vuelta;
            long ventana = pedido.minutoLimite() - pedido.minutoRegistro();
            if (duracion > 0 && ventana >= ida) {
                long viajes = 1L + Math.max(0L, ventana - duracion) / duracion;
                total = Math.addExact(total, Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        viajes * unidad.capacidad)));
            }
        }
        return total;
    }

    private static int minutosDeViaje(TipoUnidad tipo, int origen, int destino,
                                      ParametrosOperacion parametros) {
        return parametros.instantanea().minutosDeViaje(tipo,
                Ciudad.distanciaManhattan(origen, destino));
    }

    public record Resultado(boolean pasa, int pedidos, int individualesImposibles,
                            int noProgramables, List<Diagnostico> diagnosticos) {
        public Resultado {
            diagnosticos = List.copyOf(diagnosticos);
        }
    }

    public record Diagnostico(int pedido, String codigo, String detalle) {
    }

    private static final class EstadoUnidad {
        private final TipoUnidad tipo;
        private final int capacidad;
        private int nodo;
        private long disponibleDesde;

        private EstadoUnidad(TipoUnidad tipo, int capacidad, int nodo) {
            this.tipo = tipo;
            this.capacidad = capacidad;
            this.nodo = nodo;
        }
    }
}
