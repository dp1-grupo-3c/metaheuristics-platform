package org.kindbox.core.configuracion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.simulacion.GeneradorAverias;

/**
 * Parametros del mundo simulado que el concepto de evaluacion CONFIGURACION exige poder
 * establecer desde un archivo de parametros o equivalente: el tamano del mapa, los
 * almacenes, la flota, las averias, el semaforo, los turnos, los plazos y los tiempos de
 * servicio.
 *
 * <p>Es un valor inmutable. El servicio guarda la version vigente y cada corrida toma la
 * suya al arrancar, de modo que un cambio entre simulaciones nunca alcanza a una corrida
 * en curso y cada resultado queda asociado a los parametros con que se obtuvo.</p>
 *
 * <p>La validacion es del conjunto entero y reune todos los errores en lugar de detenerse
 * en el primero, para que el archivo o el panel de configuracion puedan mostrarlos de una
 * sola vez. Los registros anidados no validan por su cuenta: un parametro solo tiene
 * sentido junto con los demas (un almacen esta dentro o fuera segun el tamano del mapa).</p>
 *
 * <p>Durante la migracion, {@link #delEnunciado()} se construye a partir de las constantes
 * que hoy usa el nucleo, de modo que ambos caminos coinciden mientras convivan.</p>
 *
 * @param mapa      dimensiones de la ciudad
 * @param almacenes almacen central e intermedios
 * @param flota     ficha de cada tipo de unidad de transporte
 * @param averias   reparto y efecto de cada tipo de averia
 * @param semaforo  cortes del semaforo de ocupacion
 * @param turnos    cambios de turno del dia
 * @param plazos    plazos de entrega comprometidos
 * @param tiempos   tiempos de servicio de la operacion
 */
