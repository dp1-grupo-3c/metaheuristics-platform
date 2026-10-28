package org.kindbox.core.operacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.kindbox.core.io.CalendarioEscenario;

public final class RelojOperacion {
    private final Clock reloj;
    private final ZoneId zona;
    private final CalendarioEscenario calendario;
    private Instant ultimo;

    public RelojOperacion(Clock reloj, ZoneId zona) {
        this.reloj = java.util.Objects.requireNonNull(reloj);
        this.zona = java.util.Objects.requireNonNull(zona);
        ultimo = reloj.instant();
        calendario = CalendarioEscenario.desde(LocalDateTime.ofInstant(ultimo, zona).toLocalDate());
    }

    public synchronized Instant instante() {
        Instant actual = reloj.instant();
        if (actual.isAfter(ultimo)) ultimo = actual;
        return ultimo;
    }

    public LocalDateTime fechaHora() {
        return LocalDateTime.ofInstant(instante(), zona);
    }

    public long minuto() {
        return calendario.aMinutos(fechaHora());
    }

    public CalendarioEscenario calendario() {
        return calendario;
    }
}
