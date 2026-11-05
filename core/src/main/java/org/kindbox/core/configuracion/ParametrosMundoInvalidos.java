package org.kindbox.core.configuracion;

import java.util.List;

/** Un juego de {@link ParametrosMundo} incoherente, con todos los errores encontrados. */
public final class ParametrosMundoInvalidos extends IllegalArgumentException {

    private final List<String> errores;

    public ParametrosMundoInvalidos(List<String> errores) {
        super("Parametros del mundo invalidos: " + String.join("; ", errores));
        this.errores = List.copyOf(errores);
    }

    /** Cada error, con la ruta del campo al inicio, p. ej. {@code almacenes[1].capacidad: ...}. */
    public List<String> errores() {
        return errores;
    }
}
