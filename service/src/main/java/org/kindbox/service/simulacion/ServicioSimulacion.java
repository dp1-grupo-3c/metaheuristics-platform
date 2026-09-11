package org.kindbox.service.simulacion;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.kindbox.core.io.LectorAverias;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.simulacion.ColorSemaforo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.InstantaneaSimulacion;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;
import org.kindbox.core.simulacion.VistaAlmacen;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.kindbox.service.dto.DetalleCorrida;
import org.kindbox.service.dto.FilaPedido;
import org.kindbox.service.dto.MensajeVisualizador;
import org.kindbox.service.dto.PaginaPedidos;
import org.kindbox.service.dto.RespuestaAveria;
import org.kindbox.service.dto.ResultadoCargaAverias;
import org.kindbox.service.dto.ResumenCorrida;
import org.kindbox.service.dto.SolicitudSimulacion;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.io.AnalizadorAverias;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ciclo de vida de las corridas de simulacion.
 *
 * <p>Arranca cada corrida en un hilo propio, la mantiene en un registro por identificador y
 * la cancela o la limpia cuando toca. <b>Solo admite una corrida activa a la vez</b>: es una
 * decision explicita y no una limitacion tecnica. El escenario de cinco dias del enunciado
 * debe ejecutarse en entre 30 y 60 minutos reales, lo que solo se sostiene si el planificador
 * dispone del equipo entero durante su presupuesto por iteracion; dos corridas simultaneas
 * competirian por los mismos nucleos y ninguna de las dos cumpliria su plazo. Un intento de
 * arrancar una segunda corrida responde 409.</p>
 *
 * <p>Las corridas terminadas se conservan en memoria para que el visualizador pueda volver
 * sobre su resumen final; el registro se poda por antiguedad al superar el maximo
 * configurado.</p>
 */
