package org.kindbox.core.evaluacion;

import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;

/**
 * Comprobacion de las restricciones duras del apartado 2.6 del ISA.
 *
 * <p>Se emplea en dos momentos: dentro de cada algoritmo, para aceptar o rechazar
 * soluciones, y al final de cada corrida, como primera de las cuatro verificaciones de
 * validez del apartado 12.4. Es un componente comun a los dos algoritmos, de modo que la
 * experimentacion compare mecanismos de busqueda y no verificadores distintos.</p>
 */
public interface VerificadorFactibilidad {

    /** Verifica un plan completo contra la instancia que lo origino. */
    ResultadoVerificacion verificar(InstanciaPlanificacion instancia, Solucion solucion);

    /** Verifica una ruta aislada. Util en las pruebas y en la depuracion de operadores. */
    ResultadoVerificacion verificarRuta(InstanciaPlanificacion instancia, Ruta ruta);
}
