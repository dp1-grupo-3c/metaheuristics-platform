package org.kindbox.core.io;

import java.io.IOException;
import java.time.YearMonth;

/**
 * Origen de los meses de un escenario abierto, como el de colapso, que el motor pide a
 * medida que avanza su reloj en lugar de cargarlos todos al arrancar.
 */
@FunctionalInterface
public interface FuenteTramos {

    /**
     * Carga un mes del escenario. Un mes sin archivo de ventas no es un error: vuelve con
     * {@link RepositorioDatos.TramoMensual#conVentas()} en falso y marca el final de los datos.
     */
    RepositorioDatos.TramoMensual cargar(YearMonth mes) throws IOException;
}
