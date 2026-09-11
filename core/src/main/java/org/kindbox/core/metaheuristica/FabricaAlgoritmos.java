package org.kindbox.core.metaheuristica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ObjLongConsumer;
import org.kindbox.core.construccion.AhorrosClarkeWright;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.alns.ParametrosAlns;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;

/**
 * Punto unico de construccion de los dos algoritmos del planificador a partir de su nombre,
 * de la semilla de la corrida y de un juego de propiedades con que ajustar sus parametros.
 *
 * <p>Resuelve tres necesidades que antes se cubrian por separado en cada invocante. La
 * experimentacion numerica del apartado 12 del ISA barre los parametros calibrables de los
 * apartados 6.4 y 7.4 sin recompilar; el apartado 10 exige que cada corrida quede fijada por
 * su semilla, que los ejecutables no llegaban a pasar al algoritmo; y el requisito no
 * funcional (b) admite exactamente dos algoritmos, cuyos nombres y alias se resolvian con
 * reglas distintas en el modulo de servicio y en los ejecutables. Aqui se resuelven una sola
 * vez, y la heuristica constructiva de ahorros es la misma para los dos, conforme al
 * apartado 10.</p>
 *
 * <p><b>Nombres.</b> Sin distinguir mayusculas, {@code HGS}, {@code GENETICA} y
 * {@code BUSQUEDA_GENETICA_HIBRIDA} crean la busqueda genetica hibrida del apartado 6, y
 * {@code ALNS}, {@code VECINDAD} y {@code BUSQUEDA_ADAPTATIVA_VECINDAD_AMPLIA} la busqueda
 * adaptativa de vecindad amplia del apartado 7.</p>
 *
 * <p><b>Claves.</b> Cada parametro de {@link ParametrosHgs} se ajusta con la clave
 * {@code hgs.<parametro>} y cada uno de {@link ParametrosAlns} con {@code alns.<parametro>},
 * con el mismo nombre que su accesor; {@link #clavesHgs()} y {@link #clavesAlns()} dan la
 * lista completa y el {@code toString} de cada clase de parametros usa esos mismos nombres,
 * de modo que la traza de una corrida se puede copiar de vuelta como propiedades. Los valores
 * se escriben con punto decimal. Varios parametros de ALNS se validan en pareja o en trio,
 * como el rango de destruccion o las temperaturas, y su asignacion es agrupada: cada
 * componente tiene su propia clave y el grupo se aplica una sola vez, con las componentes que
 * no se indicaron en su valor vigente. Asi fijar a la vez el minimo y el maximo de un rango no
 * depende del orden en que se lean las claves.</p>
 *
 * <p><b>Errores.</b> Una clave con prefijo {@code hgs.} o {@code alns.} que no corresponde a
 * ningun parametro lanza {@link IllegalArgumentException} con la lista de claves validas, y lo
 * mismo un valor mal escrito o fuera del dominio del parametro, indicando clave y valor. Se
 * validan siempre las claves de los dos algoritmos, aunque solo se cree uno: un error de
 * tipeo nunca pasa en silencio, que es lo que invalidaria un barrido entero sin que nadie lo
 * advirtiera. El prefijo se reconoce sin distinguir mayusculas para que {@code HGS.x} tambien
 * se rechace; el resto de la clave se compara de forma exacta.</p>
 */
public final class FabricaAlgoritmos {

    /** Prefijo de las claves que ajustan {@link ParametrosHgs}. */
    public static final String PREFIJO_HGS = "hgs.";
    /** Prefijo de las claves que ajustan {@link ParametrosAlns}. */
    public static final String PREFIJO_ALNS = "alns.";

    private static final Tabla<ParametrosHgs> TABLA_HGS = tablaHgs();
    private static final Tabla<ParametrosAlns> TABLA_ALNS = tablaAlns();

    private FabricaAlgoritmos() {
    }

    /** Nombres canonicos de los algoritmos disponibles. */
    public static List<String> nombres() {
        return List.of(BusquedaGeneticaHibrida.NOMBRE, BusquedaAdaptativaVecindadAmplia.NOMBRE);
    }

