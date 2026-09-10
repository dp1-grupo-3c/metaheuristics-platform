package org.kindbox.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Fachada de carga de un escenario completo desde un directorio de datos.
 *
 * <p>Recibe la raiz de los datos y el rango de dias que cubre el escenario, y localiza por
 * si misma los archivos mensuales de ventas y de bloqueos que caen dentro del rango, mas el
 * plan de mantenimiento preventivo y la composicion de la flota. La estructura esperada
 * es:</p>
 *
 * <pre>
 * &lt;raiz&gt;/ventas/ventasAAAAMM
 * &lt;raiz&gt;/bloqueos/AAAAMM.bloqueadas
 * &lt;raiz&gt;/mantenimiento/[aaaa/]mant.preventivo.m1.m2
 * &lt;raiz&gt;/flota.txt
 * </pre>
 *
 * <p>Un escenario puede cubrir meses sin datos publicados, de modo que la falta de un
 * archivo mensual no es un error: el mes se omite y queda constancia en la lista de avisos
 * del resultado. Un archivo presente pero mal formado si detiene la carga, porque perder
 * pedidos en silencio falsearia el nivel 1 de la funcion objetivo del apartado 2.5 del ISA.</p>
 *
 * <p>El reloj interno del escenario arranca a las 00:00 del primer dia del rango. Los
 * registros de un archivo mensual que caigan fuera del rango se descartan, tambien con
 * aviso, porque un pedido con instante de registro negativo no tiene sentido para el
 * planificador.</p>
 */
public final class RepositorioDatos {

    /** Subdirectorio de los archivos mensuales de ventas. */
    public static final String DIRECTORIO_VENTAS = "ventas";
    /** Subdirectorio de los archivos mensuales de bloqueos. */
    public static final String DIRECTORIO_BLOQUEOS = "bloqueos";
    /** Subdirectorio de los planes de mantenimiento preventivo. */
    public static final String DIRECTORIO_MANTENIMIENTO = "mantenimiento";
    /** Nombre alternativo del archivo de flota, tal como llega de la hoja del curso. */
    public static final String ARCHIVO_FLOTA_ALTERNATIVO = "flota.csv";

    private final Path raiz;

    public RepositorioDatos(Path raiz) {
        if (raiz == null) {
            throw new IllegalArgumentException("El repositorio necesita un directorio raiz de datos");
        }
        this.raiz = raiz;
    }

    /** Directorio raiz de los datos. */
    public Path raiz() {
        return raiz;
    }

    /** Carga el escenario que cubre un mes completo. */
    public DatosEscenario cargarMes(YearMonth mes) throws IOException {
        return cargar(mes.atDay(1), mes.atEndOfMonth());
    }

    /**
     * Carga el escenario que cubre el rango de dias indicado, ambos extremos incluidos.
     *
     * @param primerDia primer dia del escenario, cuyas 00:00 son el minuto interno cero
     * @param ultimoDia ultimo dia del escenario, incluido por completo
     * @return datos del escenario, con la lista de avisos de lo que no se pudo cargar
     */
    public DatosEscenario cargar(LocalDate primerDia, LocalDate ultimoDia) throws IOException {
        if (primerDia == null || ultimoDia == null) {
            throw new IllegalArgumentException("El escenario necesita un primer y un ultimo dia");
        }
        if (ultimoDia.isBefore(primerDia)) {
            throw new IllegalArgumentException("El rango del escenario esta invertido: " + primerDia
                    + " es posterior a " + ultimoDia);
        }

        CalendarioEscenario calendario = CalendarioEscenario.desde(primerDia);
        long minutoFin = calendario.aMinutos(ultimoDia.plusDays(1).atStartOfDay());
        List<String> avisos = new ArrayList<>();

        LectorFlota.FlotaLeida flota = cargarFlota(avisos);
        List<Pedido> pedidos = cargarPedidos(calendario, primerDia, ultimoDia, minutoFin, avisos);
        List<Bloqueo> bloqueos = cargarBloqueos(calendario, primerDia, ultimoDia, minutoFin, avisos);
        List<Mantenimiento> mantenimientos = cargarMantenimientos(primerDia, ultimoDia, avisos);

        return new DatosEscenario(calendario, primerDia, ultimoDia, flota, pedidos, bloqueos, mantenimientos, avisos);
    }