public record ParametrosMundo(
        Mapa mapa,
        List<AlmacenParam> almacenes,
        List<TipoUnidadParam> flota,
        List<TipoAveriaParam> averias,
        Semaforo semaforo,
        Turnos turnos,
        Plazos plazos,
        Tiempos tiempos) {

    /** Lado maximo admitido del mapa, en km. Acota el tamano del grafo de la ciudad. */
    public static final int LADO_MAXIMO_KM = 500;

    public ParametrosMundo {
        almacenes = copia(almacenes);
        flota = copia(flota);
        averias = copia(averias);
        List<String> errores = errores(mapa, almacenes, flota, averias, semaforo, turnos, plazos, tiempos);
        if (!errores.isEmpty()) {
            throw new ParametrosMundoInvalidos(errores);
        }
    }

    // ---- Secciones -------------------------------------------------------------------

    /**
     * Dimensiones de la ciudad. Los nodos van de (0,0) a (largoKm, anchoKm), un nodo por km.
     *
     * @param largoKm extension en el eje X
     * @param anchoKm extension en el eje Y
     */
    public record Mapa(int largoKm, int anchoKm) {
    }

    /**
     * Almacen de la red.
     *
     * @param id        identificador unico
     * @param nombre    nombre para presentacion
     * @param x         coordenada X en km
     * @param y         coordenada Y en km
     * @param central   si es el almacen central, de inventario ilimitado
     * @param capacidad capacidad en paquetes; {@code null} en el central
     */
    public record AlmacenParam(int id, String nombre, int x, int y, boolean central, Integer capacidad) {
    }

    /**
     * Ficha de un tipo de unidad de transporte.
     *
     * <p>El prefijo debe ser uno de los de {@link TipoUnidad}: agregar tipos nuevos exige
     * antes que el tipo deje de ser un enumerado.</p>
     *
     * @param prefijo       prefijo TT de los codigos de unidad
     * @param etiqueta      nombre para presentacion
     * @param cantidad      unidades de este tipo en la flota
     * @param capacidad     capacidad en paquetes
     * @param velocidadKmH  velocidad inicial; luego se modifica en caliente
     * @param costoPorKm    costo de operacion en soles por km
     * @param factorAveria  multiplicador de la probabilidad de averia por turno
     */
    public record TipoUnidadParam(String prefijo, String etiqueta, int cantidad, int capacidad,
                                  double velocidadKmH, double costoPorKm, double factorAveria) {
    }

    /** Regla con que una unidad averiada vuelve a estar disponible. */
    public enum ReglaReincorporacion {
        /** Tras {@code minutosIndisponible} minutos desde la averia. */
        TRAS_MINUTOS,
        /** Al cierre del turno siguiente al de la averia. */
        FIN_TURNO_SIGUIENTE,
        /** Tras {@code diasMantenimiento} dias, al inicio del siguiente {@code turnoReingreso}. */
        DIAS_Y_TURNO
    }

    /**
     * Tipo de averia.
     *
     * @param tipo                   codigo 1, 2 o 3
     * @param porcentaje             parte de las averias que son de este tipo; suman 100
     * @param minutosEnElLugar       tiempo inmovilizada en el punto del incidente
     * @param trasladaAlmacenCentral si al cumplirse ese tiempo la unidad y su carga pasan al central
     * @param reincorporacion        regla de vuelta a la planificacion
     * @param minutosIndisponible    solo con {@link ReglaReincorporacion#TRAS_MINUTOS}
     * @param diasMantenimiento      solo con {@link ReglaReincorporacion#DIAS_Y_TURNO}
     * @param turnoReingreso         solo con {@link ReglaReincorporacion#DIAS_Y_TURNO}
     */
    public record TipoAveriaParam(int tipo, int porcentaje, int minutosEnElLugar, boolean trasladaAlmacenCentral,
                                  ReglaReincorporacion reincorporacion, Integer minutosIndisponible,
                                  Integer diasMantenimiento, Turno turnoReingreso) {
    }

    /**
     * Cortes del semaforo, en porcentaje de la capacidad. Se expresan en porcentaje y no en
     * paquetes para que sigan siendo validos si cambia la capacidad de un almacen o de una
     * unidad. Vacio es el estado con cero paquetes y no necesita parametro.
     *
     * @param verdeDesdePorcentaje disponible igual o mayor a este porcentaje es verde
     * @param ambarDesdePorcentaje disponible igual o mayor a este porcentaje, y menor al verde, es ambar
     */
    public record Semaforo(int verdeDesdePorcentaje, int ambarDesdePorcentaje) {
    }

    /**
     * Cambios de turno, en minutos desde las 00:00, en el orden manana, tarde y noche.
     *
     * @param cambiosMinutoDia tres instantes crecientes dentro del dia
     */
    public record Turnos(List<Integer> cambiosMinutoDia) {
        public Turnos {
            cambiosMinutoDia = copia(cambiosMinutoDia);
        }
    }

    /**
     * Plazos de entrega comprometidos.
     *
     * @param estandarHoras    plazo de un pedido sin prioridad
     * @param priorizadosHoras plazos de los pedidos con prioridad, crecientes y menores al estandar
     */
    public record Plazos(int estandarHoras, List<Integer> priorizadosHoras) {
        public Plazos {
            priorizadosHoras = copia(priorizadosHoras);
        }
    }

    /**
     * Tiempos de servicio, en minutos.
     *
     * @param acondicionamiento         entrega al destinatario
     * @param alimentacion              pausa del conductor
     * @param separacionCambioTurno     separacion minima entre la pausa y un cambio de turno
     * @param trasvase                  trasvase de carga entre unidades
     * @param recargaIntermediosMinutoDia minuto del dia en que se reponen los intermedios
     */
    public record Tiempos(int acondicionamiento, int alimentacion, int separacionCambioTurno,
                          int trasvase, int recargaIntermediosMinutoDia) {
    }

    // ---- Valores del enunciado -----------------------------------------------------

    /** Parametros del enunciado, tomados de las constantes que hoy usa el nucleo. */
    public static ParametrosMundo delEnunciado() {
        List<AlmacenParam> almacenes = new ArrayList<>();
        for (Almacen a : Almacen.todos()) {
            almacenes.add(new AlmacenParam(a.id(), a.nombre(), Ciudad.x(a.nodo()), Ciudad.y(a.nodo()),
                    a.central(), a.central() ? null : a.capacidad()));
        }
        int[] cantidades = {10, 15, 12};
        List<TipoUnidadParam> flota = new ArrayList<>();
        for (TipoUnidad t : TipoUnidad.values()) {
            flota.add(new TipoUnidadParam(t.prefijo(), t.etiqueta(), cantidades[t.ordinal()], t.capacidad(),
                    t.velocidadPorDefecto(), t.costoPorKm(), GeneradorAverias.FACTORES_POR_DEFECTO[t.ordinal()]));
        }
        List<TipoAveriaParam> averias = List.of(
                new TipoAveriaParam(1, GeneradorAverias.PORCENTAJE_TIPO_1, 120, false,
                        ReglaReincorporacion.TRAS_MINUTOS, 120, null, null),
                new TipoAveriaParam(2, GeneradorAverias.PORCENTAJE_TIPO_2, 240, true,
                        ReglaReincorporacion.FIN_TURNO_SIGUIENTE, null, null, null),
                new TipoAveriaParam(3, GeneradorAverias.PORCENTAJE_TIPO_3, 240, true,
                        ReglaReincorporacion.DIAS_Y_TURNO, null, 2, Turno.TARDE));
        List<Integer> cambios = new ArrayList<>();
        for (int c : Turno.CAMBIOS_DE_TURNO) {
            cambios.add(c);
        }
        List<Integer> priorizados = new ArrayList<>();
        for (int h : Pedido.PLAZOS_PRIORIZADOS_HORAS) {
            priorizados.add(h);
        }
        return new ParametrosMundo(
                new Mapa(Ciudad.LARGO_KM, Ciudad.ANCHO_KM),
                almacenes,
                flota,
                averias,
                new Semaforo(50, 25),
                new Turnos(cambios),
                new Plazos(Pedido.PLAZO_ESTANDAR_HORAS, priorizados),
                new Tiempos(ParametrosOperacion.MINUTOS_ACONDICIONAMIENTO_POR_DEFECTO,
                        ParametrosOperacion.MINUTOS_ALIMENTACION_POR_DEFECTO,
                        ParametrosOperacion.MINUTOS_SEPARACION_CAMBIO_TURNO_POR_DEFECTO,
                        ParametrosOperacion.MINUTOS_TRASVASE_POR_DEFECTO,
                        Almacen.MINUTO_RECARGA_DIARIA));
    }

    /** Copia inmutable que, a diferencia de {@code List.copyOf}, admite nulos para poder reportarlos. */
    private static <T> List<T> copia(List<T> lista) {
        return lista == null ? null : Collections.unmodifiableList(new ArrayList<>(lista));
    }

    // ---- Validacion ----------------------------------------------------------------

    private static List<String> errores(Mapa mapa, List<AlmacenParam> almacenes, List<TipoUnidadParam> flota,
                                        List<TipoAveriaParam> averias, Semaforo semaforo, Turnos turnos,
                                        Plazos plazos, Tiempos tiempos) {
        List<String> e = new ArrayList<>();
        validarMapa(mapa, e);
        validarAlmacenes(almacenes, mapa, e);
        validarFlota(flota, e);
        validarAverias(averias, e);
        validarSemaforo(semaforo, e);
        validarTurnos(turnos, e);
        validarPlazos(plazos, e);
        validarTiempos(tiempos, e);
        return e;
    }

    private static void validarMapa(Mapa mapa, List<String> e) {
        if (mapa == null) {
            e.add("mapa: es obligatorio");
            return;
        }
        if (mapa.largoKm() < 1 || mapa.largoKm() > LADO_MAXIMO_KM) {
            e.add("mapa.largoKm: debe estar entre 1 y " + LADO_MAXIMO_KM + ", es " + mapa.largoKm());
        }
        if (mapa.anchoKm() < 1 || mapa.anchoKm() > LADO_MAXIMO_KM) {
            e.add("mapa.anchoKm: debe estar entre 1 y " + LADO_MAXIMO_KM + ", es " + mapa.anchoKm());
        }
    }

    private static void validarAlmacenes(List<AlmacenParam> almacenes, Mapa mapa, List<String> e) {
        if (almacenes == null || almacenes.isEmpty()) {
            e.add("almacenes: debe haber al menos el almacen central");
            return;
        }
        Set<Integer> ids = new HashSet<>();
        Set<String> nombres = new HashSet<>();
        Set<String> posiciones = new HashSet<>();
        int centrales = 0;
        for (int i = 0; i < almacenes.size(); i++) {
            AlmacenParam a = almacenes.get(i);
            String p = "almacenes[" + i + "]";
            if (a == null) {
                e.add(p + ": no puede ser nulo");
                continue;
            }
            if (!ids.add(a.id())) {
                e.add(p + ".id: el id " + a.id() + " esta repetido");
            }
            if (a.nombre() == null || a.nombre().isBlank()) {
                e.add(p + ".nombre: es obligatorio");
            } else if (!nombres.add(a.nombre().trim())) {
                e.add(p + ".nombre: '" + a.nombre() + "' esta repetido");
            }
            if (mapa != null && (a.x() < 0 || a.x() > mapa.largoKm() || a.y() < 0 || a.y() > mapa.anchoKm())) {
                e.add(p + ": la posicion (" + a.x() + "," + a.y() + ") cae fuera del mapa de "
                        + mapa.largoKm() + "x" + mapa.anchoKm() + " km");
            }
            if (!posiciones.add(a.x() + "," + a.y())) {
                e.add(p + ": ya hay otro almacen en (" + a.x() + "," + a.y() + ")");
            }
            if (a.central()) {
                centrales++;
                if (a.capacidad() != null) {
                    e.add(p + ".capacidad: el almacen central es ilimitado y no lleva capacidad");
                }
            } else if (a.capacidad() == null || a.capacidad() < 1) {
                e.add(p + ".capacidad: un almacen intermedio necesita una capacidad positiva");
            }
        }
        if (centrales != 1) {
            e.add("almacenes: debe haber exactamente un almacen central, hay " + centrales);
        }
    }

    private static void validarFlota(List<TipoUnidadParam> flota, List<String> e) {
        if (flota == null || flota.isEmpty()) {
            e.add("flota: es obligatoria");
            return;
        }
        Set<String> esperados = new HashSet<>();
        for (TipoUnidad t : TipoUnidad.values()) {
            esperados.add(t.prefijo());
        }
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < flota.size(); i++) {
            TipoUnidadParam t = flota.get(i);
            String p = "flota[" + i + "]";
            if (t == null) {
                e.add(p + ": no puede ser nulo");
                continue;
            }
            if (t.prefijo() == null || !esperados.contains(t.prefijo())) {
                e.add(p + ".prefijo: debe ser uno de " + esperados + ", es " + t.prefijo());
            } else if (!vistos.add(t.prefijo())) {
                e.add(p + ".prefijo: " + t.prefijo() + " esta repetido");
            }
            if (t.etiqueta() == null || t.etiqueta().isBlank()) {
                e.add(p + ".etiqueta: es obligatoria");
            }
            if (t.cantidad() < 0) {
                e.add(p + ".cantidad: no puede ser negativa");
            }
            if (t.capacidad() < 1) {
                e.add(p + ".capacidad: debe ser positiva");
            }
            if (!(t.velocidadKmH() > 0) || !Double.isFinite(t.velocidadKmH())) {
                e.add(p + ".velocidadKmH: debe ser positiva");
            }
            if (!(t.costoPorKm() >= 0) || !Double.isFinite(t.costoPorKm())) {
                e.add(p + ".costoPorKm: no puede ser negativo");
            }
            if (!(t.factorAveria() >= 0) || !Double.isFinite(t.factorAveria())) {
                e.add(p + ".factorAveria: no puede ser negativo");
            }
        }
        for (String prefijo : esperados) {
            if (!vistos.contains(prefijo)) {
                e.add("flota: falta el tipo " + prefijo);
            }
        }
        if (flota.stream().filter(t -> t != null).mapToInt(TipoUnidadParam::cantidad).sum() < 1) {
            e.add("flota: debe haber al menos una unidad");
        }
    }

    private static void validarAverias(List<TipoAveriaParam> averias, List<String> e) {
        if (averias == null || averias.isEmpty()) {
            e.add("averias: son obligatorias");
            return;
        }
        Set<Integer> tipos = new HashSet<>();
        int suma = 0;
        for (int i = 0; i < averias.size(); i++) {
            TipoAveriaParam a = averias.get(i);
            String p = "averias[" + i + "]";
            if (a == null) {
                e.add(p + ": no puede ser nulo");
                continue;
            }
            if (a.tipo() < 1 || a.tipo() > 3) {
                e.add(p + ".tipo: debe ser 1, 2 o 3, es " + a.tipo());
            } else if (!tipos.add(a.tipo())) {
                e.add(p + ".tipo: el tipo " + a.tipo() + " esta repetido");
            }
            if (a.porcentaje() < 0 || a.porcentaje() > 100) {
                e.add(p + ".porcentaje: debe estar entre 0 y 100");
            }
            suma += a.porcentaje();
            if (a.minutosEnElLugar() < 0) {
                e.add(p + ".minutosEnElLugar: no puede ser negativo");
            }
            if (a.reincorporacion() == null) {
                e.add(p + ".reincorporacion: es obligatoria");
                continue;
            }
            switch (a.reincorporacion()) {
                case TRAS_MINUTOS -> {
                    if (a.minutosIndisponible() == null || a.minutosIndisponible() < 1) {
                        e.add(p + ".minutosIndisponible: TRAS_MINUTOS necesita un valor positivo");
                    } else if (a.minutosIndisponible() < a.minutosEnElLugar()) {
                        e.add(p + ".minutosIndisponible: no puede ser menor que minutosEnElLugar");
                    }
                }
                case FIN_TURNO_SIGUIENTE -> {
                    // Sin parametros propios.
                }
                case DIAS_Y_TURNO -> {
                    if (a.diasMantenimiento() == null || a.diasMantenimiento() < 1) {
                        e.add(p + ".diasMantenimiento: DIAS_Y_TURNO necesita un valor positivo");
                    }
                    if (a.turnoReingreso() == null) {
                        e.add(p + ".turnoReingreso: DIAS_Y_TURNO necesita MANANA, TARDE o NOCHE");
                    }
                }
            }
        }
        for (int t = 1; t <= 3; t++) {
            if (!tipos.contains(t)) {
                e.add("averias: falta el tipo " + t);
            }
        }
        if (suma != 100) {
            e.add("averias: los porcentajes deben sumar 100, suman " + suma);
        }
    }

    private static void validarSemaforo(Semaforo s, List<String> e) {
        if (s == null) {
            e.add("semaforo: es obligatorio");
            return;
        }
        if (s.ambarDesdePorcentaje() < 1 || s.verdeDesdePorcentaje() > 100
                || s.ambarDesdePorcentaje() >= s.verdeDesdePorcentaje()) {
            e.add("semaforo: debe cumplirse 1 <= ambarDesdePorcentaje < verdeDesdePorcentaje <= 100, son "
                    + s.ambarDesdePorcentaje() + " y " + s.verdeDesdePorcentaje());
        }
    }

    private static void validarTurnos(Turnos t, List<String> e) {
        if (t == null || t.cambiosMinutoDia() == null) {
            e.add("turnos.cambiosMinutoDia: es obligatorio");
            return;
        }
        List<Integer> c = t.cambiosMinutoDia();
        if (c.size() != 3 || c.contains(null)) {
            e.add("turnos.cambiosMinutoDia: deben ser tres instantes (manana, tarde y noche)");
            return;
        }
        if (c.get(0) < 0 || c.get(2) >= 1440 || c.get(0) >= c.get(1) || c.get(1) >= c.get(2)) {
            e.add("turnos.cambiosMinutoDia: deben ser crecientes y estar en [0, 1440), son " + c);
        }
    }

    private static void validarPlazos(Plazos p, List<String> e) {
        if (p == null || p.priorizadosHoras() == null) {
            e.add("plazos: son obligatorios");
            return;
        }
        if (p.estandarHoras() < 1) {
            e.add("plazos.estandarHoras: debe ser positivo");
        }
        List<Integer> h = p.priorizadosHoras();
        if (h.isEmpty() || h.contains(null)) {
            e.add("plazos.priorizadosHoras: debe haber al menos un plazo y ninguno nulo");
            return;
        }
        for (int i = 0; i < h.size(); i++) {
            if (h.get(i) < 1 || (i > 0 && h.get(i) <= h.get(i - 1))) {
                e.add("plazos.priorizadosHoras: deben ser positivos y estrictamente crecientes, son " + h);
                break;
            }
        }
        if (h.get(h.size() - 1) >= p.estandarHoras()) {
            e.add("plazos.priorizadosHoras: todos deben ser menores que el plazo estandar de "
                    + p.estandarHoras() + " h");
        }
    }

    private static void validarTiempos(Tiempos t, List<String> e) {
        if (t == null) {
            e.add("tiempos: son obligatorios");
            return;
        }
        if (t.acondicionamiento() < 0) {
            e.add("tiempos.acondicionamiento: no puede ser negativo");
        }
        if (t.alimentacion() < 0) {
            e.add("tiempos.alimentacion: no puede ser negativo");
        }
        if (t.separacionCambioTurno() < 0) {
            e.add("tiempos.separacionCambioTurno: no puede ser negativo");
        }
        if (t.trasvase() < 0) {
            e.add("tiempos.trasvase: no puede ser negativo");
        }
        if (t.recargaIntermediosMinutoDia() < 0 || t.recargaIntermediosMinutoDia() >= 1440) {
            e.add("tiempos.recargaIntermediosMinutoDia: debe estar en [0, 1440)");
        }
    }
}
