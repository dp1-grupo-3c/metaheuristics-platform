package org.kindbox.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * &lt;raiz&gt;/ventas/ventasAAAAMM[.txt] o ventas.AAAAMM.txt
 * &lt;raiz&gt;/bloqueos/AAAAMM.bloqueadas o bloqueo.AAMM.txt
 * &lt;raiz&gt;/mantenimiento/[aaaa/]mant.preventivo.m1.m2
 * &lt;raiz&gt;/mant.preventivo.m1.m2[.txt] (alternativa DP1)
 * &lt;raiz&gt;/flota.txt (o flota.csv)
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

    // Nombres mensuales que reconoce la carga de escenarios.
    private static final Pattern NOMBRE_VENTAS = Pattern.compile("(?i)ventas\\d{6}(\\.txt)?|ventas\\.\\d{6}\\.txt");
    private static final Pattern NOMBRE_BLOQUEOS = Pattern.compile("(?i)\\d{6}\\.bloqueadas(\\.txt)?|bloqueo\\.\\d{4}\\.txt");
    private static final Pattern MES_BLOQUEOS = Pattern.compile(
            "(?i)(\\d{4})(\\d{2})\\.?bloqueadas(\\.txt)?|bloqueo\\.(\\d{2})(\\d{2})\\.txt");

    private final Path raiz;
    // Cantidades por tipo fijadas en caliente; null usa las del archivo de flota.
    private volatile Map<TipoUnidad, Integer> cantidadesFlota;

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

    public DatosEscenario cargarEntorno(LocalDate primerDia, LocalDate ultimoDia,
                                        CalendarioEscenario calendario) throws IOException {
        if (primerDia == null || ultimoDia == null || calendario == null || ultimoDia.isBefore(primerDia)) {
            throw new IllegalArgumentException("El rango del entorno es invalido");
        }
        List<String> avisos = new ArrayList<>();
        long fin = calendario.aMinutos(ultimoDia.plusDays(1).atStartOfDay());
        return new DatosEscenario(calendario, primerDia, ultimoDia, cargarFlota(avisos), List.of(),
                cargarBloqueos(calendario, primerDia, ultimoDia, fin, avisos),
                cargarMantenimientos(primerDia, ultimoDia, avisos), avisos);
    }

    /**
     * Abre un escenario sin fecha de fin, como el de colapso.
     *
     * <p>Carga de inmediato la flota, todo el mantenimiento preventivo desde {@code primerDia}
     * y el mes de {@code primerDia}, y devuelve la fuente que entrega los meses siguientes a
     * medida que el motor los pide. Asi el escenario no queda acotado por una fecha de fin
     * elegida de antemano ni obliga a leer meses que la corrida quiza nunca alcance.</p>
     *
     * @param primerDia primer dia del escenario, cuyas 00:00 son el minuto interno cero
     * @return datos del primer mes y fuente de los siguientes, que comparten calendario y
     *         numeracion de pedidos
     */
    public CargaAbierta abrir(LocalDate primerDia) throws IOException {
        if (primerDia == null) {
            throw new IllegalArgumentException("El escenario necesita un primer dia");
        }
        CalendarioEscenario calendario = CalendarioEscenario.desde(primerDia);
        List<String> avisos = new ArrayList<>();
        LectorFlota.FlotaLeida flota = cargarFlota(avisos);
        List<Mantenimiento> mantenimientos = cargarMantenimientos(primerDia, LocalDate.MAX, avisos);

        FuenteMensual fuente = new FuenteMensual(calendario);
        YearMonth mes = YearMonth.from(primerDia);
        TramoMensual primero = fuente.cargar(mes);
        avisos.addAll(primero.avisos());
        DatosEscenario inicio = new DatosEscenario(calendario, primerDia, mes.atEndOfMonth(), flota,
                primero.pedidos(), primero.bloqueos(), mantenimientos, avisos);
        return new CargaAbierta(inicio, fuente);
    }

    /**
     * Fuente de los meses de un escenario abierto. Conserva el siguiente identificador de
     * pedido entre llamadas, de modo que los meses encadenados no repiten identificadores.
     */
    private final class FuenteMensual implements FuenteTramos {

        private final CalendarioEscenario calendario;
        private final LectorVentas lectorVentas;
        private final LectorBloqueos lectorBloqueos;
        private int siguienteId;

        FuenteMensual(CalendarioEscenario calendario) {
            this.calendario = calendario;
            this.lectorVentas = new LectorVentas(calendario);
            this.lectorBloqueos = new LectorBloqueos(calendario);
        }

        @Override
        public synchronized TramoMensual cargar(YearMonth mes) throws IOException {
            List<String> avisos = new ArrayList<>();
            long minutoFin = calendario.aMinutos(mes.plusMonths(1).atDay(1).atStartOfDay());
            LecturaVentas lectura = pedidosDelMes(lectorVentas, mes, siguienteId, minutoFin, avisos);
            if (lectura == null) {
                return new TramoMensual(mes, false, List.of(), List.of(), avisos);
            }
            siguienteId += lectura.leidos();
            List<Pedido> pedidos = new ArrayList<>(lectura.aceptados());
            pedidos.sort(Comparator.comparingLong(Pedido::minutoRegistro).thenComparingInt(Pedido::id));
            List<Bloqueo> bloqueos = new ArrayList<>(bloqueosDelMes(lectorBloqueos, mes, minutoFin, avisos));
            bloqueos.sort(Comparator.comparingLong(Bloqueo::minutoInicio).thenComparingLong(Bloqueo::minutoFin));
            return new TramoMensual(mes, true, pedidos, bloqueos, avisos);
        }
    }

    // ------------------------------------------------------------------ flota

    // Reemplaza las cantidades del archivo de flota en las cargas siguientes; null las restablece.
    public void cantidadesFlota(Map<TipoUnidad, Integer> cantidades) throws IOException {
        if (cantidades == null) {
            cantidadesFlota = null;
            return;
        }
        Map<TipoUnidad, Integer> copia = new EnumMap<>(TipoUnidad.class);
        for (Map.Entry<TipoUnidad, Integer> entrada : cantidades.entrySet()) {
            if (entrada.getKey() == null || entrada.getValue() == null) {
                throw new IllegalArgumentException("Cada cantidad necesita su tipo de unidad y su valor");
            }
            copia.put(entrada.getKey(), entrada.getValue());
        }
        // Se prueba contra la flota base para no dejar un ajuste que impida cargar escenarios.
        new LectorFlota().conCantidades(flotaBase(new ArrayList<>()), copia);
        cantidadesFlota = Collections.unmodifiableMap(copia);
    }

    // Unidades por tipo de la flota que recibiria la proxima carga, con el ajuste aplicado.
    public Map<TipoUnidad, Integer> cantidadesFlotaVigentes() throws IOException {
        LectorFlota.FlotaLeida flota = cargarFlota(new ArrayList<>());
        Map<TipoUnidad, Integer> cantidades = new EnumMap<>(TipoUnidad.class);
        for (TipoUnidad tipo : TipoUnidad.values()) {
            cantidades.put(tipo, flota.cantidadDeTipo(tipo));
        }
        return cantidades;
    }

    private LectorFlota.FlotaLeida cargarFlota(List<String> avisos) throws IOException {
        LectorFlota.FlotaLeida flota = flotaBase(avisos);
        Map<TipoUnidad, Integer> ajuste = cantidadesFlota;
        if (ajuste == null) {
            return flota;
        }
        LectorFlota.FlotaLeida ajustada = new LectorFlota().conCantidades(flota, ajuste);
        List<String> partes = new ArrayList<>();
        for (TipoUnidad tipo : TipoUnidad.values()) {
            int n = ajustada.cantidadDeTipo(tipo);
            String nombre = tipo.etiqueta().toLowerCase(Locale.ROOT);
            partes.add(n + " " + (n == 1 ? nombre : nombre + "s"));
        }
        avisos.add("Flota ajustada por parametro: " + String.join(", ", partes));
        return ajustada;
    }

    /** Lee la flota, o usa la del enunciado si el directorio de datos no la trae. */
    private LectorFlota.FlotaLeida flotaBase(List<String> avisos) throws IOException {
        LectorFlota lector = new LectorFlota();
        Path archivo = archivoFlota();
        if (archivo == null) {
            avisos.add("No se encontro " + raiz.resolve(LectorFlota.NOMBRE_ARCHIVO)
                    + "; se usa la flota del enunciado: 10 autos, 15 motos y 12 bicicletas");
            return lector.flotaPorDefecto();
        }
        LectorFlota.FlotaLeida flota = lector.leer(archivo);
        avisarDiscrepancias(flota, archivo.getFileName().toString(), avisos);
        return flota;
    }

    // flota.txt tiene prioridad sobre flota.csv; null si no hay ninguno.
    private Path archivoFlota() {
        Path archivo = raiz.resolve(LectorFlota.NOMBRE_ARCHIVO);
        if (!Files.isRegularFile(archivo)) {
            archivo = raiz.resolve(ARCHIVO_FLOTA_ALTERNATIVO);
        }
        return Files.isRegularFile(archivo) ? archivo : null;
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
        List<Pedido> pedidos = new ArrayList<>();
        int siguienteId = 0;

        for (YearMonth mes = YearMonth.from(primerDia); !mes.isAfter(YearMonth.from(ultimoDia)); mes = mes.plusMonths(1)) {
            LecturaVentas lectura = pedidosDelMes(lector, mes, siguienteId, minutoFin, avisos);
            if (lectura == null) {
                continue;
            }
            // Los identificadores se reservan aunque el pedido se descarte, de modo que los
            // meses encadenados nunca reutilicen un identificador ya emitido.
            siguienteId += lectura.leidos();
            pedidos.addAll(lectura.aceptados());
        }

        pedidos.sort(Comparator.comparingLong(Pedido::minutoRegistro).thenComparingInt(Pedido::id));
        return pedidos;
    }

    /**
     * Lee el archivo de ventas de un mes y se queda con los pedidos de {@code [0, minutoFin)}.
     *
     * @return la lectura, o {@code null} si el mes no tiene archivo, con el aviso ya anotado
     */
    private LecturaVentas pedidosDelMes(LectorVentas lector, YearMonth mes, int primerId, long minutoFin,
                                        List<String> avisos) throws IOException {
        Path directorio = raiz.resolve(DIRECTORIO_VENTAS);
        String base = LectorVentas.nombreDeArchivo(mes);
        Path archivo = localizarMensual(directorio, List.of(
                base, base + ".txt",
                "ventas." + String.format("%04d%02d", mes.getYear(), mes.getMonthValue()) + ".txt"));
        if (archivo == null) {
            avisos.add("No se encontro el archivo de ventas de " + mes + " (" + directorio.resolve(base)
                    + "); el mes se omite");
            return null;
        }
        List<Pedido> leidos = lector.leer(archivo, mes, primerId);
        List<Pedido> aceptados = new ArrayList<>(leidos.size());
        int descartados = 0;
        for (Pedido p : leidos) {
            if (p.minutoRegistro() >= 0 && p.minutoRegistro() < minutoFin) {
                aceptados.add(p);
            } else {
                descartados++;
            }
        }
        if (descartados > 0) {
            avisos.add(archivo.getFileName()
                    + ": pedidos omitidos por estar fuera del rango del escenario: " + descartados);
        }
        return new LecturaVentas(aceptados, leidos.size());
    }

    /** Pedidos aceptados de un archivo mensual y cuantos registros traia en total. */
    private record LecturaVentas(List<Pedido> aceptados, int leidos) {}

    // --------------------------------------------------------------- bloqueos

    /** Lee los archivos mensuales de bloqueos del rango. */
    private List<Bloqueo> cargarBloqueos(CalendarioEscenario calendario, LocalDate primerDia, LocalDate ultimoDia,
                                         long minutoFin, List<String> avisos) throws IOException {
        LectorBloqueos lector = new LectorBloqueos(calendario);
        List<Bloqueo> bloqueos = new ArrayList<>();

        for (YearMonth mes = YearMonth.from(primerDia); !mes.isAfter(YearMonth.from(ultimoDia)); mes = mes.plusMonths(1)) {
            bloqueos.addAll(bloqueosDelMes(lector, mes, minutoFin, avisos));
        }

        bloqueos.sort(Comparator.comparingLong(Bloqueo::minutoInicio).thenComparingLong(Bloqueo::minutoFin));
        return bloqueos;
    }

    private static List<String> nombresBloqueos(YearMonth mes) {
        String base = LectorBloqueos.nombreDeArchivo(mes);
        String periodoDp1 = String.format("%02d%02d", mes.getYear() % 100, mes.getMonthValue());
        return List.of(base, base + ".txt", "bloqueo." + periodoDp1 + ".txt");
    }

    /** Bloqueos de un mes cuya ventana toca {@code (0, minutoFin)}; vacio si el mes no tiene archivo. */
    private List<Bloqueo> bloqueosDelMes(LectorBloqueos lector, YearMonth mes, long minutoFin,
                                         List<String> avisos) throws IOException {
        Path directorio = raiz.resolve(DIRECTORIO_BLOQUEOS);
        String base = LectorBloqueos.nombreDeArchivo(mes);
        Path archivo = localizarMensual(directorio, nombresBloqueos(mes));
        if (archivo == null) {
            avisos.add("No se encontro el archivo de bloqueos de " + mes + " (" + directorio.resolve(base)
                    + "); el mes se omite");
            return List.of();
        }
        List<Bloqueo> leidos = lector.leer(archivo, mes);
        List<Bloqueo> aceptados = new ArrayList<>(leidos.size());
        int descartados = 0;
        for (Bloqueo b : leidos) {
            // Solo interesan los bloqueos cuya ventana toca el horizonte del escenario.
            if (b.minutoFin() > 0 && b.minutoInicio() < minutoFin) {
                aceptados.add(b);
            } else {
                descartados++;
            }
        }
        if (descartados > 0) {
            avisos.add(archivo.getFileName()
                    + ": bloqueos omitidos por estar fuera del rango del escenario: " + descartados);
        }
        return aceptados;
    }

    // ---------------------------------------------------------- mantenimiento

    /** Lee todos los planes de mantenimiento preventivo y se queda con los del rango. */
    private List<Mantenimiento> cargarMantenimientos(LocalDate primerDia, LocalDate ultimoDia, List<String> avisos)
            throws IOException {
        LectorMantenimiento lector = new LectorMantenimiento();
        Path directorio = raiz.resolve(DIRECTORIO_MANTENIMIENTO);
        List<Path> archivos = planesDeMantenimiento();
        if (archivos.isEmpty()) {
            avisos.add("No se encontro ningun archivo " + LectorMantenimiento.PREFIJO_ARCHIVO + "m1.m2 en "
                    + directorio + " ni en " + raiz + "; el escenario corre sin mantenimiento preventivo");
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

    private List<Path> planesDeMantenimiento() throws IOException {
        Set<Path> encontrados = new LinkedHashSet<>(
                localizarTodos(raiz.resolve(DIRECTORIO_MANTENIMIENTO), LectorMantenimiento.PREFIJO_ARCHIVO));
        // DP1 publica los planes directamente en la raiz junto a flota.txt.
        if (Files.isDirectory(raiz)) {
            try (Stream<Path> flujo = Files.list(raiz)) {
                flujo.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().regionMatches(true, 0,
                                LectorMantenimiento.PREFIJO_ARCHIVO, 0,
                                LectorMantenimiento.PREFIJO_ARCHIVO.length()))
                        .forEach(encontrados::add);
            }
        }
        return encontrados.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    // ------------------------------------------------------- carga de archivos

    // Archivos que usaria la carga de escenarios, con rutas relativas a la raiz.
    public Inventario inventario() throws IOException {
        Path flota = archivoFlota();
        return new Inventario(flota == null ? null : relativa(flota),
                relativas(coincidencias(raiz.resolve(DIRECTORIO_VENTAS), NOMBRE_VENTAS)),
                relativas(coincidencias(raiz.resolve(DIRECTORIO_BLOQUEOS), NOMBRE_BLOQUEOS)),
                relativas(planesDeMantenimiento()));
    }

    // Reemplaza flota.txt y descarta el ajuste de cantidades, que se referia a la flota anterior.
    public synchronized ArchivoGuardado guardarFlota(Path origen) throws IOException {
        LectorFlota.FlotaLeida flota = new LectorFlota().leer(exigirArchivo(origen));
        Path destino = raiz.resolve(LectorFlota.NOMBRE_ARCHIVO);
        boolean reemplazado = escribirAtomico(origen, destino);
        cantidadesFlota = null;
        return new ArchivoGuardado(relativa(destino), flota.unidades().size(), reemplazado);
    }

    // Sin mes se deduce del nombre del archivo; reemplaza el archivo del mes donde este.
    public synchronized ArchivoGuardado guardarBloqueos(YearMonth mes, Path origen) throws IOException {
        exigirArchivo(origen);
        YearMonth periodo = mes != null ? mes : mesDeArchivoBloqueos(origen.getFileName().toString());
        List<Bloqueo> bloqueos = new LectorBloqueos(CalendarioEscenario.desde(periodo)).leer(origen, periodo);
        Path directorio = raiz.resolve(DIRECTORIO_BLOQUEOS);
        List<Path> existentes = coincidencias(directorio, nombresBloqueos(periodo));
        if (existentes.size() > 1) {
            throw new IllegalStateException("Hay mas de un archivo de bloqueos de " + periodo + ": "
                    + relativas(existentes) + ". Conserve uno solo antes de cargar otro");
        }
        Path destino = existentes.isEmpty()
                ? directorio.resolve(LectorBloqueos.nombreDeArchivo(periodo))
                : existentes.get(0);
        boolean reemplazado = escribirAtomico(origen, destino);
        return new ArchivoGuardado(relativa(destino), bloqueos.size(), reemplazado);
    }

    /**
     * Agrega un bloqueo al archivo del mes, o de los meses, que abarca.
     *
     * <p>El formato solo admite dias del mes del archivo, de modo que un bloqueo que cruza de
     * mes se parte en un registro por mes: el primero termina a las 23:59 del ultimo dia y el
     * siguiente empieza a las 00:00, y ese minuto queda libre. Todos los registros se validan
     * antes de escribir el primero, para no dejar el bloqueo a medias.</p>
     *
     * @param coordenadas pares x,y de la poligonal, en orden
     * @param inicio      instante de activacion, al minuto
     * @param fin         instante de desactivacion, al minuto y posterior al inicio
     * @return un archivo guardado por cada mes, con los registros que quedaron en el
     */
    public synchronized List<ArchivoGuardado> agregarBloqueo(int[] coordenadas, LocalDateTime inicio,
                                                             LocalDateTime fin) throws IOException {
        if (!fin.isAfter(inicio)) {
            throw new IllegalArgumentException("El fin del bloqueo debe ser posterior a su inicio");
        }
        StringJoiner poligonal = new StringJoiner(",");
        for (int coordenada : coordenadas) {
            poligonal.add(String.valueOf(coordenada));
        }
        Path temporal = Files.createTempDirectory("kindbox-bloqueo-");
        try {
            Map<YearMonth, Path> preparados = new LinkedHashMap<>();
            for (YearMonth mes = YearMonth.from(inicio); !mes.isAfter(YearMonth.from(fin)); mes = mes.plusMonths(1)) {
                LocalDateTime desde = mes.equals(YearMonth.from(inicio)) ? inicio : mes.atDay(1).atStartOfDay();
                LocalDateTime hastaDelMes = mes.atEndOfMonth().atTime(23, 59);
                LocalDateTime hasta = fin.isBefore(hastaDelMes) ? fin : hastaDelMes;
                if (hasta.isAfter(desde)) {
                    preparados.put(mes, prepararBloqueo(temporal, mes, registroBloqueo(desde, hasta, poligonal.toString())));
                }
            }
            if (preparados.isEmpty()) {
                throw new IllegalArgumentException("El bloqueo debe durar al menos un minuto dentro de un mes");
            }
            List<ArchivoGuardado> guardados = new ArrayList<>();
            for (Map.Entry<YearMonth, Path> preparado : preparados.entrySet()) {
                guardados.add(guardarBloqueos(preparado.getKey(), preparado.getValue()));
            }
            return guardados;
        } finally {
            try (Stream<Path> flujo = Files.walk(temporal)) {
                flujo.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String registroBloqueo(LocalDateTime desde, LocalDateTime hasta, String poligonal) {
        return String.format(Locale.ROOT, "%02dd%02dh%02dm-%02dd%02dh%02dm:%s", desde.getDayOfMonth(), desde.getHour(),
                desde.getMinute(), hasta.getDayOfMonth(), hasta.getHour(), hasta.getMinute(), poligonal);
    }

    // Copia del archivo del mes con el registro al final, ya validada entera.
    private Path prepararBloqueo(Path temporal, YearMonth mes, String registro) throws IOException {
        List<Path> existentes = coincidencias(raiz.resolve(DIRECTORIO_BLOQUEOS), nombresBloqueos(mes));
        if (existentes.size() > 1) {
            throw new IllegalStateException("Hay mas de un archivo de bloqueos de " + mes + ": "
                    + relativas(existentes) + ". Conserve uno solo antes de agregar otro");
        }
        Path copia = Files.createDirectory(temporal.resolve(mes.toString())).resolve("bloqueo-manual.txt");
        byte[] actuales = existentes.isEmpty() ? new byte[0] : Files.readAllBytes(existentes.get(0));
        boolean cerrado = actuales.length == 0 || actuales[actuales.length - 1] == '\n';
        Files.write(copia, actuales);
        Files.writeString(copia, (cerrado ? "" : "\n") + registro + "\n", StandardCharsets.ISO_8859_1,
                StandardOpenOption.APPEND);
        new LectorBloqueos(CalendarioEscenario.desde(mes)).leer(copia, mes);
        return copia;
    }

    // El plan va al subdirectorio del ano de su primera fecha, porque el nombre no lleva el ano.
    public synchronized ArchivoGuardado guardarMantenimiento(String nombre, Path origen) throws IOException {
        String prefijo = LectorMantenimiento.PREFIJO_ARCHIVO;
        if (nombre == null || nombre.contains("/") || nombre.contains("\\")
                || !nombre.regionMatches(true, 0, prefijo, 0, prefijo.length()) || nombre.length() == prefijo.length()) {
            throw new IllegalArgumentException("El plan de mantenimiento debe llamarse " + prefijo
                    + "m1.m2, por ejemplo " + prefijo + "09.10, y se recibio \"" + nombre + "\"");
        }
        List<Mantenimiento> entradas = new LectorMantenimiento().leer(exigirArchivo(origen));
        if (entradas.isEmpty()) {
            throw new IllegalArgumentException("El plan de mantenimiento " + nombre + " no trae ninguna fecha");
        }
        Path destino = raiz.resolve(DIRECTORIO_MANTENIMIENTO)
                .resolve(String.valueOf(entradas.get(0).fecha().getYear())).resolve(nombre);
        boolean reemplazado = escribirAtomico(origen, destino);
        return new ArchivoGuardado(relativa(destino), entradas.size(), reemplazado);
    }

    // Acepta AAAAMM.bloqueadas, AAAAMMbloqueadas, cualquiera de ellos con .txt, y bloqueo.AAMM.txt.
    public static YearMonth mesDeArchivoBloqueos(String nombre) {
        Matcher m = nombre == null ? null : MES_BLOQUEOS.matcher(nombre);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("No se puede deducir el mes del archivo de bloqueos \"" + nombre
                    + "\"; se esperaba AAAAMM.bloqueadas o bloqueo.AAMM.txt, o indicar el mes");
        }
        int ano = m.group(1) != null ? Integer.parseInt(m.group(1)) : 2000 + Integer.parseInt(m.group(4));
        int mes = Integer.parseInt(m.group(1) != null ? m.group(2) : m.group(5));
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El archivo de bloqueos \"" + nombre + "\" declara el mes "
                    + mes + ", que no existe");
        }
        return YearMonth.of(ano, mes);
    }

    private static Path exigirArchivo(Path origen) {
        if (origen == null || !Files.isRegularFile(origen)) {
            throw new IllegalArgumentException("No existe el archivo a cargar: " + origen);
        }
        return origen;
    }

    // Copia a un temporal del mismo directorio y lo mueve de un golpe sobre el destino.
    private static boolean escribirAtomico(Path origen, Path destino) throws IOException {
        Path directorio = destino.toAbsolutePath().getParent();
        Files.createDirectories(directorio);
        boolean existia = Files.exists(destino);
        Path temporal = Files.createTempFile(directorio, ".carga-", ".tmp");
        try {
            Files.copy(origen, temporal, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporal);
        }
        return existia;
    }

    private String relativa(Path archivo) {
        return raiz.toAbsolutePath().normalize().relativize(archivo.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private List<String> relativas(List<Path> archivos) {
        return archivos.stream().map(this::relativa).toList();
    }

    private static List<Path> coincidencias(Path directorio, Pattern nombre) throws IOException {
        if (!Files.isDirectory(directorio)) {
            return List.of();
        }
        try (Stream<Path> flujo = Files.walk(directorio)) {
            return flujo.filter(Files::isRegularFile)
                    .filter(p -> nombre.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    // ------------------------------------------------------- busqueda de archivos

    /** Coincidencias exactas, ignorando mayusculas, para un unico mes del escenario. */
    private static Path localizarMensual(Path directorio, List<String> nombres) throws IOException {
        List<Path> archivos = coincidencias(directorio, nombres);
        if (archivos.size() > 1) {
            throw new IOException("Archivos mensuales ambiguos en " + directorio
                    + ": " + archivos + ". Conserve un solo archivo por mes; no se combinan copias.");
        }
        return archivos.isEmpty() ? null : archivos.get(0);
    }

    private static List<Path> coincidencias(Path directorio, List<String> nombres) throws IOException {
        if (!Files.isDirectory(directorio)) {
            return List.of();
        }
        try (Stream<Path> flujo = Files.walk(directorio)) {
            return flujo.filter(Files::isRegularFile)
                    .filter(p -> nombres.stream().anyMatch(n ->
                            n.equalsIgnoreCase(p.getFileName().toString())))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
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

    // Archivos de datos presentes; flota es null si no hay archivo y se usaria la del enunciado.
    public record Inventario(String flota, List<String> ventas, List<String> bloqueos, List<String> mantenimiento) {

        public Inventario {
            ventas = List.copyOf(ventas);
            bloqueos = List.copyOf(bloqueos);
            mantenimiento = List.copyOf(mantenimiento);
        }
    }

    // Ruta relativa a la raiz del archivo guardado, registros validados y si piso uno anterior.
    public record ArchivoGuardado(String ruta, int registros, boolean reemplazado) {}

    /**
     * Un mes de un escenario abierto.
     *
     * @param mes       mes al que pertenece el tramo
     * @param conVentas si el mes tiene archivo de ventas; sin el, el tramo viene vacio y marca
     *                  el final de los datos disponibles
     * @param pedidos   pedidos del mes, ordenados por instante de registro
     * @param bloqueos  bloqueos del archivo del mes, ordenados por inicio
     * @param avisos    archivos o registros del mes que no se pudieron cargar
     */
    public record TramoMensual(YearMonth mes, boolean conVentas, List<Pedido> pedidos,
                               List<Bloqueo> bloqueos, List<String> avisos) {

        public TramoMensual {
            pedidos = List.copyOf(pedidos);
            bloqueos = List.copyOf(bloqueos);
            avisos = List.copyOf(avisos);
        }
    }

    /**
     * Escenario abierto: el primer mes ya cargado y la fuente de los siguientes.
     *
     * @param inicio    datos del escenario con el primer mes, la flota y el mantenimiento
     * @param siguientes fuente de los meses posteriores al primero
     */
    public record CargaAbierta(DatosEscenario inicio, FuenteTramos siguientes) {}

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

        // Los mismos datos sin los pedidos registrados antes del minuto dado.
        public DatosEscenario sinPedidosAntesDe(long minuto) {
            if (minuto <= 0) {
                return this;
            }
            return new DatosEscenario(calendario, primerDia, ultimoDia, flota,
                    pedidos.stream().filter(p -> p.minutoRegistro() >= minuto).toList(),
                    bloqueos, mantenimientos, avisos);
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