    // ------------------------------------------------------------------ flota

    /** Lee la flota, o usa la del enunciado si el directorio de datos no la trae. */
    private LectorFlota.FlotaLeida cargarFlota(List<String> avisos) throws IOException {
        LectorFlota lector = new LectorFlota();
        Path archivo = raiz.resolve(LectorFlota.NOMBRE_ARCHIVO);
        if (!Files.isRegularFile(archivo)) {
            archivo = raiz.resolve(ARCHIVO_FLOTA_ALTERNATIVO);
        }
        if (!Files.isRegularFile(archivo)) {
            avisos.add("No se encontro " + raiz.resolve(LectorFlota.NOMBRE_ARCHIVO)
                    + "; se usa la flota del enunciado: 10 autos, 15 motos y 12 bicicletas");
            return lector.flotaPorDefecto();
        }
        LectorFlota.FlotaLeida flota = lector.leer(archivo);
        avisarDiscrepancias(flota, archivo.getFileName().toString(), avisos);
        return flota;
    }

    /**
     * Avisa de los valores por tipo que el archivo declara distintos de los del enunciado.
     * El documento de dominio deja registrada justamente esa discrepancia entre la hoja de
     * flota y el enunciado en las velocidades, de modo que conviene que salte a la vista.
     */
    private static void avisarDiscrepancias(LectorFlota.FlotaLeida flota, String archivo, List<String> avisos) {
        for (TipoUnidad tipo : TipoUnidad.values()) {
            Integer capacidad = flota.capacidades().get(tipo);
            if (capacidad != null && capacidad != tipo.capacidad()) {
                avisos.add(archivo + ": declara capacidad " + capacidad + " para " + tipo.etiqueta()
                        + " y el enunciado fija " + tipo.capacidad());
            }
            Double velocidad = flota.velocidades().get(tipo);
            if (velocidad != null && velocidad != tipo.velocidadPorDefecto()) {
                avisos.add(archivo + ": declara velocidad " + velocidad + " Km/h para " + tipo.etiqueta()
                        + " y el enunciado fija " + tipo.velocidadPorDefecto());
            }
            Double costo = flota.costos().get(tipo);
            if (costo != null && costo != tipo.costoPorKm()) {
                avisos.add(archivo + ": declara costo " + costo + " por Km para " + tipo.etiqueta()
                        + " y el enunciado fija " + tipo.costoPorKm());
            }
        }
    }

    // ---------------------------------------------------------------- pedidos

    /** Lee los archivos mensuales de ventas del rango y devuelve los pedidos ordenados. */
    private List<Pedido> cargarPedidos(CalendarioEscenario calendario, LocalDate primerDia, LocalDate ultimoDia,
                                       long minutoFin, List<String> avisos) throws IOException {
        LectorVentas lector = new LectorVentas(calendario);
        Path directorio = raiz.resolve(DIRECTORIO_VENTAS);
        List<Pedido> pedidos = new ArrayList<>();
        int siguienteId = 0;

        for (YearMonth mes = YearMonth.from(primerDia); !mes.isAfter(YearMonth.from(ultimoDia)); mes = mes.plusMonths(1)) {
            String base = LectorVentas.nombreDeArchivo(mes);
            Path archivo = localizar(directorio, base);
            if (archivo == null) {
                avisos.add("No se encontro el archivo de ventas de " + mes + " (" + directorio.resolve(base)
                        + "); el mes se omite");
                continue;
            }
            List<Pedido> leidos = lector.leer(archivo, mes, siguienteId);
            // Los identificadores se reservan aunque el pedido se descarte, de modo que los
            // meses encadenados nunca reutilicen un identificador ya emitido.
            siguienteId += leidos.size();
            int descartados = 0;
            for (Pedido p : leidos) {
                if (p.minutoRegistro() >= 0 && p.minutoRegistro() < minutoFin) {
                    pedidos.add(p);
                } else {
                    descartados++;
                }
            }
            if (descartados > 0) {
                avisos.add(archivo.getFileName()
                        + ": pedidos omitidos por estar fuera del rango del escenario: " + descartados);
            }
        }

        pedidos.sort(Comparator.comparingLong(Pedido::minutoRegistro).thenComparingInt(Pedido::id));
        return pedidos;
    }

