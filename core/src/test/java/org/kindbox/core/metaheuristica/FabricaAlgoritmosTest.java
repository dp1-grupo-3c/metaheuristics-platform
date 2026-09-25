package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.metaheuristica.alns.BusquedaAdaptativaVecindadAmplia;
import org.kindbox.core.metaheuristica.alns.ParametrosAlns;
import org.kindbox.core.metaheuristica.hgs.BusquedaGeneticaHibrida;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;

/**
 * Fabrica de algoritmos con parametros ajustables por propiedades.
 *
 * <p>El banco de experimentos del apartado 12 del ISA barre los parametros de los apartados
 * 6.4 y 7.4 a traves de esta fabrica, de modo que lo que aqui se comprueba es que un barrido
 * mide lo que dice medir: que cada alias crea el algoritmo que corresponde con la semilla de
 * la corrida, que cada clave llega al parametro de su nombre, que un error de tipeo en la
 * clave o en el valor detiene la corrida en lugar de pasar en silencio y que la traza de los
 * parametros lista todos los que existen.</p>
 */
class FabricaAlgoritmosTest {

    private static Properties propiedades(String... paresClaveValor) {
        Properties propiedades = new Properties();
        for (int i = 0; i < paresClaveValor.length; i += 2) {
            propiedades.setProperty(paresClaveValor[i], paresClaveValor[i + 1]);
        }
        return propiedades;
    }

    private static ParametrosHgs hgs(Properties propiedades) {
        Algoritmo algoritmo = FabricaAlgoritmos.crear("HGS", 1L, propiedades);
        return assertInstanceOf(BusquedaGeneticaHibrida.class, algoritmo).parametros();
    }

    private static ParametrosAlns alns(Properties propiedades) {
        Algoritmo algoritmo = FabricaAlgoritmos.crear("ALNS", 1L, propiedades);
        return assertInstanceOf(BusquedaAdaptativaVecindadAmplia.class, algoritmo).parametros();
    }

