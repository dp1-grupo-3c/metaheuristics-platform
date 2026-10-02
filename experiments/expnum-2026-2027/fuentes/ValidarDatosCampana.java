package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import org.kindbox.core.io.RepositorioDatos;

/** Valida todas las fechas y ventanas con el mismo lector que usa la simulacion. */
public final class ValidarDatosCampana {
    private ValidarDatosCampana() { }

    public static void main(String[] args) throws Exception {
        var repositorio = new RepositorioDatos(Path.of(args[0]));
        var inicio = LocalDate.of(2026, 1, 1);
        long diarios = 0;
        long ventanas = 0;
        System.out.println("fecha,dias,pedidos,bloqueos,mantenimientos");
        for (int dias : new int[]{1, 5}) {
            for (int desplazamiento = 0; desplazamiento < 730; desplazamiento += dias) {
                var fecha = inicio.plusDays(desplazamiento);
                var datos = repositorio.cargar(fecha, fecha.plusDays(dias - 1));
                if (datos.avisos().stream().anyMatch(a -> a.startsWith("No se encontro"))) {
                    throw new IllegalStateException("Datos ausentes: " + datos.avisos());
                }
                if (datos.unidades().size() != 37) throw new IllegalStateException("Flota alterada");
                if (dias == 1) diarios += datos.pedidos().size();
                else ventanas += datos.pedidos().size();
                System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d%n", fecha, dias,
                        datos.pedidos().size(), datos.bloqueos().size(), datos.mantenimientos().size());
            }
        }
        if (diarios != 100010 || ventanas != diarios) {
            throw new IllegalStateException("Cobertura distinta de las 100010 ventas: " + diarios + "/" + ventanas);
        }
        // Rango entre anos: el lector debe encadenar ambos archivos sin reutilizar IDs.
        var cruce = repositorio.cargar(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 3));
        if (cruce.pedidos().stream().map(p -> p.id()).distinct().count() != cruce.pedidos().size()) {
            throw new IllegalStateException("Identificadores repetidos entre anos");
        }
        System.err.println("Cobertura validada: 730 dias, 146 ventanas y 100010 ventas.");
    }
}