    // --------------------------------------------------------------- bloqueos

    /** Lee los archivos mensuales de bloqueos del rango. */
    private List<Bloqueo> cargarBloqueos(CalendarioEscenario calendario, LocalDate primerDia, LocalDate ultimoDia,
                                         long minutoFin, List<String> avisos) throws IOException {
        LectorBloqueos lector = new LectorBloqueos(calendario);
        Path directorio = raiz.resolve(DIRECTORIO_BLOQUEOS);
        List<Bloqueo> bloqueos = new ArrayList<>();

        for (YearMonth mes = YearMonth.from(primerDia); !mes.isAfter(YearMonth.from(ultimoDia)); mes = mes.plusMonths(1)) {
            String base = LectorBloqueos.nombreDeArchivo(mes);
            Path archivo = localizar(directorio, base.substring(0, 6));
            if (archivo == null) {
                avisos.add("No se encontro el archivo de bloqueos de " + mes + " (" + directorio.resolve(base)
                        + "); el mes se omite");
                continue;
            }
            List<Bloqueo> leidos = lector.leer(archivo, mes);
            int descartados = 0;
            for (Bloqueo b : leidos) {
                // Solo interesan los bloqueos cuya ventana toca el horizonte del escenario.
                if (b.minutoFin() > 0 && b.minutoInicio() < minutoFin) {
                    bloqueos.add(b);
                } else {
                    descartados++;
                }
            }
            if (descartados > 0) {
                avisos.add(archivo.getFileName()
                        + ": bloqueos omitidos por estar fuera del rango del escenario: " + descartados);
            }
        }

        bloqueos.sort(Comparator.comparingLong(Bloqueo::minutoInicio).thenComparingLong(Bloqueo::minutoFin));
        return bloqueos;
    }

    // ---------------------------------------------------------- mantenimiento

    /** Lee todos los planes de mantenimiento preventivo y se queda con los del rango. */
    private List<Mantenimiento> cargarMantenimientos(LocalDate primerDia, LocalDate ultimoDia, List<String> avisos)
            throws IOException {
        LectorMantenimiento lector = new LectorMantenimiento();
        Path directorio = raiz.resolve(DIRECTORIO_MANTENIMIENTO);
        List<Path> archivos = localizarTodos(directorio, LectorMantenimiento.PREFIJO_ARCHIVO);
        if (archivos.isEmpty()) {
            avisos.add("No se encontro ningun archivo " + LectorMantenimiento.PREFIJO_ARCHIVO + "m1.m2 en "
                    + directorio + "; el escenario corre sin mantenimiento preventivo");
            return List.of();
        }

        // Un mismo dia puede aparecer en dos planes solapados: el conjunto elimina el duplicado.
        Set<Mantenimiento> unicos = new LinkedHashSet<>();
        int descartados = 0;
        for (Path archivo : archivos) {
            for (Mantenimiento m : lector.leer(archivo)) {
                if (m.fecha().isBefore(primerDia) || m.fecha().isAfter(ultimoDia)) {
                    descartados++;
                } else {
                    unicos.add(m);
                }
            }
        }
        if (descartados > 0) {
            avisos.add("Mantenimiento preventivo: entradas omitidas por estar fuera del rango del escenario: "
                    + descartados);
        }

        List<Mantenimiento> mantenimientos = new ArrayList<>(unicos);
        mantenimientos.sort(Comparator.comparing(Mantenimiento::fecha).thenComparing(Mantenimiento::codigoUnidad));
        return mantenimientos;
    }

