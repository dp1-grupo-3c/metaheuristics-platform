package org.kindbox.service.configuracion;

import java.time.LocalDate;
import org.kindbox.core.metaheuristica.FabricaAlgoritmos;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros del servicio, con prefijo {@code kindbox} en {@code application.properties}.
 *
 * <p>Recoge lo que la pantalla de inicio y configuracion del prototipo ofrece como valores
 * por defecto: el directorio de datos del equipo docente, el dia de arranque, la duracion
 * objetivo de la corrida de cinco dias (entre 30 y 60 minutos reales segun el enunciado) y
 * el salto de planificacion del que se deriva el presupuesto de computo del apartado 2.3
 * del ISA.</p>
 *
 * <p>Los umbrales del semaforo del requisito no funcional (d) no viven aqui sino en
 * {@code ParametrosOperacion}, porque son modificables en caliente y el nucleo los consulta
 * en cada iteracion de planificacion. Estos son solo los valores con los que arranca.</p>
 */
@ConfigurationProperties(prefix = "kindbox")
public class PropiedadesKindBox {

    /** Directorio raiz de los datos del equipo docente. */
    private String directorioDatos = "data";

    /** Primer dia del escenario cuando la peticion no lo indica. */
    private LocalDate primerDiaPorDefecto = LocalDate.of(2026, 9, 1);

    /** Dias que cubre la simulacion cuando la peticion no da fecha de fin. */
    private int diasPorDefecto = 5;

    /** Duracion objetivo de la corrida en minutos de reloj real. El enunciado pide de 30 a 60. */
    private int duracionMinutosPorDefecto = 30;

    /** Salto SA entre dos ejecuciones del planificador, en minutos simulados. */
    private int saltoMinutosPorDefecto = 30;

    /** Cadencia de las fotografias que recibe el visualizador, en minutos simulados. */
    private int minutosEntreFotografiasPorDefecto = 5;

    /** Algoritmo del planificador cuando la peticion no lo indica. */
    private String algoritmoPorDefecto = "ALNS";

    /** Semilla del generador de la corrida, que la hace reproducible. */
    private long semillaPorDefecto = 20262L;

    /** Si el motor genera averias por reglas ademas de las registradas desde el visualizador. */
    private boolean generarAveriasPorDefecto = true;

    /** Probabilidad de que una unidad se averie durante un turno. */
    private double averiasPorUnidadPorTurno = 0.02;

    /** Corridas terminadas que se conservan en memoria para consulta posterior. */
    private int corridasEnMemoria = 20;

    /** Fotografias que caben en la cola de retransmision antes de descartar la mas antigua. */
    private int capacidadColaDifusion = 32;

    /** Tamano de pagina por defecto de la tabla de pedidos. */
    private int tamanoPaginaPedidos = 25;

    /** Tamano maximo de pagina admitido en la tabla de pedidos. */
    private int tamanoMaximoPaginaPedidos = 500;

    public String getDirectorioDatos() {
        return directorioDatos;
    }

    public void setDirectorioDatos(String directorioDatos) {
        this.directorioDatos = directorioDatos;
    }

    public LocalDate getPrimerDiaPorDefecto() {
        return primerDiaPorDefecto;
    }

    public void setPrimerDiaPorDefecto(LocalDate primerDiaPorDefecto) {
        this.primerDiaPorDefecto = primerDiaPorDefecto;
    }

    public int getDiasPorDefecto() {
        return diasPorDefecto;
    }

    public void setDiasPorDefecto(int diasPorDefecto) {
        this.diasPorDefecto = diasPorDefecto;
    }

    public int getDuracionMinutosPorDefecto() {
        return duracionMinutosPorDefecto;
    }

    public void setDuracionMinutosPorDefecto(int duracionMinutosPorDefecto) {
        this.duracionMinutosPorDefecto = duracionMinutosPorDefecto;
    }

    public int getSaltoMinutosPorDefecto() {
        return saltoMinutosPorDefecto;
    }

    public void setSaltoMinutosPorDefecto(int saltoMinutosPorDefecto) {
        this.saltoMinutosPorDefecto = saltoMinutosPorDefecto;
    }

    public int getMinutosEntreFotografiasPorDefecto() {
        return minutosEntreFotografiasPorDefecto;
    }

    public void setMinutosEntreFotografiasPorDefecto(int minutosEntreFotografiasPorDefecto) {
        this.minutosEntreFotografiasPorDefecto = minutosEntreFotografiasPorDefecto;
    }

    public String getAlgoritmoPorDefecto() {
        return algoritmoPorDefecto;
    }