    /**
     * Nombre canonico que corresponde a un nombre o alias.
     *
     * @throws IllegalArgumentException si el nombre no corresponde a ninguno de los dos algoritmos
     */
    public static String canonico(String nombre) {
        String clave = nombre == null ? "" : nombre.trim().toUpperCase(Locale.ROOT);
        return switch (clave) {
            case "HGS", "GENETICA", "BUSQUEDA_GENETICA_HIBRIDA" -> BusquedaGeneticaHibrida.NOMBRE;
            case "ALNS", "VECINDAD", "BUSQUEDA_ADAPTATIVA_VECINDAD_AMPLIA" ->
                    BusquedaAdaptativaVecindadAmplia.NOMBRE;
            default -> throw new IllegalArgumentException("Algoritmo desconocido: " + nombre
                    + ". Los disponibles son " + String.join(" y ", nombres()));
        };
    }

    /** Crea el algoritmo con los parametros ajustados por las propiedades de sistema. */
    public static Algoritmo crear(String nombre, long semilla) {
        return crear(nombre, semilla, System.getProperties());
    }

    /**
     * Crea el algoritmo pedido, con los parametros por defecto modificados por las claves
     * {@code hgs.*} o {@code alns.*} presentes en las propiedades.
     *
     * @param nombre      nombre o alias del algoritmo, sin distinguir mayusculas
     * @param semilla     semilla del generador de la corrida
     * @param propiedades propiedades de las que se leen los ajustes; las que no llevan uno de
     *                    los dos prefijos se ignoran
     * @throws IllegalArgumentException si el nombre, una clave o un valor no son validos
     */
    public static Algoritmo crear(String nombre, long semilla, Properties propiedades) {
        String canonico = canonico(nombre);
        Map<String, String> ajustes = ajustesDe(propiedades);
        ParametrosHgs hgs = TABLA_HGS.aplicar(ParametrosHgs.porDefecto(), ajustes);
        ParametrosAlns alns = TABLA_ALNS.aplicar(ParametrosAlns.porDefecto(), ajustes);
        if (BusquedaGeneticaHibrida.NOMBRE.equals(canonico)) {
            return new BusquedaGeneticaHibrida(new AhorrosClarkeWright(), hgs, semilla);
        }
        return new BusquedaAdaptativaVecindadAmplia(new AhorrosClarkeWright(), alns, semilla);
    }

    /**
     * Comprueba claves y valores de los dos algoritmos sin crear ninguno, para que un
     * invocante de larga vida pueda fallar al arrancar y no en la primera corrida.
     *
     * @throws IllegalArgumentException si una clave o un valor no son validos
     */
    public static void validar(Properties propiedades) {
        Map<String, String> ajustes = ajustesDe(propiedades);
        TABLA_HGS.aplicar(ParametrosHgs.porDefecto(), ajustes);
        TABLA_ALNS.aplicar(ParametrosAlns.porDefecto(), ajustes);
    }

    /** Claves admitidas para la busqueda genetica hibrida, en el orden de {@link ParametrosHgs}. */
    public static List<String> clavesHgs() {
        return TABLA_HGS.claves();
    }

    /** Claves admitidas para la busqueda adaptativa, en el orden de {@link ParametrosAlns}. */
    public static List<String> clavesAlns() {
        return TABLA_ALNS.claves();
    }

    // ------------------------------------------------------------------ tablas

