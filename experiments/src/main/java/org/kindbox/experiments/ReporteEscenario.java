package org.kindbox.experiments;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.io.RepositorioDatos.DatosEscenario;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;

/**
 * Observador de planes comprometidos y ejecucion real. No modifica el algoritmo.
 * El diagnostico de banco prueba rutas individuales sobre la fotografia original:
 * no descuenta inventario reservado por otras rutas ni prueba factibilidad conjunta.
 */
public final class ReporteEscenario implements ObservadorSimulacion, AutoCloseable {
    /** Punto de entrada para CorrerEscenario; conecta observador y escribe el resumen final. */
    public static ResultadoSimulacion ejecutar(DatosEscenario datos,
            ConfiguracionEscenario configuracion,
            org.kindbox.core.modelo.ParametrosOperacion parametros,
            org.kindbox.core.metaheuristica.Algoritmo algoritmo) throws IOException {
        if (!Boolean.parseBoolean(System.getProperty("reporte.habilitado", "true"))) {
            return new org.kindbox.core.simulacion.MotorSimulacion(
                    datos, configuracion, parametros, algoritmo, List.of()).ejecutar();
        }
        try (ReporteEscenario reporte = crear(datos, configuracion)) {
            ResultadoSimulacion resultado = new org.kindbox.core.simulacion.MotorSimulacion(
                    datos, configuracion, parametros, algoritmo, List.of(reporte)).ejecutar();
            reporte.generarReporte(resultado);
            return resultado;
        }
    }
    private static final class Seguimiento {
        final Pedido pedido;
        int entregado;
        int planes;
        int enBanco;
        int sinAsignacion;
        int cambios;
        boolean incumplido;
        Long completadoEn;
        String vehiculosAnteriores;
        final StringBuilder historia = new StringBuilder();
        Seguimiento(Pedido pedido) { this.pedido = pedido; }
    }

    private final DatosEscenario datos;
    private final Path directorio;
    private final PrintWriter planes;
    private final PrintWriter eventos;
    private final Map<Integer, Seguimiento> seguimiento = new LinkedHashMap<>();
    private final boolean consola;
    private final boolean diagnosticarBanco;
    private int replanificaciones;
    private int completados;
    private int incumplidos;
    private long paquetes;
    private boolean finalizado;
    private RuntimeException falloObservador;

    /** Directorio nuevo por corrida, dentro de -Dreporte.directorio=reportes. */
    public static ReporteEscenario crear(DatosEscenario datos, ConfiguracionEscenario configuracion)
            throws IOException {
        Path base = Path.of(System.getProperty("reporte.directorio", "reportes"));
        Files.createDirectories(base);
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path destino = Files.createTempDirectory(base, "escenario-" + fecha + "-");
        ReporteEscenario reporte = new ReporteEscenario(datos, destino,
                Boolean.parseBoolean(System.getProperty("reporte.consola", "true")),
                Boolean.parseBoolean(System.getProperty("reporte.diagnosticarBanco", "true")));
        reporte.log("Configuracion: " + configuracion);
        return reporte;
    }

    public ReporteEscenario(DatosEscenario datos, Path directorio, boolean consola,
                             boolean diagnosticarBanco) throws IOException {
        this.datos = datos;
        this.directorio = directorio.toAbsolutePath();
        this.consola = consola;
        this.diagnosticarBanco = diagnosticarBanco;
        Files.createDirectories(this.directorio);
        for (Pedido pedido : datos.pedidos()) {
            if (seguimiento.put(pedido.id(), new Seguimiento(pedido)) != null) {
                throw new IllegalArgumentException("ID de pedido duplicado: " + pedido.id());
            }
        }
        planes = abrir("planes.log");
        PrintWriter segundo;
        try {
            segundo = abrir("eventos.csv");
        } catch (IOException error) {
            planes.close();
            throw error;
        }
        eventos = segundo;
        eventos.println("minuto,fecha,tipo,pedido,vehiculo,cantidad,pendiente,detalle");
        log("PLANES COMPROMETIDOS: las llegadas son previstas, no entregas ejecutadas.");
        log("Origen del reloj: " + datos.calendario().inicio());
        log("DIAGNOSTICO AISLADO: no reserva inventario de otras rutas; no prueba alternativas conjuntas.");
        for (String aviso : datos.avisos()) log("AVISO DE CARGA: " + aviso);
    }

    public Path directorio() { return directorio; }