    public void setAlgoritmoPorDefecto(String algoritmoPorDefecto) {
        this.algoritmoPorDefecto = algoritmoPorDefecto;
    }

    public long getSemillaPorDefecto() {
        return semillaPorDefecto;
    }

    public void setSemillaPorDefecto(long semillaPorDefecto) {
        this.semillaPorDefecto = semillaPorDefecto;
    }

    public boolean isGenerarAveriasPorDefecto() {
        return generarAveriasPorDefecto;
    }

    public void setGenerarAveriasPorDefecto(boolean generarAveriasPorDefecto) {
        this.generarAveriasPorDefecto = generarAveriasPorDefecto;
    }

    public double getAveriasPorUnidadPorTurno() {
        return averiasPorUnidadPorTurno;
    }

    public void setAveriasPorUnidadPorTurno(double averiasPorUnidadPorTurno) {
        this.averiasPorUnidadPorTurno = averiasPorUnidadPorTurno;
    }

    public int getCorridasEnMemoria() {
        return corridasEnMemoria;
    }

    public void setCorridasEnMemoria(int corridasEnMemoria) {
        this.corridasEnMemoria = corridasEnMemoria;
    }

    public int getCapacidadColaDifusion() {
        return capacidadColaDifusion;
    }

    public void setCapacidadColaDifusion(int capacidadColaDifusion) {
        this.capacidadColaDifusion = capacidadColaDifusion;
    }

    public int getTamanoPaginaPedidos() {
        return tamanoPaginaPedidos;
    }

    public void setTamanoPaginaPedidos(int tamanoPaginaPedidos) {
        this.tamanoPaginaPedidos = tamanoPaginaPedidos;
    }

    public int getTamanoMaximoPaginaPedidos() {
        return tamanoMaximoPaginaPedidos;
    }

    public void setTamanoMaximoPaginaPedidos(int tamanoMaximoPaginaPedidos) {
        this.tamanoMaximoPaginaPedidos = tamanoMaximoPaginaPedidos;
    }

    /**
     * Valida la configuracion efectiva antes de arrancar componentes que la consumen.
     * Los avisos de datos del escenario siguen siendo tolerantes, pero una configuracion
     * imposible debe impedir el arranque para no producir corridas ambiguas.
     */
    public void validar() {
        if (directorioDatos == null || directorioDatos.isBlank()) {
            throw new IllegalArgumentException("kindbox.directorio-datos no puede estar vacio");
        }
        if (primerDiaPorDefecto == null) {
            throw new IllegalArgumentException("kindbox.primer-dia-por-defecto es obligatorio");
        }
        exigirPositivo(diasPorDefecto, "kindbox.dias-por-defecto");
        exigirPositivo(duracionMinutosPorDefecto, "kindbox.duracion-minutos-por-defecto");
        exigirPositivo(saltoMinutosPorDefecto, "kindbox.salto-minutos-por-defecto");
        exigirPositivo(minutosEntreFotografiasPorDefecto,
                "kindbox.minutos-entre-fotografias-por-defecto");
        if (algoritmoPorDefecto == null || algoritmoPorDefecto.isBlank()) {
            throw new IllegalArgumentException("kindbox.algoritmo-por-defecto es obligatorio");
        }
        FabricaAlgoritmos.canonico(algoritmoPorDefecto);
        if (!Double.isFinite(averiasPorUnidadPorTurno)
                || averiasPorUnidadPorTurno < 0.0 || averiasPorUnidadPorTurno > 1.0) {
            throw new IllegalArgumentException(
                    "kindbox.averias-por-unidad-por-turno debe estar entre 0 y 1");
        }
        exigirPositivo(corridasEnMemoria, "kindbox.corridas-en-memoria");
        exigirPositivo(capacidadColaDifusion, "kindbox.capacidad-cola-difusion");
        exigirPositivo(tamanoPaginaPedidos, "kindbox.tamano-pagina-pedidos");
        exigirPositivo(tamanoMaximoPaginaPedidos, "kindbox.tamano-maximo-pagina-pedidos");
        if (tamanoPaginaPedidos > tamanoMaximoPaginaPedidos) {
            throw new IllegalArgumentException(
                    "kindbox.tamano-pagina-pedidos no puede superar el tamano-maximo-pagina-pedidos");
        }
    }

    private static void exigirPositivo(int valor, String propiedad) {
        if (valor <= 0) {
            throw new IllegalArgumentException(propiedad + " debe ser positivo: " + valor);
        }
    }
}