    private static Tabla<ParametrosHgs> tablaHgs() {
        return new Tabla<ParametrosHgs>(PREFIJO_HGS)
                .entero("tamanoMinimoPoblacion", ParametrosHgs::tamanoMinimoPoblacion)
                .entero("tamanoGeneracion", ParametrosHgs::tamanoGeneracion)
                .entero("individuosElite", ParametrosHgs::individuosElite)
                .entero("vecinosProximosDiversidad", ParametrosHgs::vecinosProximosDiversidad)
                .real("proporcionObjetivoFactibles", ParametrosHgs::proporcionObjetivoFactibles)
                .entero("granularidadVecindario", ParametrosHgs::granularidadVecindario)
                .entero("pasadasEducacionMaximas", ParametrosHgs::pasadasEducacionMaximas)
                .entero("frecuenciaMaterializacion", ParametrosHgs::frecuenciaMaterializacion)
                .real("probabilidadReparacion", ParametrosHgs::probabilidadReparacion)
                .real("factorPenalizacionReparacion", ParametrosHgs::factorPenalizacionReparacion)
                .entero("longitudMaximaArco", ParametrosHgs::longitudMaximaArco)
                .entero("iteracionesLagrangiana", ParametrosHgs::iteracionesLagrangiana)
                .entero("candidatosUnidadPorRuta", ParametrosHgs::candidatosUnidadPorRuta)
                .entero("desfaseMaximoDeArco", ParametrosHgs::desfaseMaximoDeArco)
                .real("penalizacionDesfaseInicial", ParametrosHgs::penalizacionDesfaseInicial)
                .real("penalizacionDesfaseMinima", ParametrosHgs::penalizacionDesfaseMinima)
                .real("penalizacionDesfaseMaxima", ParametrosHgs::penalizacionDesfaseMaxima)
                .entero("frecuenciaAjustePenalizacion", ParametrosHgs::frecuenciaAjustePenalizacion)
                .real("pesoEstabilidad", ParametrosHgs::pesoEstabilidad)
                .real("factorAumentoPenalizacion", ParametrosHgs::factorAumentoPenalizacion)
                .real("factorReduccionPenalizacion", ParametrosHgs::factorReduccionPenalizacion)
                .real("holguraProporcionFactibles", ParametrosHgs::holguraProporcionFactibles)
                .real("esfuerzoElite", ParametrosHgs::esfuerzoElite)
                .largo("maximoGeneraciones", ParametrosHgs::maximoGeneraciones)
                .largo("maximoGeneracionesSinMejora", ParametrosHgs::maximoGeneracionesSinMejora);
    }

    private static Tabla<ParametrosAlns> tablaAlns() {
        return new Tabla<ParametrosAlns>(PREFIJO_ALNS)
                .grupo(Tipo.REAL, (p, v) -> p.rangoDestruccion(
                                v.real("fraccionMinimaDestruccion", p.fraccionMinimaDestruccion()),
                                v.real("fraccionMaximaDestruccion", p.fraccionMaximaDestruccion())),
                        "fraccionMinimaDestruccion", "fraccionMaximaDestruccion")
                .entero("maximoAbsolutoDestruccion", ParametrosAlns::maximoAbsolutoDestruccion)
                .entero("longitudMaximaCadena", ParametrosAlns::longitudMaximaCadena)
                .grupo(Tipo.REAL, (p, v) -> p.rangoParpadeo(
                                v.real("parpadeoMinimo", p.parpadeoMinimo()),
                                v.real("parpadeoMaximo", p.parpadeoMaximo())),
                        "parpadeoMinimo", "parpadeoMaximo")
                .grupo(Tipo.ENTERO, (p, v) -> p.rangoArrepentimiento(
                                v.entero("ordenArrepentimientoMinimo", p.ordenArrepentimientoMinimo()),
                                v.entero("ordenArrepentimientoMaximo", p.ordenArrepentimientoMaximo())),
                        "ordenArrepentimientoMinimo", "ordenArrepentimientoMaximo")
                .real("determinismoSeleccion", ParametrosAlns::determinismoSeleccion)
                .entero("ventanaVecindad", ParametrosAlns::ventanaVecindad)
                .entero("maximoUnidadesCandidatas", ParametrosAlns::maximoUnidadesCandidatas)
                .grupo(Tipo.REAL, (p, v) -> p.pesosAfinidad(
                                v.real("pesoAfinidadDistancia", p.pesoAfinidadDistancia()),
                                v.real("pesoAfinidadPlazo", p.pesoAfinidadPlazo()),
                                v.real("pesoAfinidadUnidad", p.pesoAfinidadUnidad())),
                        "pesoAfinidadDistancia", "pesoAfinidadPlazo", "pesoAfinidadUnidad")
                .entero("longitudSegmento", ParametrosAlns::longitudSegmento)
                .real("tasaReaccion", ParametrosAlns::tasaReaccion)
                .grupo(Tipo.REAL, (p, v) -> p.puntajes(
                                v.real("puntajeNuevaMejor", p.puntajeNuevaMejor()),
                                v.real("puntajeMejora", p.puntajeMejora()),
                                v.real("puntajeAceptada", p.puntajeAceptada())),
                        "puntajeNuevaMejor", "puntajeMejora", "puntajeAceptada")
                .real("pesoMinimoOperador", ParametrosAlns::pesoMinimoOperador)
                .grupo(Tipo.REAL, (p, v) -> p.temperaturas(
                                v.real("fraccionTemperaturaInicial", p.fraccionTemperaturaInicial()),
                                v.real("fraccionTemperaturaFinal", p.fraccionTemperaturaFinal())),
                        "fraccionTemperaturaInicial", "fraccionTemperaturaFinal")
                .real("factorPenalizacionBanco", ParametrosAlns::factorPenalizacionBanco)
                .real("factorPenalizacionEstabilidad", ParametrosAlns::factorPenalizacionEstabilidad)
                .largo("maximoIteracionesSinMejora", ParametrosAlns::maximoIteracionesSinMejora)
                .largo("iteracionesParaReinicio", ParametrosAlns::iteracionesParaReinicio);
    }