    @Test
    @DisplayName("Los alias crean el algoritmo que corresponde, con la semilla indicada")
    void aliasYSemilla() {
        for (String alias : List.of("HGS", "hgs", " Genetica ", "busqueda_genetica_hibrida")) {
            Algoritmo algoritmo = FabricaAlgoritmos.crear(alias, 77L, new Properties());
            assertEquals(BusquedaGeneticaHibrida.NOMBRE, algoritmo.nombre(), alias);
            assertEquals(77L, assertInstanceOf(BusquedaGeneticaHibrida.class, algoritmo).semilla());
            assertEquals(BusquedaGeneticaHibrida.NOMBRE, FabricaAlgoritmos.canonico(alias));
        }
        for (String alias : List.of("ALNS", "alns", "Vecindad", "BUSQUEDA_ADAPTATIVA_VECINDAD_AMPLIA")) {
            Algoritmo algoritmo = FabricaAlgoritmos.crear(alias, 78L, new Properties());
            assertEquals(BusquedaAdaptativaVecindadAmplia.NOMBRE, algoritmo.nombre(), alias);
            assertEquals(78L, assertInstanceOf(BusquedaAdaptativaVecindadAmplia.class, algoritmo).semilla());
            assertEquals(BusquedaAdaptativaVecindadAmplia.NOMBRE, FabricaAlgoritmos.canonico(alias));
        }
        for (String alias : List.of("TS", "tabu", "Busqueda_Tabu")) {
            Algoritmo algoritmo = FabricaAlgoritmos.crear(alias, 79L, new Properties());
            assertEquals(org.kindbox.core.metaheuristica.tabu.BusquedaTabu.NOMBRE, algoritmo.nombre(), alias);
        }
        for (String nombre : new String[]{"SIMPLEX", "", null}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> FabricaAlgoritmos.crear(nombre, 1L, new Properties()));
            assertTrue(error.getMessage().contains("HGS, ALNS"), error.getMessage());
        }
    }

    @Test
    @DisplayName("Una clave de cada algoritmo llega a su parametro y el resto conserva el valor por defecto")
    void clavesAplicadas() {
        Properties ajustes = propiedades(
                "hgs.tamanoGeneracion", "31",
                "hgs.pesoEstabilidad", " 12.5 ",
                "alns.factorPenalizacionEstabilidad", "0.5",
                "otra.propiedad", "se ignora");

        ParametrosHgs hgs = hgs(ajustes);
        assertEquals(31, hgs.tamanoGeneracion());
        assertEquals(12.5, hgs.pesoEstabilidad());
        assertEquals(ParametrosHgs.TAMANO_MINIMO_POBLACION, hgs.tamanoMinimoPoblacion());

        ParametrosAlns alns = alns(ajustes);
        assertEquals(0.5, alns.factorPenalizacionEstabilidad());
        assertEquals(ParametrosAlns.porDefecto().toString().replace("factorPenalizacionEstabilidad=0.25",
                "factorPenalizacionEstabilidad=0.5"), alns.toString());
    }

    @Test
    @DisplayName("Un grupo de ALNS se aplica de una vez, con las componentes ausentes en su valor vigente")
    void gruposAplicadosDeUnaVez() {
        // Con el maximo vigente de 0.40, fijar solo el minimo a 0.5 seria invalido; juntos no lo son.
        ParametrosAlns rango = alns(propiedades(
                "alns.fraccionMinimaDestruccion", "0.5", "alns.fraccionMaximaDestruccion", "0.8"));
        assertEquals(0.5, rango.fraccionMinimaDestruccion());
        assertEquals(0.8, rango.fraccionMaximaDestruccion());

        ParametrosAlns porDefecto = ParametrosAlns.porDefecto();
        ParametrosAlns parpadeo = alns(propiedades("alns.parpadeoMaximo", "0.2"));
        assertEquals(0.2, parpadeo.parpadeoMaximo());
        assertEquals(porDefecto.parpadeoMinimo(), parpadeo.parpadeoMinimo());

        ParametrosAlns pesos = alns(propiedades("alns.pesoAfinidadUnidad", "0.7"));
        assertEquals(0.7, pesos.pesoAfinidadUnidad());
        assertEquals(porDefecto.pesoAfinidadDistancia(), pesos.pesoAfinidadDistancia());
        assertEquals(porDefecto.pesoAfinidadPlazo(), pesos.pesoAfinidadPlazo());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> alns(propiedades("alns.fraccionMinimaDestruccion", "0.5")));
        assertTrue(error.getMessage().contains("alns.fraccionMinimaDestruccion=0.5"), error.getMessage());
    }

    @Test
    @DisplayName("Una clave desconocida se rechaza con la lista de claves validas")
    void claveDesconocidaRechazada() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FabricaAlgoritmos.crear("ALNS", 1L, propiedades("alns.pesoEstabilidad", "0.5")));
        assertTrue(error.getMessage().contains("alns.pesoEstabilidad"), error.getMessage());
        assertTrue(error.getMessage().contains("alns.factorPenalizacionEstabilidad"), error.getMessage());

        // Un error en las claves del otro algoritmo tampoco pasa en silencio.
        error = assertThrows(IllegalArgumentException.class,
                () -> FabricaAlgoritmos.crear("HGS", 1L, propiedades("alns.tasaReacion", "0.2")));
        assertTrue(error.getMessage().contains("alns.tasaReacion"), error.getMessage());

        // El prefijo se reconoce sin distinguir mayusculas; el resto de la clave es exacto.
        error = assertThrows(IllegalArgumentException.class,
                () -> FabricaAlgoritmos.crear("HGS", 1L, propiedades("HGS.tamanoGeneracion", "30")));
        assertTrue(error.getMessage().contains("hgs.tamanoGeneracion"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> FabricaAlgoritmos.validar(propiedades("hgs.tamanogeneracion", "30")));
    }

    @Test
    @DisplayName("Un valor mal escrito o fuera de dominio se rechaza indicando clave y valor")
    void valorInvalidoRechazado() {
        String[][] casos = {
            {"hgs.tamanoGeneracion", "treinta"},
            {"hgs.tamanoGeneracion", "30.5"},
            {"hgs.tamanoGeneracion", "-3"},
            {"hgs.esfuerzoElite", "0.9"},
            {"hgs.maximoGeneraciones", "1e6"},
            {"alns.tasaReaccion", "0,3"},
            {"alns.tasaReaccion", "NaN"},
            {"alns.ordenArrepentimientoMinimo", "1"},
            {"hgs.filtroCotaInferior", "quizas"},
            {"alns.filtroCotaInferior", "1"},
        };
        for (String[] caso : casos) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> FabricaAlgoritmos.validar(propiedades(caso[0], caso[1])), caso[0] + "=" + caso[1]);
            assertTrue(error.getMessage().contains(caso[0]), error.getMessage());
            assertTrue(error.getMessage().contains(caso[1]), error.getMessage());
        }
    }

    @Test
    @DisplayName("Cada clave llega al parametro de su nombre y la traza los lista todos")
    void cadaClaveLlegaASuParametro() {
        comprobarCadaClave(FabricaAlgoritmos.PREFIJO_HGS, FabricaAlgoritmos.clavesHgs(),
                ParametrosHgs.porDefecto().toString(), p -> hgs(p).toString());
        comprobarCadaClave(FabricaAlgoritmos.PREFIJO_ALNS, FabricaAlgoritmos.clavesAlns(),
                ParametrosAlns.porDefecto().toString(), p -> alns(p).toString());
    }

    @Test
    @DisplayName("Toda variable de instancia de los parametros tiene clave con su mismo nombre")
    void clavesCubrenTodosLosParametros() {
        assertEquals(nombresDeCampos(ParametrosHgs.class),
                sinPrefijo(FabricaAlgoritmos.PREFIJO_HGS, FabricaAlgoritmos.clavesHgs()));
        assertEquals(nombresDeCampos(ParametrosAlns.class),
                sinPrefijo(FabricaAlgoritmos.PREFIJO_ALNS, FabricaAlgoritmos.clavesAlns()));
    }

    @Test
    @DisplayName("La sobrecarga sin propiedades lee las propiedades de sistema")
    void leePropiedadesDeSistema() {
        String clave = "hgs.frecuenciaMaterializacion";
        String previo = System.getProperty(clave);
        try {
            System.setProperty(clave, "7");
            Algoritmo algoritmo = FabricaAlgoritmos.crear("GENETICA", 5L);
            assertEquals(7, assertInstanceOf(BusquedaGeneticaHibrida.class, algoritmo)
                    .parametros().frecuenciaMaterializacion());
        } finally {
            if (previo == null) {
                System.clearProperty(clave);
            } else {
                System.setProperty(clave, previo);
            }
        }
    }

    // ------------------------------------------------------------ auxiliares

    /**
     * Para cada clave, fija un valor distinto del vigente y valido y exige que la traza
     * resultante difiera de la de por defecto exactamente en esa entrada. Los enteros se
     * incrementan en uno, los reales se multiplican por 0.9, que cae dentro del dominio de
     * todos los parametros vigentes, y los booleanos se invierten.
     */
    private static void comprobarCadaClave(String prefijo, List<String> claves, String trazaPorDefecto,
                                           Function<Properties, String> traza) {
        List<String> entradas = entradasDeTraza(trazaPorDefecto);
        assertEquals(claves.size(), entradas.size(), "la traza debe listar todos los parametros");
        for (int i = 0; i < claves.size(); i++) {
            String clave = claves.get(i);
            String nombre = clave.substring(prefijo.length());
            String entrada = entradas.get(i);
            assertTrue(entrada.startsWith(nombre + "="), "traza fuera de orden en " + entrada);
            String vigente = entrada.substring(nombre.length() + 1);
            String nuevo;
            if (vigente.equals("true") || vigente.equals("false")) {
                nuevo = Boolean.toString(!Boolean.parseBoolean(vigente));
            } else if (vigente.contains(".")) {
                nuevo = Double.toString(Double.parseDouble(vigente) * 0.9);
            } else {
                nuevo = Long.toString(Long.parseLong(vigente) + 1L);
            }
            String esperada = trazaPorDefecto.replace(" " + entrada + " ", " " + nombre + "=" + nuevo + " ")
                    .replace("[" + entrada + " ", "[" + nombre + "=" + nuevo + " ")
                    .replace(" " + entrada + "]", " " + nombre + "=" + nuevo + "]");
            assertEquals(esperada, traza.apply(propiedades(clave, nuevo)), clave);
        }
    }

    private static List<String> entradasDeTraza(String traza) {
        String cuerpo = traza.substring(traza.indexOf('[') + 1, traza.length() - 1);
        List<String> entradas = new ArrayList<>();
        for (String entrada : cuerpo.split(" ")) {
            entradas.add(entrada);
        }
        return entradas;
    }

    private static TreeSet<String> nombresDeCampos(Class<?> clase) {
        TreeSet<String> nombres = new TreeSet<>();
        for (Field campo : clase.getDeclaredFields()) {
            if (!Modifier.isStatic(campo.getModifiers())) {
                nombres.add(campo.getName());
            }
        }
        return nombres;
    }

    private static TreeSet<String> sinPrefijo(String prefijo, List<String> claves) {
        TreeSet<String> nombres = new TreeSet<>();
        for (String clave : claves) {
            assertTrue(clave.startsWith(prefijo), clave);
            nombres.add(clave.substring(prefijo.length()));
        }
        return nombres;
    }
}