@Service
public class ServicioSimulacion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioSimulacion.class);

    /** Cadencia con que se revisan las averias programadas por carga masiva, en milisegundos. */
    private static final long CADENCIA_DESPACHO_AVERIAS_MS = 250L;

    private final RepositorioDatos repositorio;
    private final ParametrosOperacion parametros;
    private final FabricaMotor fabrica;
    private final RegistroAlgoritmos algoritmos;
    private final PublicadorDeEstado publicador;
    private final PropiedadesKindBox propiedades;
    /** Segundo modo de arranque del apartado 7.3.5 del ISA, leido al arrancar el servicio. */
    private final boolean arranqueDesdePlanVigente;

    private final Map<String, Corrida> corridas = new ConcurrentHashMap<>();
    private final List<String> orden = new ArrayList<>();
    private final AtomicInteger contador = new AtomicInteger();

    private final ExecutorService ejecutor = Executors.newCachedThreadPool(tarea -> {
        Thread hilo = new Thread(tarea, "simulacion");
        hilo.setDaemon(true);
        return hilo;
    });
    private final ScheduledExecutorService despachador = Executors.newSingleThreadScheduledExecutor(tarea -> {
        Thread hilo = new Thread(tarea, "despachador-averias");
        hilo.setDaemon(true);
        return hilo;
    });

    public ServicioSimulacion(RepositorioDatos repositorio, ParametrosOperacion parametros,
                              FabricaMotor fabrica, RegistroAlgoritmos algoritmos,
                              PublicadorDeEstado publicador, PropiedadesKindBox propiedades) {
        this.repositorio = repositorio;
        this.parametros = parametros;
        this.fabrica = fabrica;
        this.algoritmos = algoritmos;
        this.publicador = publicador;
        this.propiedades = propiedades;
        // Se lee al construir el servicio y no en cada peticion: un valor mal escrito impide
        // arrancar, igual que una clave hgs.* o alns.* mal escrita en RegistroAlgoritmos.
        this.arranqueDesdePlanVigente =
                ConfiguracionEscenario.leerArranqueDesdePlanVigente(System.getProperties());
        this.despachador.scheduleWithFixedDelay(this::despacharAveriasProgramadas,
                CADENCIA_DESPACHO_AVERIAS_MS, CADENCIA_DESPACHO_AVERIAS_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void cerrar() {
        for (Corrida corrida : corridas.values()) {
            if (corrida.enCurso() && corrida.motor() != null) {
                corrida.motor().cancelar();
            }
        }
        despachador.shutdownNow();
        ejecutor.shutdownNow();
    }

    // ------------------------------------------------------------ arranque

    /**
     * Arranca una corrida con la configuracion pedida, completando con los valores por
     * defecto lo que la peticion no traiga.
     *
     * <p>El modo de arranque de cada replanificacion, constructiva o plan vigente, no viaja en
     * la peticion: se toma de la propiedad de sistema
     * {@code arranqueDesdePlanVigente} con que se arranco el servicio, igual que los
     * parametros {@code hgs.*} y {@code alns.*}. Es una variante experimental de los apartados
     * 7.3.5 y 11.4 del ISA y no una opcion de la pantalla de configuracion.</p>
     *
     * @throws ConflictoDeEstado si ya hay una corrida en curso
     * @throws SolicitudInvalida si algun parametro no es admisible
     * @throws ErrorDeDatos      si el escenario no se pudo leer del directorio de datos
     */
    public synchronized ResumenCorrida arrancar(SolicitudSimulacion solicitud) {
        Corrida activa = corridaActiva();
        if (activa != null) {
            throw new ConflictoDeEstado("Ya hay una simulacion en curso (" + activa.id()
                    + "). Cancelela antes de arrancar otra.");
        }
        SolicitudSimulacion peticion = solicitud == null
                ? new SolicitudSimulacion(null, null, null, null, null, null, null, null, null)
                : solicitud;

        TipoEscenario tipo = peticion.tipo() == null ? TipoEscenario.SIMULACION_5D : peticion.tipo();
        LocalDate primerDia = peticion.primerDia() == null
                ? propiedades.getPrimerDiaPorDefecto() : peticion.primerDia();
        LocalDate ultimoDia = peticion.ultimoDia() == null
                ? primerDia.plusDays(tipo == TipoEscenario.DIA_A_DIA ? 0 : propiedades.getDiasPorDefecto() - 1L)
                : peticion.ultimoDia();
        if (ultimoDia.isBefore(primerDia)) {
            throw new SolicitudInvalida("El rango de fechas esta invertido: " + primerDia
                    + " es posterior a " + ultimoDia);
        }
        int dias = (int) ChronoUnit.DAYS.between(primerDia, ultimoDia) + 1;
        int duracion = valorPositivo(peticion.duracionMinutosReales(),
                propiedades.getDuracionMinutosPorDefecto(), "la duracion en minutos reales");
        int salto = valorPositivo(peticion.saltoMinutos(),
                propiedades.getSaltoMinutosPorDefecto(), "el salto de planificacion");
        int cadencia = valorPositivo(peticion.minutosEntreFotografias(),
                propiedades.getMinutosEntreFotografiasPorDefecto(), "la cadencia de fotografias");
        String algoritmo = algoritmos.canonico(peticion.algoritmo() == null
                ? propiedades.getAlgoritmoPorDefecto() : peticion.algoritmo());
        long semilla = peticion.semilla() == null ? propiedades.getSemillaPorDefecto() : peticion.semilla();
        boolean generarAverias = peticion.generarAverias() == null
                ? propiedades.isGenerarAveriasPorDefecto() : peticion.generarAverias();

        double factor = tipo == TipoEscenario.DIA_A_DIA ? 1.0 : (double) dias * 1440.0 / duracion;
        ModoReloj reloj = tipo == TipoEscenario.COLAPSO ? ModoReloj.LIBRE : ModoReloj.ACOMPASADO;
        ConfiguracionEscenario configuracion = new ConfiguracionEscenario(tipo, primerDia, ultimoDia,
                salto, factor, reloj, algoritmo, semilla, cadencia, generarAverias,
                propiedades.getAveriasPorUnidadPorTurno(), arranqueDesdePlanVigente);

        RepositorioDatos.DatosEscenario datos;
        try {
            datos = repositorio.cargar(primerDia, ultimoDia);
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo leer el escenario desde " + repositorio.raiz()
                    + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida("Escenario invalido: " + e.getMessage(), e);
        }
        if (datos.pedidos().isEmpty()) {
            throw new SolicitudInvalida("El rango " + primerDia + " a " + ultimoDia
                    + " no tiene ningun pedido en " + repositorio.raiz()
                    + ". Revise el directorio de datos o elija otras fechas.");
        }

        String id = "sim-" + String.format(Locale.ROOT, "%03d", contador.incrementAndGet());
        Corrida corrida = new Corrida(id, configuracion, datos, duracion);
        Algoritmo planificador = algoritmos.crear(algoritmo, semilla);
        List<ObservadorSimulacion> observadores = List.of(corrida, publicador.observadorDe(id));
        MotorSimulacion motor = fabrica.crear(datos, configuracion, parametros, planificador, observadores);
        corrida.motor(motor);

        corridas.put(id, corrida);
        orden.add(id);
        podar();

        LOG.info("Arranca la corrida {}: {} del {} al {}, {} pedidos, algoritmo {}, K={}",
                id, tipo, primerDia, ultimoDia, datos.pedidos().size(), algoritmo, String.format("%.1f", factor));
        ejecutor.submit(() -> ejecutarCorrida(corrida));

        ResumenCorrida resumen = resumen(corrida);
        publicador.publicar(MensajeVisualizador.TIPO_CORRIDA, id, resumen);
        return resumen;
    }

    private void ejecutarCorrida(Corrida corrida) {
        Thread.currentThread().setName("simulacion-" + corrida.id());
        corrida.estado(EstadoCorrida.EN_CURSO);
        try {
            ResultadoSimulacion resultado = corrida.motor().ejecutar();
            LOG.info("Termina la corrida {}: {} en el minuto {} ({})", corrida.id(),
                    resultado.estado(), resultado.minutoFinal(), resultado.mensaje());
        } catch (RuntimeException e) {
            corrida.estado(EstadoCorrida.FALLIDA);
            LOG.error("La corrida {} fallo", corrida.id(), e);
        }
    }

    // ------------------------------------------------------------ consultas

    /** Corridas conocidas, de la mas reciente a la mas antigua. */
    public synchronized List<ResumenCorrida> listar() {
        List<ResumenCorrida> lista = new ArrayList<>(orden.size());
        for (int i = orden.size() - 1; i >= 0; i--) {
            Corrida corrida = corridas.get(orden.get(i));
            if (corrida != null) {
                lista.add(resumen(corrida));
            }
        }
        return lista;
    }

    /** Corrida en curso, o {@code null} si no hay ninguna. */
    public Corrida corridaActiva() {
        for (Corrida corrida : corridas.values()) {
            if (corrida.enCurso()) {
                return corrida;
            }
        }
        return null;
    }

    /** Corrida por identificador. */
    public Corrida requerir(String id) {
        Corrida corrida = corridas.get(id);
        if (corrida == null) {
            throw new RecursoNoEncontrado("No existe la corrida " + id);
        }
        return corrida;
    }

    /** Cabecera, fotografia actual y, si termino, resumen final. */
    public DetalleCorrida detalle(String id) {
        Corrida corrida = requerir(id);
        return new DetalleCorrida(resumen(corrida), corrida.instantanea(), corrida.resultado(),
                corrida.averiasAplicadas());
    }

    /** Indicadores acumulados de la corrida. */
    public MetricasSimulacion metricas(String id) {
        Corrida corrida = requerir(id);
        ResultadoSimulacion resultado = corrida.resultado();
        if (resultado != null) {
            return resultado.metricas();
        }
        InstantaneaSimulacion instantanea = corrida.instantanea();
        if (instantanea == null) {
            throw new ConflictoDeEstado("La corrida " + id + " aun no ha producido ninguna fotografia");
        }
        return instantanea.metricas();
    }

    /** Resumen final de la corrida, que alimenta el modal de fin de simulacion. */
    public ResultadoSimulacion resultado(String id) {
        Corrida corrida = requerir(id);
        ResultadoSimulacion resultado = corrida.resultado();
        if (resultado == null) {
            throw new ConflictoDeEstado("La corrida " + id + " sigue en curso; su resumen final "
                    + "estara disponible cuando termine");
        }
        return resultado;
    }

    /** Cancela la corrida, que es el boton Cancelar de la barra superior. */
    public ResumenCorrida cancelar(String id) {
        Corrida corrida = requerir(id);
        if (!corrida.enCurso()) {
            throw new ConflictoDeEstado("La corrida " + id + " ya termino con estado " + corrida.estado());
        }
        corrida.motor().cancelar();
        LOG.info("Cancelacion solicitada para la corrida {}", id);
        return resumen(corrida);
    }

    /**
     * Tabla de pedidos con buscador y paginacion.
     *
     * <p>Se muestran los pedidos ya registrados en el instante simulado en curso, porque un
     * pedido que aun no ha llegado no es visible para el operador. El buscador casa contra el
     * identificador, el cliente, las coordenadas del destino y el plazo.</p>
     *
     * @param id          corrida consultada
     * @param busqueda    texto del buscador, admite nulo
     * @param pagina      numero de pagina, empezando en cero
     * @param tamano      filas por pagina
     * @param soloActivos si se excluyen los pedidos ya entregados
     */
    public PaginaPedidos pedidos(String id, String busqueda, Integer pagina, Integer tamano, boolean soloActivos) {
        Corrida corrida = requerir(id);
        int numeroPagina = pagina == null ? 0 : pagina;
        if (numeroPagina < 0) {
            throw new SolicitudInvalida("El numero de pagina no puede ser negativo: " + numeroPagina);
        }
        int filasPorPagina = tamano == null ? propiedades.getTamanoPaginaPedidos() : tamano;
        if (filasPorPagina <= 0 || filasPorPagina > propiedades.getTamanoMaximoPaginaPedidos()) {
            throw new SolicitudInvalida("El tamano de pagina debe estar entre 1 y "
                    + propiedades.getTamanoMaximoPaginaPedidos() + ": " + filasPorPagina);
        }
        String filtro = busqueda == null ? "" : busqueda.trim().toLowerCase(Locale.ROOT);

        long minuto = minutoSimulado(corrida);
        ParametrosOperacion.Instantanea vigentes = parametros.instantanea();
        Set<Integer> entregados = corrida.entregados();

        List<FilaPedido> filtradas = new ArrayList<>();
        for (Pedido pedido : corrida.datos().pedidos()) {
            if (pedido.minutoRegistro() > minuto) {
                continue;
            }
            boolean entregado = entregados.contains(pedido.id());
            if (soloActivos && entregado) {
                continue;
            }
            if (!filtro.isEmpty() && !coincide(pedido, filtro)) {
                continue;
            }
            long holgura = pedido.holgura(minuto);
            filtradas.add(new FilaPedido(pedido.id(), pedido.idCliente(), pedido.cantidad(),
                    entregado ? 0 : pedido.cantidad(), pedido.x(), pedido.y(), pedido.plazoHoras(),
                    corrida.datos().calendario().aFecha(pedido.minutoRegistro()),
                    corrida.datos().calendario().aFecha(pedido.minutoLimite()),
                    holgura, entregado,
                    entregado ? ColorSemaforo.VERDE
                            : ColorSemaforo.deHolgura(holgura, pedido.plazoHoras() * 60L, vigentes)));
        }
        filtradas.sort(Comparator.comparingLong(FilaPedido::holguraMinutos).thenComparingInt(FilaPedido::id));

        int total = filtradas.size();
        int totalPaginas = total == 0 ? 0 : (total + filasPorPagina - 1) / filasPorPagina;
        int desde = Math.min(numeroPagina * filasPorPagina, total);
        int hasta = Math.min(desde + filasPorPagina, total);
        return new PaginaPedidos(filtradas.subList(desde, hasta), numeroPagina, filasPorPagina,
                total, totalPaginas, filtro, soloActivos);
    }

    private static boolean coincide(Pedido pedido, String filtro) {
        if (String.valueOf(pedido.id()).contains(filtro)) {
            return true;
        }
        if (pedido.idCliente().toLowerCase(Locale.ROOT).contains(filtro)) {
            return true;
        }
        String direccion = "(" + pedido.x() + "," + pedido.y() + ")";
        if (direccion.contains(filtro) || (pedido.x() + "," + pedido.y()).contains(filtro)) {
            return true;
        }
        return (pedido.plazoHoras() + "h").contains(filtro);
    }

    /** Estado y color de cada almacen: el de la corrida en curso, o el de reposo si no hay ninguna. */
    public List<VistaAlmacen> almacenes() {
        Corrida corrida = corridaActiva();
        if (corrida == null) {
            corrida = ultimaCorrida();
        }
        if (corrida != null && corrida.instantanea() != null) {
            return corrida.instantanea().almacenes();
        }
        ParametrosOperacion.Instantanea vigentes = parametros.instantanea();
        List<VistaAlmacen> vistas = new ArrayList<>();
        for (Almacen almacen : Almacen.todos()) {
            int disponible = almacen.central() ? -1 : almacen.capacidad();
            vistas.add(new VistaAlmacen(almacen.id(), almacen.nombre(), Ciudad.x(almacen.nodo()),
                    Ciudad.y(almacen.nodo()), almacen.central(), disponible,
                    almacen.central() ? -1 : almacen.capacidad(),
                    almacen.central() ? ColorSemaforo.VERDE
                            : ColorSemaforo.deInventario(almacen.capacidad(), vigentes)));
        }
        return vistas;
    }

    // -------------------------------------------------------------- averias

    /** Registro individual de una averia, tal como llega del panel lateral del visualizador. */
    public RespuestaAveria registrarAveria(String id, String placa, Integer tipoPedido) {
        Corrida corrida = requerir(id);
        exigirEnCurso(corrida);
        String codigo = placa == null ? "" : placa.trim().toUpperCase(Locale.ROOT);
        if (!codigosDeFlota(corrida).contains(codigo)) {
            throw new SolicitudInvalida("La unidad " + placa + " no pertenece a la flota del escenario");
        }
        if (tipoPedido == null) {
            throw new SolicitudInvalida("Falta el tipo de averia: debe ser 1, 2 o 3");
        }
        TipoAveria tipo;
        try {
            tipo = TipoAveria.porCodigo(tipoPedido);
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida("Tipo de averia invalido: " + tipoPedido + ". Debe ser 1, 2 o 3", e);
        }
        try {
            corrida.motor().registrarAveria(codigo, tipo);
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        long minuto = minutoSimulado(corrida);
        long reincorporacion = tipo.minutoReincorporacion(minuto);
        return new RespuestaAveria(codigo, tipo.codigo(), minuto,
                corrida.datos().calendario().aFecha(minuto), reincorporacion,
                "La unidad " + codigo + " queda inmovilizada por una averia de tipo " + tipo.codigo()
                        + " y vuelve a estar disponible el "
                        + corrida.datos().calendario().fechaTexto(reincorporacion));
    }

    /**
     * Carga masiva de averias desde un archivo con el formato {@code ##d##h##m:TTNN:T}.
     *
     * <p>Las que caen en un minuto ya recorrido se aplican de inmediato; las demas quedan en
     * la cola de la corrida y se aplican cuando el reloj simulado alcanza su instante.</p>
     *
     * @param id            corrida sobre la que se registran
     * @param nombreArchivo nombre con que se subio, que acompana a los mensajes de error
     * @param contenido     texto del archivo
     */
    public ResultadoCargaAverias cargarAverias(String id, String nombreArchivo, String contenido) {
        Corrida corrida = requerir(id);
        exigirEnCurso(corrida);
        if (contenido == null || contenido.isBlank()) {
            throw new SolicitudInvalida("El archivo de averias esta vacio");
        }
        AnalizadorAverias analizador = new AnalizadorAverias(corrida.datos().calendario(),
                YearMonth.from(corrida.datos().primerDia()), corrida.datos().minutoFin());
        AnalizadorAverias.Lectura lectura;
        try {
            lectura = analizador.analizar(contenido, nombreArchivo, codigosDeFlota(corrida));
        } catch (IllegalArgumentException e) {
            // El lector del nucleo se detiene en el primer registro mal formado e indica la
            // linea y el registro completo; ese mensaje es justo lo que el operador necesita.
            throw new SolicitudInvalida(e.getMessage(), e);
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo procesar el archivo de averias: " + e.getMessage(), e);
        }
        if (lectura.averias().isEmpty()) {
            throw new SolicitudInvalida("El archivo no trae ninguna averia valida. "
                    + "El formato de cada linea es ##d##h##m:TTNN:T. "
                    + String.join(" | ", lectura.avisos()));
        }
        long minuto = minutoSimulado(corrida);
        int aplicadas = 0;
        int programadas = 0;
        List<String> avisos = new ArrayList<>(lectura.avisos());
        for (LectorAverias.AveriaProgramada averia : lectura.averias()) {
            if (averia.minutoAveria() <= minuto) {
                try {
                    corrida.motor().registrarAveria(averia.codigoUnidad(), averia.tipo());
                    aplicadas++;
                } catch (IllegalArgumentException e) {
                    avisos.add(averia.codigoUnidad() + ": " + e.getMessage());
                }
            } else {
                corrida.averiasProgramadas().add(averia);
                programadas++;
            }
        }
        LOG.info("Carga masiva de averias en {}: {} aplicadas, {} programadas, {} avisos",
                id, aplicadas, programadas, avisos.size());
        return new ResultadoCargaAverias(lectura.averias().size(), aplicadas, programadas, avisos);
    }

    /** Aplica las averias programadas cuyo instante ya alcanzo el reloj simulado. */
    private void despacharAveriasProgramadas() {
        for (Corrida corrida : corridas.values()) {
            if (!corrida.enCurso() || corrida.motor() == null || corrida.averiasProgramadas().isEmpty()) {
                continue;
            }
            long minuto = minutoSimulado(corrida);
            LectorAverias.AveriaProgramada cabeza;
            while ((cabeza = corrida.averiasProgramadas().peek()) != null && cabeza.minutoAveria() <= minuto) {
                corrida.averiasProgramadas().poll();
                try {
                    corrida.motor().registrarAveria(cabeza.codigoUnidad(), cabeza.tipo());
                } catch (RuntimeException e) {
                    LOG.warn("No se pudo aplicar la averia programada de {}: {}",
                            cabeza.codigoUnidad(), e.toString());
                }
            }
        }
    }

    // ------------------------------------------------------------ internos

    /** Cabecera de la corrida, con todo lo que pinta la barra superior del visualizador. */
    public ResumenCorrida resumen(Corrida corrida) {
        InstantaneaSimulacion instantanea = corrida.instantanea();
        Map<String, Integer> activas = new LinkedHashMap<>();
        for (TipoUnidad tipo : TipoUnidad.values()) {
            activas.put(tipo.name(), instantanea == null ? 0 : instantanea.unidadesActivasDeTipo(tipo));
        }
        ConfiguracionEscenario configuracion = corrida.configuracion();
        ResultadoSimulacion resultado = corrida.resultado();
        long milisegundos = resultado != null ? resultado.milisegundosReales()
                : instantanea == null ? 0L : instantanea.milisegundosReales();
        return new ResumenCorrida(corrida.id(), configuracion.tipo(), configuracion.algoritmo(),
                corrida.estado(), configuracion.primerDia(), configuracion.ultimoDia(),
                corrida.duracionMinutosReales(), configuracion.saltoMinutos(),
                configuracion.factorAceleracion(), configuracion.minutosEntreFotografias(),
                configuracion.semilla(), instantanea == null ? 0L : instantanea.minutoSimulado(),
                corrida.datos().minutoFin(),
                instantanea == null ? corrida.datos().calendario().inicio() : instantanea.fechaHoraSimulada(),
                milisegundos, corrida.datos().pedidos().size(), corrida.datos().unidades().size(),
                activas, instantanea == null ? 0 : instantanea.metricas().unidadesEntregadas(),
                fabrica.descripcion(),
                corrida.datos().avisos());
    }

    /**
     * Instante simulado en curso, leido del motor mismo y no de la ultima fotografia.
     *
     * <p>La fotografia se emite con la cadencia configurada, de modo que puede ir varios
     * minutos simulados por detras del reloj del motor; una averia despachada contra ella se
     * aplicaria tarde. {@code minutoActual()} del motor no tiene ese desfase.</p>
     */
    private static long minutoSimulado(Corrida corrida) {
        if (corrida.motor() != null) {
            return corrida.motor().minutoActual();
        }
        InstantaneaSimulacion instantanea = corrida.instantanea();
        return instantanea == null ? 0L : instantanea.minutoSimulado();
    }

    private void exigirEnCurso(Corrida corrida) {
        if (!corrida.enCurso()) {
            throw new ConflictoDeEstado("La corrida " + corrida.id()
                    + " ya termino con estado " + corrida.estado() + "; no admite mas averias");
        }
    }

    private static Set<String> codigosDeFlota(Corrida corrida) {
        Set<String> codigos = new LinkedHashSet<>();
        for (UnidadTransporte unidad : corrida.datos().unidades()) {
            codigos.add(unidad.codigo());
        }
        return codigos;
    }

    private Corrida ultimaCorrida() {
        synchronized (this) {
            for (int i = orden.size() - 1; i >= 0; i--) {
                Corrida corrida = corridas.get(orden.get(i));
                if (corrida != null) {
                    return corrida;
                }
            }
        }
        return null;
    }

    /** Retira las corridas terminadas mas antiguas cuando el registro supera el maximo. */
    private void podar() {
        int maximo = Math.max(1, propiedades.getCorridasEnMemoria());
        int i = 0;
        while (orden.size() > maximo && i < orden.size()) {
            String candidato = orden.get(i);
            Corrida corrida = corridas.get(candidato);
            if (corrida == null || !corrida.enCurso()) {
                orden.remove(i);
                corridas.remove(candidato);
            } else {
                i++;
            }
        }
    }

    private static int valorPositivo(Integer valor, int porDefecto, String que) {
        int elegido = valor == null ? porDefecto : valor;
        if (elegido <= 0) {
            throw new SolicitudInvalida("El valor de " + que + " debe ser positivo: " + elegido);
        }
        return elegido;
    }
}
