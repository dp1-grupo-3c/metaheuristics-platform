package org.kindbox.core.io;

import java.io.IOException;
import java.time.LocalDate;

// Pedidos de un horizonte fijo de a un dia, para no tener el rango entero en memoria.
public interface FuenteDias {

    // Un dia sin archivo de ventas vuelve vacio y con el aviso anotado.
    RepositorioDatos.TramoDiario cargar(LocalDate dia) throws IOException;
}
