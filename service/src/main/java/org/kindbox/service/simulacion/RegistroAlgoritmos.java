package org.kindbox.service.simulacion;

import java.util.List;
import java.util.Locale;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.service.error.SolicitudInvalida;
import org.springframework.stereotype.Component;

/**
 * Resuelve el nombre de algoritmo que llega en la peticion a una instancia del planificador.
 *
 * <p>Los dos son los seleccionados en el ISA para el requisito no funcional (b): la busqueda
 * genetica hibrida del apartado 6, de tipo poblacional, y la busqueda adaptativa de vecindad
 * amplia del apartado 7, de trayectoria. Ambas comparten la heuristica constructiva de
 * ahorros de Clarke y Wright, conforme al apartado 10, que es la condicion de validez del
 * experimento del apartado 12.</p>
 *
 * <p>La semilla llega desde la configuracion de la corrida y no se genera aqui, de modo que
 * dos corridas con la misma semilla produzcan el mismo plan.</p>
 */
@Component
public class RegistroAlgoritmos {

    /** Nombres admitidos, tal como los ofrece la pantalla de configuracion. */
    public List<String> nombres() {
        return List.of(BusquedaGeneticaHibrida.NOMBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE);
    }

    /**
     * Crea el algoritmo pedido.
     *
     * @param nombre  HGS o ALNS, sin distinguir mayusculas
     * @param semilla semilla del generador de la corrida
     * @throws SolicitudInvalida si el nombre no corresponde a ninguno de los dos algoritmos
     */
    public Algoritmo crear(String nombre, long semilla) {
        String clave = nombre == null ? "" : nombre.trim().toUpperCase(Locale.ROOT);
        return switch (clave) {
            case "HGS", "GENETICA", "BUSQUEDA_GENETICA_HIBRIDA" ->
                    new BusquedaGeneticaHibrida(new AhorrosClarkeWright(), ParametrosHgs.porDefecto(), semilla);
            case "ALNS", "VECINDAD", "BUSQUEDA_ADAPTATIVA_VECINDAD_AMPLIA" ->
                    new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), semilla);
            default -> throw new SolicitudInvalida("Algoritmo desconocido: " + nombre
                    + ". Los disponibles son " + String.join(" y ", nombres()));
        };
    }

    /** Nombre canonico del algoritmo, para guardarlo en la configuracion de la corrida. */
    public String canonico(String nombre) {
        return crear(nombre, 0L).nombre();
    }
}
