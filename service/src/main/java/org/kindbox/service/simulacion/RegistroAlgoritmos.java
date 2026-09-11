package org.kindbox.service.simulacion;

import java.util.List;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
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
 * <p>La resolucion de nombres y alias y la construccion viven en {@link FabricaAlgoritmos},
 * la misma que usan los ejecutables de experimentos, de modo que el servicio y el banco de
 * pruebas crean el mismo algoritmo para el mismo nombre. Los parametros se ajustan con las
 * propiedades de sistema {@code hgs.*} y {@code alns.*} con que se arranque el servicio, y
 * se validan al construir el registro: una clave mal escrita impide arrancar en lugar de
 * aparecer en la primera corrida.</p>
 *
 * <p>La semilla llega desde la configuracion de la corrida y no se genera aqui, de modo que
 * dos corridas con la misma semilla produzcan el mismo plan.</p>
 */
@Component
public class RegistroAlgoritmos {

    /**
     * @throws IllegalArgumentException si las propiedades de sistema traen una clave o un
     *                                  valor de parametro no valido
     */
    public RegistroAlgoritmos() {
        FabricaAlgoritmos.validar(System.getProperties());
    }

    /** Nombres admitidos, tal como los ofrece la pantalla de configuracion. */
    public List<String> nombres() {
        return FabricaAlgoritmos.nombres();
    }

    /**
     * Crea el algoritmo pedido.
     *
     * @param nombre  HGS o ALNS, o uno de sus alias, sin distinguir mayusculas
     * @param semilla semilla del generador de la corrida
     * @throws SolicitudInvalida si el nombre no corresponde a ninguno de los dos algoritmos
     */
    public Algoritmo crear(String nombre, long semilla) {
        try {
            return FabricaAlgoritmos.crear(nombre, semilla);
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
    }

    /** Nombre canonico del algoritmo, para guardarlo en la configuracion de la corrida. */
    public String canonico(String nombre) {
        try {
            return FabricaAlgoritmos.canonico(nombre);
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
    }
}
