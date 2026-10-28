package org.kindbox.core.operacion;

import java.io.IOException;
import java.time.LocalDate;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.RepositorioDatos;

@FunctionalInterface
public interface FuenteEntornoOperacion {
    RepositorioDatos.DatosEscenario cargar(LocalDate desde, LocalDate hasta,
                                           CalendarioEscenario calendario) throws IOException;
}
