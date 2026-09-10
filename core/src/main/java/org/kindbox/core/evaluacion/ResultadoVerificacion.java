package org.kindbox.core.evaluacion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Salida del verificador de factibilidad. Recoge todas las infracciones halladas, no solo
 * la primera, porque la verificacion de validez del apartado 12.4 del ISA necesita el
 * detalle completo para diagnosticar una corrida invalida.
 */
public final class ResultadoVerificacion {

    /** Resultado sin infracciones. */
    public static final ResultadoVerificacion FACTIBLE =
            new ResultadoVerificacion(Collections.emptyList());

    private final List<Detalle> detalles;
    private final Set<Infraccion> tipos;

    private ResultadoVerificacion(List<Detalle> detalles) {
        this.detalles = List.copyOf(detalles);
        this.tipos = detalles.isEmpty() ? EnumSet.noneOf(Infraccion.class) : EnumSet.noneOf(Infraccion.class);
        for (Detalle d : detalles) {
            this.tipos.add(d.infraccion());
        }
    }

    /**
     * Una infraccion concreta.
     *
     * @param infraccion   restriccion violada
     * @param codigoUnidad unidad implicada, o {@code null} si la infraccion es del plan
     * @param indiceParada posicion de la parada implicada, o {@code -1}
     * @param mensaje      descripcion legible con los valores concretos
     */
    public record Detalle(Infraccion infraccion, String codigoUnidad, int indiceParada, String mensaje) {
    }

    /** Indica si la solucion satisface las siete restricciones duras. */
    public boolean factible() {
        return detalles.isEmpty();
    }

    /** Infracciones halladas, en el orden en que se detectaron. */
    public List<Detalle> detalles() {
        return detalles;
    }

    /** Tipos de infraccion presentes. */
    public Set<Infraccion> tipos() {
        return Collections.unmodifiableSet(tipos);
    }

    /** Constructor incremental. */
    public static Acumulador acumulador() {
        return new Acumulador();
    }

    /** Acumula infracciones durante el recorrido de una solucion. */
    public static final class Acumulador {
        private final List<Detalle> detalles = new ArrayList<>();

        public Acumulador agregar(Infraccion infraccion, String codigoUnidad, int indiceParada, String mensaje) {
            detalles.add(new Detalle(infraccion, codigoUnidad, indiceParada, mensaje));
            return this;
        }

        public boolean vacio() {
            return detalles.isEmpty();
        }

        public ResultadoVerificacion construir() {
            return detalles.isEmpty() ? FACTIBLE : new ResultadoVerificacion(detalles);
        }
    }

    @Override
    public String toString() {
        if (factible()) {
            return "factible";
        }
        StringBuilder sb = new StringBuilder(detalles.size() + " infraccion(es):");
        for (Detalle d : detalles) {
            sb.append("\n  - ").append(d.infraccion()).append(' ').append(d.mensaje());
        }
        return sb.toString();
    }
}