    // ------------------------------------------------------------- lectura

    /**
     * Extrae de las propiedades las claves con uno de los dos prefijos y rechaza las que no
     * corresponden a ningun parametro. Devuelve un mapa ordenado para que la aplicacion no
     * dependa del orden de iteracion de {@link Properties}.
     */
    private static Map<String, String> ajustesDe(Properties propiedades) {
        if (propiedades == null) {
            throw new IllegalArgumentException("La fabrica de algoritmos necesita propiedades, aunque esten vacias");
        }
        Map<String, String> ajustes = new TreeMap<>();
        List<String> desconocidas = new ArrayList<>();
        boolean desconocidaHgs = false;
        boolean desconocidaAlns = false;
        for (String clave : propiedades.stringPropertyNames()) {
            String minusculas = clave.toLowerCase(Locale.ROOT);
            boolean deHgs = minusculas.startsWith(PREFIJO_HGS);
            boolean deAlns = minusculas.startsWith(PREFIJO_ALNS);
            if (!deHgs && !deAlns) {
                continue;
            }
            if (TABLA_HGS.contiene(clave) || TABLA_ALNS.contiene(clave)) {
                ajustes.put(clave, propiedades.getProperty(clave));
            } else {
                desconocidas.add(clave);
                desconocidaHgs |= deHgs;
                desconocidaAlns |= deAlns;
            }
        }
        if (!desconocidas.isEmpty()) {
            Collections.sort(desconocidas);
            StringBuilder mensaje = new StringBuilder();
            mensaje.append(desconocidas.size() == 1 ? "Clave de parametro desconocida: "
                    : "Claves de parametro desconocidas: ").append(String.join(", ", desconocidas)).append('.');
            if (desconocidaHgs) {
                mensaje.append(" Las claves validas de HGS son: ").append(String.join(", ", clavesHgs())).append('.');
            }
            if (desconocidaAlns) {
                mensaje.append(" Las claves validas de ALNS son: ").append(String.join(", ", clavesAlns()))
                        .append('.');
            }
            throw new IllegalArgumentException(mensaje.toString());
        }
        return ajustes;
    }

    /** Tipo del valor de una clave, que decide como se interpreta su texto. */
    private enum Tipo {
        ENTERO("un numero entero"),
        LARGO("un numero entero"),
        REAL("un numero real finito escrito con punto decimal");

        private final String descripcion;

        Tipo(String descripcion) {
            this.descripcion = descripcion;
        }
    }

    /** Asigna sobre los parametros las componentes de un ajuste, leidas de los valores. */
    @FunctionalInterface
    private interface Aplicacion<P> {
        void aplicar(P parametros, Valores valores);
    }

    /** Uno o varios parametros que se asignan juntos, identificados por su nombre sin prefijo. */
    private record Ajuste<P>(String[] nombres, Aplicacion<P> aplicacion) {
    }

    /** Valores ya interpretados de las claves de un algoritmo, indexados por nombre sin prefijo. */
    private static final class Valores {

        private final String prefijo;
        private final Map<String, Number> numeros = new HashMap<>();
        private final Map<String, String> textos = new HashMap<>();

        Valores(String prefijo) {
            this.prefijo = prefijo;
        }

        void poner(String nombre, String texto, Tipo tipo) {
            numeros.put(nombre, interpretar(prefijo + nombre, texto, tipo));
            textos.put(nombre, texto);
        }

        boolean algunoPresente(String[] nombres) {
            for (String nombre : nombres) {
                if (numeros.containsKey(nombre)) {
                    return true;
                }
            }
            return false;
        }

        int entero(String nombre, int vigente) {
            Number valor = numeros.get(nombre);
            return valor == null ? vigente : valor.intValue();
        }

