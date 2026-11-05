package org.kindbox.service.operacion;

import org.kindbox.service.error.ConflictoDeEstado;
import org.springframework.stereotype.Component;

@Component
public class CoordinadorEjecucion {
    private Reserva activa;

    public synchronized Reserva reservar(String tipo) {
        if (activa != null) throw new ConflictoDeEstado("Ya hay una ejecucion activa de " + activa.tipo);
        activa = new Reserva(tipo);
        return activa;
    }

    // Tipo de la ejecucion que tiene la reserva, o null si no hay ninguna.
    public synchronized String ejecucionActiva() {
        return activa == null ? null : activa.tipo;
    }

    public final class Reserva implements AutoCloseable {
        private final String tipo;

        private Reserva(String tipo) { this.tipo = tipo; }

        @Override
        public void close() {
            synchronized (CoordinadorEjecucion.this) {
                if (activa == this) activa = null;
            }
        }
    }
}