    @Override
    public void alPublicarPlan(long minuto, InstanciaPlanificacion instancia,
                              ResultadoPlanificacion resultado) {
        try {
            registrarPlan(minuto, instancia, resultado);
        } catch (RuntimeException error) {
            // MotorSimulacion aisla excepciones de observadores; conservarlas para el cierre.
            falloObservador = error;
            throw error;
        }
    }

    private void registrarPlan(long minuto, InstanciaPlanificacion instancia,
                               ResultadoPlanificacion resultado) {
        replanificaciones++;
        Solucion plan = resultado.solucion();
        log("\nREPLANIFICACION " + replanificaciones + " -- " + instante(minuto)
                + " | algoritmo=" + resultado.algoritmo() + " semilla=" + resultado.semilla()
                + " iteraciones=" + resultado.iteraciones() + " tiempoMs=" + resultado.milisegundos()
                + " H=" + plan.h() + " costo=" + plan.costo());
        int margenHolgura = org.kindbox.core.evaluacion.HolguraTemporal.margenConfigurado();
        log("Riesgo temporal=" + org.kindbox.core.evaluacion.HolguraTemporal.riesgo(instancia, plan, margenHolgura)
                + " margenObjetivoMin=" + margenHolgura + " (solo desempata el objetivo existente)");
        log("Pesos finales de operadores: " + resultado.pesosOperadores());
        log("Unidades incluidas en fotografia=" + instancia.cantidadUnidades()
                + " de flota cargada=" + datos.unidades().size()
                + "; las excluidas no son candidatas de este planificador.");
        Map<String, Ruta> rutas = new LinkedHashMap<>();
        Map<Integer, Integer> asignado = new LinkedHashMap<>();
        Map<Integer, List<String>> vehiculos = new LinkedHashMap<>();
        for (Ruta ruta : plan.rutas()) {
            if (rutas.put(ruta.codigoUnidad(), ruta) != null) {
                log("ADVERTENCIA: mas de una ruta para " + ruta.codigoUnidad());
            }
        }
        List<Integer> sinEntregas = new ArrayList<>();
        for (int u = 0; u < instancia.cantidadUnidades(); u++) {
            String codigo = instancia.unidadCodigo(u);
            Ruta ruta = rutas.get(codigo);
            if (ruta == null || ruta.sinEntregas()) sinEntregas.add(u);
            log("VEHICULO " + codigo + " tipo=" + instancia.unidadTipo(u)
                    + " posicion=" + Ciudad.texto(instancia.unidadNodo(u))
                    + " carga=" + instancia.unidadCarga(u) + "/" + instancia.unidadCapacidad(u)
                    + " disponible=" + instante(instancia.unidadMinutoDisponible(u))
                    + " finHorizonte=" + instante(instancia.unidadMinutoFinHorizonte(u))
                    + " retornoObligatorio=" + instancia.unidadRetornaAlCentral(u));
            if (ruta == null || ruta.paradas().isEmpty()) {
                log("  Sin ruta devuelta. El motor puede agregar un retorno obligatorio al central.");
                continue;
            }
            int orden = 0;
            for (Parada parada : ruta.paradas()) {
                String texto = "  " + (++orden) + ". " + parada.tipo()
                        + " " + Ciudad.texto(parada.nodo()) + " cantidad=" + parada.cantidad()
                        + " llegada=" + instante(parada.minutoLlegada())
                        + " salida=" + instante(parada.minutoSalida())
                        + " kmTramo=" + parada.kmDesdeAnterior();
                if (parada.tipo() == TipoParada.ENTREGA) {
                    int id = parada.idPedido();
                    asignado.merge(id, parada.cantidad(), Integer::sum);
                    vehiculos.computeIfAbsent(id, ignorado -> new ArrayList<>()).add(codigo);
                    int p = instancia.indiceDePedido(id);
                    texto += " pedido=" + id;
                    if (p >= 0) texto += " holguraLlegada="
                            + (instancia.pedidoMinutoLimite(p) - parada.minutoLlegada()) + " min";
                } else if (parada.idAlmacen() >= 0) {
                    texto += " almacen=" + parada.idAlmacen();
                }
                log(texto);
            }
        }
        log("BANCO (cantidad sin asignar por pedido): " + plan.cantidadNoAtendida());
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            int id = instancia.pedidoId(p);
            Seguimiento s = seguimiento.get(id);
            int banco = plan.cantidadNoAtendida().getOrDefault(id, 0);
            int cantidad = asignado.getOrDefault(id, 0);
            String unidades = vehiculos.getOrDefault(id, List.of()).stream().distinct().sorted()
                    .collect(java.util.stream.Collectors.joining("|"));
            if (s != null) {
                s.planes++;
                if (banco > 0) s.enBanco++;
                if (cantidad == 0) s.sinAsignacion++;
                if (s.vehiculosAnteriores != null && !s.vehiculosAnteriores.equals(unidades)) s.cambios++;
                s.vehiculosAnteriores = unidades;
                historia(s, minuto, "PLAN asignado=" + cantidad + " banco=" + banco
                        + " vehiculos=" + unidades + " minHastaLimite=" + instancia.pedidoHolgura(p));
            }
            if (cantidad + banco != instancia.pedidoCantidad(p)) {
                log("ADVERTENCIA CONSERVACION pedido=" + id + " pendiente="
                        + instancia.pedidoCantidad(p) + " asignado=" + cantidad + " banco=" + banco);
            }
            if (banco > 0 && diagnosticarBanco) diagnosticar(minuto, instancia, p, banco, sinEntregas, s);
        }
        planes.flush();
        eventos.flush();
    }

    private void diagnosticar(long minuto, InstanciaPlanificacion instancia, int p, int banco,
                              List<Integer> sinEntregas, Seguimiento seguimientoPedido) {
        if (sinEntregas.isEmpty()) {
            registrarDiagnostico(minuto, instancia.pedidoId(p), seguimientoPedido,
                    "SIN_VEHICULOS_SIN_ENTREGAS en la fotografia; no prueba imposibilidad de insercion o intercambio");
            return;
        }
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        for (int u : sinEntregas) {
            int cantidad = Math.min(banco, instancia.unidadCapacidad(u));
            if (cantidad <= 0) continue;
            Programacion prueba = programador.programar(u, new int[] {p}, new int[] {cantidad}, 1, false);
            String detalle = "vehiculo=" + instancia.unidadCodigo(u) + " cantidadProbada=" + cantidad;
            if (prueba.sinProgramacion()) {
                detalle = "SIN_PROGRAMACION " + detalle
                        + "; el decodificador no distingue aqui entre conectividad, inventario y otras restricciones";
            } else if (!prueba.factible()) {
                detalle = "DESFASE_TEMPORAL " + detalle + " desfaseMin=" + prueba.desfaseMinutos();
            } else {
                detalle = "ALTERNATIVA_INDIVIDUAL_FACTIBLE " + detalle
                        + (cantidad < banco ? " (solo una parte del remanente)" : " (todo el remanente)")
                        + "; no se descuenta inventario reservado por otras rutas";
            }
            registrarDiagnostico(minuto, instancia.pedidoId(p), seguimientoPedido, detalle);
        }
    }

    private void registrarDiagnostico(long minuto, int id, Seguimiento s, String detalle) {
        log("  DIAGNOSTICO pedido=" + id + " " + detalle);
        if (s != null) historia(s, minuto, detalle);
    }

    @Override
    public void alDescargarPedido(long minuto, int id, String unidad, int cantidad, int pendiente) {
        paquetes += cantidad;
        Seguimiento s = seguimiento.get(id);
        if (s != null) {
            s.entregado += cantidad;
            historia(s, minuto, "DESCARGA_REAL vehiculo=" + unidad + " cantidad=" + cantidad
                    + " restante=" + pendiente);
        }
        evento(minuto, "DESCARGA_REAL", id, unidad, cantidad, pendiente, "");
    }

    @Override
    public void alEntregarPedido(long minuto, int id, String unidad, int cantidad,
                                  long desdeRegistro, int plazoHoras) {
        completados++;
        Seguimiento s = seguimiento.get(id);
        if (s != null) s.completadoEn = minuto;
        // No sumar cantidad: las descargas ya se registraron por separado.
        evento(minuto, "PEDIDO_COMPLETADO", id, unidad, cantidad, 0,
                "minutosDesdeRegistro=" + desdeRegistro + " plazoHoras=" + plazoHoras);
    }

    @Override
    public void alIncumplirPedido(long minuto, int id, int restante) {
        incumplidos++;
        Seguimiento s = seguimiento.get(id);
        if (s != null) {
            s.incumplido = true;
            historia(s, minuto, "INCUMPLIMIENTO restante=" + restante);
        }
        evento(minuto, "INCUMPLIMIENTO", id, "", 0, restante, "Plazo vencido sin completar");
    }

    @Override
    public void alAveriarse(long minuto, String unidad, int tipo, int x, int y, int carga) {
        String detalle = "tipo=" + tipo + " posicion=(" + x + "," + y + ") carga=" + carga;
        evento(minuto, "AVERIA", -1, unidad, 0, 0, detalle);
        // La ultima asignacion vincula una incidencia; no demuestra causalidad.
        for (Seguimiento s : seguimiento.values()) {
            if (s.completadoEn == null && !s.incumplido && s.vehiculosAnteriores != null
                    && List.of(s.vehiculosAnteriores.split("\\|")).contains(unidad)) {
                historia(s, minuto, "AVERIA_EN_ULTIMO_VEHICULO_ASIGNADO " + unidad + " " + detalle);
            }
        }
    }

    @Override
    public void alIniciarReplanificacion(long minuto, String motivos) {
        evento(minuto, "REPLANIFICACION_INICIADA", -1, "", 0, 0, motivos);
        log("\nSOLICITUD DE PLAN " + instante(minuto) + " motivos=" + motivos);
    }

    @Override
    public void alDespacharRescate(long minuto, String averiada, String rescate,
            long llegada, long fin, int carga, int libre) {
        registrarRescate(minuto, "RESCATE_DESPACHADO", averiada, rescate, 0,
                "llegadaPrevista=" + instante(llegada) + " finPrevisto=" + instante(fin)
                + " cargaAveriada=" + carga + " capacidadLibre=" + libre);
    }

    @Override
    public void alNoAsignarRescate(long minuto, String averiada, String motivo) {
        registrarRescate(minuto, "RESCATE_NO_ASIGNADO", averiada, "", 0, motivo);
    }

    @Override
    public void alIniciarTrasvase(long minuto, String averiada, String rescate, long fin) {
        registrarRescate(minuto, "TRASVASE_INICIADO", averiada, rescate, 0,
                "finPrevisto=" + instante(fin));
    }

    @Override
    public void alCompletarTrasvase(long minuto, String averiada, String rescate,
            int cantidad, int cargaAveriada, int cargaRescate) {
        registrarRescate(minuto, "TRASVASE_COMPLETADO", averiada, rescate, cantidad,
                "cargaAveriada=" + cargaAveriada + " cargaRescate=" + cargaRescate);
    }

    private void registrarRescate(long minuto, String tipo, String averiada, String rescate,
            int cantidad, String detalle) {
        String texto = "averiada=" + averiada + " rescate=" + rescate + " " + detalle;
        evento(minuto, tipo, -1, rescate, cantidad, 0, texto);
        log(instante(minuto) + " " + tipo + " cantidad=" + cantidad + " " + texto);
    }

    /** Invocar despues de ejecutar(), antes de cerrar el observador. */
    public void generarReporte(ResultadoSimulacion resultado) throws IOException {
        if (finalizado) throw new IllegalStateException("El reporte ya fue finalizado");
        planes.flush();
        eventos.flush();
        if (planes.checkError() || eventos.checkError()) throw new IOException("Fallo escribiendo trazas en " + directorio);
        try (PrintWriter tabla = abrir("pedidos.csv"); PrintWriter diagnostico = abrir("diagnostico.txt")) {
            tabla.println("pedido,cliente,x,y,registro,limite,cantidad,entregado,restante,estado,completadoEn,planes,enBanco,sinAsignacion,cambiosAsignacion");
            diagnostico.println("REPORTE FINAL " + resultado.estado() + " " + resultado.fechaHoraFinal());
            diagnostico.println("Configuracion: " + resultado.configuracion());
            diagnostico.println("Resumen: " + resultado.metricas());
            for (String aviso : datos.avisos()) diagnostico.println("AVISO DE CARGA: " + aviso);
            boolean coherente = falloObservador == null
                    && replanificaciones == resultado.metricas().ejecucionesPlanificador()
                    && paquetes == resultado.metricas().unidadesEntregadas()
                    && completados == resultado.metricas().pedidosEntregados()
                    && incumplidos == resultado.metricas().pedidosIncumplidos();
            diagnostico.println("Eventos coinciden con metricas: " + coherente);
            if (falloObservador != null) {
                diagnostico.println("ERROR DEL OBSERVADOR: " + falloObservador);
                falloObservador.printStackTrace(diagnostico);
            }
            diagnostico.println("Planes=" + replanificaciones + " paquetes=" + paquetes
                    + " completados=" + completados + " incumplidos=" + incumplidos);
            diagnostico.println("Primer incumplimiento: pedido=" + resultado.pedidoDelPrimerIncumplimiento()
                    + " minuto=" + resultado.minutoPrimerIncumplimiento());
            diagnostico.println("\nPEDIDOS COMPLETADOS REALMENTE");
            if (consola) System.out.println("\nPEDIDOS COMPLETADOS REALMENTE");
            for (Seguimiento s : seguimiento.values()) {
                Pedido p = s.pedido;
                String estado = s.completadoEn != null ? "ENTREGADO" : s.incumplido ? "INCUMPLIDO"
                        : p.minutoRegistro() <= resultado.minutoFinal() ? "PENDIENTE" : "NO_REGISTRADO";
                tabla.printf(Locale.ROOT, "%d,%s,%d,%d,%s,%s,%d,%d,%d,%s,%s,%d,%d,%d,%d%n",
                        p.id(), csv(p.idCliente()), Ciudad.x(p.nodoDestino()), Ciudad.y(p.nodoDestino()),
                        csv(instante(p.minutoRegistro())), csv(instante(p.minutoLimite())), p.cantidad(),
                        s.entregado, p.cantidad() - s.entregado, estado,
                        csv(s.completadoEn == null ? "" : instante(s.completadoEn)),
                        s.planes, s.enBanco, s.sinAsignacion, s.cambios);
                if (s.completadoEn != null) {
                    String entrega = "Pedido " + p.id() + " cantidad=" + s.entregado
                            + " completado=" + instante(s.completadoEn);
                    diagnostico.println(entrega);
                    if (consola) System.out.println(entrega);
                }
            }
            diagnostico.println("\nHISTORIAL DE PEDIDOS INCUMPLIDOS");
            for (Seguimiento s : seguimiento.values()) {
                if (!s.incumplido) continue;
                diagnostico.println("\nPedido " + s.pedido.id() + " destino=" + Ciudad.texto(s.pedido.nodoDestino())
                        + " registro=" + instante(s.pedido.minutoRegistro()) + " limite=" + instante(s.pedido.minutoLimite())
                        + " cantidad=" + s.pedido.cantidad() + " entregado=" + s.entregado
                        + " planes=" + s.planes + " banco=" + s.enBanco + " cambios=" + s.cambios);
                diagnostico.print(s.historia);
            }
            diagnostico.println("\nLIMITES DE INTERPRETACION");
            diagnostico.println("ALTERNATIVA_INDIVIDUAL_FACTIBLE: el decodificador admite esa ruta aislada; no certifica inventario conjunto.");
            diagnostico.println("SIN_PROGRAMACION: motivo agregado; no atribuir automaticamente a capacidad o inventario.");
            diagnostico.println("DESFASE_TEMPORAL: incumple restricciones temporales en la prueba aislada.");
            diagnostico.println("No encontrar alternativas aqui no demuestra que una insercion, intercambio o reparto adicional sea imposible.");
            diagnostico.println("Los cambios de asignacion y averias son evidencia temporal, no una prueba automatica de causalidad.");
            diagnostico.println("El registro y las pruebas consumen tiempo real fuera del presupuesto del algoritmo.");
            diagnostico.println("Los planes son los devueltos al comprometer: el motor puede agregar retornos obligatorios sin entregas.");
            if (tabla.checkError() || diagnostico.checkError()) throw new IOException("Fallo escribiendo resumen");
            if (!coherente) throw new IOException("Instrumentacion incompleta: revisar diagnostico.txt");
        }
        finalizado = true;
        System.out.println("Reporte generado: " + directorio);
    }

    private PrintWriter abrir(String nombre) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(directorio.resolve(nombre), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE));
    }
    private String instante(long minuto) { return datos.calendario().aFecha(minuto).toString(); }
    private void historia(Seguimiento s, long minuto, String detalle) {
        s.historia.append("min=").append(minuto).append(" ").append(instante(minuto))
                .append(" ").append(detalle).append('\n');
    }
    private void log(String texto) { planes.println(texto); if (consola) System.out.println(texto); }
    private void evento(long minuto, String tipo, int id, String unidad, int cantidad, int restante, String detalle) {
        eventos.printf(Locale.ROOT, "%d,%s,%s,%d,%s,%d,%d,%s%n", minuto, csv(instante(minuto)),
                tipo, id, csv(unidad), cantidad, restante, csv(detalle));
        eventos.flush();
    }
    private static String csv(String texto) { return "\"" + texto.replace("\"", "\"\"") + "\""; }
    @Override public void close() { planes.close(); eventos.close(); }
}