        long largo(String nombre, long vigente) {
            Number valor = numeros.get(nombre);
            return valor == null ? vigente : valor.longValue();
        }

        double real(String nombre, double vigente) {
            Number valor = numeros.get(nombre);
            return valor == null ? vigente : valor.doubleValue();
        }

        /** Las claves presentes del ajuste con su valor tal como se escribio. */
        String describir(String[] nombres) {
            StringBuilder texto = new StringBuilder();
            for (String nombre : nombres) {
                if (textos.containsKey(nombre)) {
                    if (texto.length() > 0) {
                        texto.append(", ");
                    }
                    texto.append(prefijo).append(nombre).append('=').append(textos.get(nombre));
                }
            }
            return texto.toString();
        }

        private static Number interpretar(String clave, String texto, Tipo tipo) {
            String limpio = texto == null ? "" : texto.trim();
            try {
                if (tipo == Tipo.ENTERO) {
                    return Integer.valueOf(Integer.parseInt(limpio));
                }
                if (tipo == Tipo.LARGO) {
                    return Long.valueOf(Long.parseLong(limpio));
                }
                double valor = Double.parseDouble(limpio);
                if (!Double.isFinite(valor)) {
                    throw new NumberFormatException("valor no finito");
                }
                return Double.valueOf(valor);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Valor mal escrito en la clave " + clave + ": '" + texto
                        + "' no es " + tipo.descripcion, e);
            }
        }
    }

    /**
     * Claves de un algoritmo y la forma de aplicarlas. Se construye una vez, al cargar la
     * clase, y despues solo se lee, de modo que la fabrica se puede usar desde varios hilos.
     */
    private static final class Tabla<P> {

        private final String prefijo;
        private final List<String> claves = new ArrayList<>();
        private final Map<String, Tipo> tipos = new HashMap<>();
        private final List<Ajuste<P>> ajustes = new ArrayList<>();

        Tabla(String prefijo) {
            this.prefijo = prefijo;
        }

        Tabla<P> entero(String nombre, ObjIntConsumer<P> asignar) {
            return grupo(Tipo.ENTERO, (p, v) -> asignar.accept(p, v.entero(nombre, 0)), nombre);
        }

        Tabla<P> largo(String nombre, ObjLongConsumer<P> asignar) {
            return grupo(Tipo.LARGO, (p, v) -> asignar.accept(p, v.largo(nombre, 0L)), nombre);
        }

        Tabla<P> real(String nombre, ObjDoubleConsumer<P> asignar) {
            return grupo(Tipo.REAL, (p, v) -> asignar.accept(p, v.real(nombre, 0.0)), nombre);
        }

        /** Registra un ajuste de una o varias componentes del mismo tipo. */
        Tabla<P> grupo(Tipo tipo, Aplicacion<P> aplicacion, String... nombres) {
            for (String nombre : nombres) {
                if (tipos.put(prefijo + nombre, tipo) != null) {
                    throw new IllegalStateException("Clave registrada dos veces: " + prefijo + nombre);
                }
                claves.add(prefijo + nombre);
            }
            ajustes.add(new Ajuste<>(nombres.clone(), aplicacion));
            return this;
        }

        boolean contiene(String clave) {
            return tipos.containsKey(clave);
        }

        List<String> claves() {
            return List.copyOf(claves);
        }

        /**
         * Interpreta primero todos los valores de este algoritmo, para que un valor mal escrito
         * se informe como tal, y aplica despues cada ajuste presente, en el orden de registro.
         */
        P aplicar(P parametros, Map<String, String> ajustesCrudos) {
            Valores valores = new Valores(prefijo);
            for (Map.Entry<String, String> entrada : ajustesCrudos.entrySet()) {
                Tipo tipo = tipos.get(entrada.getKey());
                if (tipo != null) {
                    valores.poner(entrada.getKey().substring(prefijo.length()), entrada.getValue(), tipo);
                }
            }
            for (Ajuste<P> ajuste : ajustes) {
                if (!valores.algunoPresente(ajuste.nombres())) {
                    continue;
                }
                try {
                    ajuste.aplicacion().aplicar(parametros, valores);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Valor no admitido en " + valores.describir(ajuste.nombres())
                            + ": " + e.getMessage(), e);
                }
            }
            return parametros;
        }
    }
}