    // ------------------------------------------------------- busqueda de archivos

    /**
     * Primer archivo del directorio cuyo nombre arranca con el prefijo dado, o
     * {@code null} si no hay ninguno. El prefijo y no el nombre exacto, porque los archivos
     * del curso llegan a veces con extension anadida, como {@code ventas202601.txt}.
     */
    private static Path localizar(Path directorio, String prefijo) throws IOException {
        List<Path> encontrados = localizarTodos(directorio, prefijo);
        return encontrados.isEmpty() ? null : encontrados.get(0);
    }

    /**
     * Archivos cuyo nombre arranca con el prefijo dado, ordenados por nombre.
     *
     * <p>La busqueda es RECURSIVA. El nombre {@code mant.preventivo.m1.m2} que fija la
     * respuesta 19 del cuestionario no lleva el ano, de modo que cubrir varios anos obliga
     * a repartir los planes en subdirectorios por ano y el lector debe descender a ellos.
     * La misma recursion permite que ventas y bloqueos se organicen por ano si el equipo lo
     * prefiere, sin tocar el lector.</p>
     */
    private static List<Path> localizarTodos(Path directorio, String prefijo) throws IOException {
        if (!Files.isDirectory(directorio)) {
            return List.of();
        }
        try (Stream<Path> flujo = Files.walk(directorio)) {
            return flujo.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .regionMatches(true, 0, prefijo, 0, prefijo.length()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Escenario completo listo para el motor de simulacion.
     *
     * @param calendario      traductor entre el calendario real y el reloj interno
     * @param primerDia       primer dia del escenario
     * @param ultimoDia       ultimo dia del escenario, incluido
     * @param flota           unidades y valores por tipo declarados en el archivo de flota
     * @param pedidos         pedidos ordenados por instante de registro
     * @param bloqueos        bloqueos cuya ventana toca el horizonte del escenario
     * @param mantenimientos  entradas de mantenimiento preventivo del rango
     * @param avisos          archivos o registros que no se pudieron cargar y se omitieron
     */
    public record DatosEscenario(
            CalendarioEscenario calendario,
            LocalDate primerDia,
            LocalDate ultimoDia,
            LectorFlota.FlotaLeida flota,
            List<Pedido> pedidos,
            List<Bloqueo> bloqueos,
            List<Mantenimiento> mantenimientos,
            List<String> avisos) {

        public DatosEscenario {
            pedidos = List.copyOf(pedidos);
            bloqueos = List.copyOf(bloqueos);
            mantenimientos = List.copyOf(mantenimientos);
            avisos = List.copyOf(avisos);
        }

        /** Unidades de la flota, todas situadas en el almacen central. */
        public List<UnidadTransporte> unidades() {
            return flota.unidades();
        }

        /** Instante de cierre del escenario, en minutos desde su inicio. */
        public long minutoFin() {
            return calendario.aMinutos(ultimoDia.plusDays(1).atStartOfDay());
        }

        /** Cantidad de dias que cubre el escenario. */
        public int cantidadDias() {
            return (int) (minutoFin() / CalendarioEscenario.MINUTOS_POR_DIA);
        }

        /** Mantenimiento agrupado por dia, que es como lo consume el motor de simulacion. */
        public Map<LocalDate, Set<String>> mantenimientoPorFecha() {
            return LectorMantenimiento.agrupadoPorFecha(mantenimientos);
        }

        /** Indica si la carga dejo algo sin leer. */
        public boolean tieneAvisos() {
            return !avisos.isEmpty();
        }

        @Override
        public String toString() {
            return "DatosEscenario[" + primerDia + ".." + ultimoDia + " unidades=" + unidades().size()
                    + " pedidos=" + pedidos.size() + " bloqueos=" + bloqueos.size()
                    + " mantenimientos=" + mantenimientos.size() + " avisos=" + avisos.size() + "]";
        }
    }
}
