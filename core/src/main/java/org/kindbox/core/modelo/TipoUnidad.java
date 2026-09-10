package org.kindbox.core.modelo;

/**
 * Tipos de unidad de transporte de la flota. El prefijo de dos letras es el que
 * usan los codigos TTNN de la respuesta 18 del cuestionario.
 *
 * <p>Las velocidades por defecto son las del enunciado: autos 40 Km/h, motos 25 Km/h
 * y bicicletas 12 Km/h. La velocidad efectiva es un parametro modificable en caliente
 * (respuestas 6 y 16 del cuestionario) y vive en {@link ParametrosOperacion}; los
 * valores de este enumerado son unicamente el punto de partida.</p>
 */
public enum TipoUnidad {

    AUTO("TA", "Auto", 24, 40.0, 8.00),
    MOTO("TM", "Moto", 8, 25.0, 6.00),
    BICICLETA("TB", "Bicicleta", 4, 12.0, 3.00);

    private final String prefijo;
    private final String etiqueta;
    private final int capacidad;
    private final double velocidadPorDefecto;
    private final double costoPorKm;

    TipoUnidad(String prefijo, String etiqueta, int capacidad, double velocidadPorDefecto, double costoPorKm) {
        this.prefijo = prefijo;
        this.etiqueta = etiqueta;
        this.capacidad = capacidad;
        this.velocidadPorDefecto = velocidadPorDefecto;
        this.costoPorKm = costoPorKm;
    }

    /** Prefijo TT del codigo de unidad. */
    public String prefijo() {
        return prefijo;
    }

    /** Nombre para presentacion. */
    public String etiqueta() {
        return etiqueta;
    }

    /** Capacidad en paquetes del producto P. */
    public int capacidad() {
        return capacidad;
    }

    /** Velocidad por defecto en Km/h, segun el enunciado. */
    public double velocidadPorDefecto() {
        return velocidadPorDefecto;
    }

    /** Costo total de operacion en soles por kilometro recorrido. */
    public double costoPorKm() {
        return costoPorKm;
    }

    /** Resuelve el tipo a partir del prefijo TT de un codigo de unidad. */
    public static TipoUnidad porPrefijo(String prefijo) {
        for (TipoUnidad t : values()) {
            if (t.prefijo.equalsIgnoreCase(prefijo)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Prefijo de tipo de unidad desconocido: " + prefijo);
    }

    /** Resuelve el tipo a partir de un codigo completo TTNN. */
    public static TipoUnidad porCodigo(String codigo) {
        if (codigo == null || codigo.length() < 2) {
            throw new IllegalArgumentException("Codigo de unidad invalido: " + codigo);
        }
        return porPrefijo(codigo.substring(0, 2));
    }
}
