package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Averia;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;

/**
 * Reglas de generacion de averias del motor de simulacion, respuesta 3 del cuestionario y
 * apartado 7 del contexto de dominio.
 *
 * <p>Las reglas concretas no las publico el equipo docente y se documentan en
 * {@link GeneradorAverias} como decision propia. Esta prueba es su especificacion
 * ejecutable: solo se averia lo que esta en ruta, la probabilidad crece de auto a moto y de
 * moto a bicicleta, el reparto entre los tres tipos de averia es 70, 25 y 5 por ciento, y
 * toda la generacion es reproducible a partir de la semilla de la corrida.</p>
 */
class GeneradorAveriasTest {

    /** 07:00 del primer dia, arranque del turno de manana. */
    private static final long TURNO_MANANA = 420L;

    /** Flota de trabajo con las cantidades indicadas, toda ella en ruta. */
    private static List<UnidadTransporte> flotaEnRuta(int autos, int motos, int bicicletas) {
        List<UnidadTransporte> flota = new ArrayList<>();
        agregar(flota, TipoUnidad.AUTO, autos);
        agregar(flota, TipoUnidad.MOTO, motos);
        agregar(flota, TipoUnidad.BICICLETA, bicicletas);
        return flota;
    }

    private static void agregar(List<UnidadTransporte> flota, TipoUnidad tipo, int cantidad) {
        int nodo = Almacen.crearCentral().nodo();
        for (int i = 1; i <= cantidad; i++) {
            UnidadTransporte unidad = UnidadTransporte.de(String.format("%s%02d", tipo.prefijo(), i), nodo);
            unidad.estado(EstadoUnidad.EN_RUTA);
            flota.add(unidad);
        }
    }

    @Test
    @DisplayName("La misma semilla produce exactamente la misma secuencia de averias")
    void generacionReproducible() {
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);
        GeneradorAverias uno = new GeneradorAverias(20260901L, 0.10);
        GeneradorAverias otro = new GeneradorAverias(20260901L, 0.10);

        for (long turno = TURNO_MANANA; turno < TURNO_MANANA + 30 * 1440; turno += Turno.DURACION_MIN) {
            assertEquals(uno.averiasDelTurno(turno, flota), otro.averiasDelTurno(turno, flota),
                    "difieren en el turno que arranca en el minuto " + turno);
        }
    }

    @Test
    @DisplayName("Semillas distintas producen secuencias distintas")
    void semillasDistintasDanAveriasDistintas() {
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);
        List<Averia> conUna = new ArrayList<>();
        List<Averia> conOtra = new ArrayList<>();
        for (long turno = TURNO_MANANA; turno < TURNO_MANANA + 30 * 1440; turno += Turno.DURACION_MIN) {
            conUna.addAll(new GeneradorAverias(1L, 0.10).averiasDelTurno(turno, flota));
            conOtra.addAll(new GeneradorAverias(2L, 0.10).averiasDelTurno(turno, flota));
        }
        assertTrue(conUna.size() > 0 && conOtra.size() > 0, "ambas semillas deben producir averias");
        assertNotEquals(conUna, conOtra, "dos semillas distintas no pueden dar la misma secuencia");
    }

    @Test
    @DisplayName("El sorteo de una unidad no depende de las demas ni del orden de la lista")
    void elSorteoEsIndependientePorUnidad() {
        GeneradorAverias generador = new GeneradorAverias(4242L, 0.30);
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);

        List<Averia> conTodaLaFlota = generador.averiasDelTurno(TURNO_MANANA, flota);

        // La misma consulta unidad a unidad debe dar exactamente las mismas averias.
        List<Averia> unaAUna = new ArrayList<>();
        for (UnidadTransporte unidad : flota) {
            Averia averia = generador.averiaDe(unidad, TURNO_MANANA);
            if (averia != null) {
                unaAUna.add(averia);
            }
        }
        unaAUna.sort((a, b) -> {
            int porMinuto = Long.compare(a.minutoAveria(), b.minutoAveria());
            return porMinuto != 0 ? porMinuto : a.codigoUnidad().compareTo(b.codigoUnidad());
        });
        assertEquals(conTodaLaFlota, unaAUna);

        // Y con la lista al reves tambien, porque el resultado se ordena antes de devolverse.
        List<UnidadTransporte> alReves = new ArrayList<>(flota);
        Collections.reverse(alReves);
        assertEquals(conTodaLaFlota, generador.averiasDelTurno(TURNO_MANANA, alReves));
    }

    @Test
    @DisplayName("Una unidad fuera de servicio no se averia nunca; una que puede operar si")
    void soloSeAveriaLoQuePuedeOperar() {
        // Probabilidad uno: si el estado no filtrase, toda unidad se averiaria.
        GeneradorAverias generador = new GeneradorAverias(7L, 1.0);
        int nodo = Almacen.crearCentral().nodo();

        for (EstadoUnidad estado : EstadoUnidad.values()) {
            UnidadTransporte unidad = UnidadTransporte.de("TB01", nodo);
            unidad.estado(estado);
            Averia averia = generador.averiaDe(unidad, TURNO_MANANA);
            if (estado == EstadoUnidad.AVERIADA || estado == EstadoUnidad.EN_MANTENIMIENTO) {
                assertNull(averia, "una unidad en estado " + estado + " ya esta fuera de servicio");
            } else {
                assertNotNull(averia, "una unidad que puede operar, con probabilidad uno, debe averiarse");
            }
        }

        // El sorteo se hace al arrancar el turno, cuando la flota todavia esta parada en el
        // almacen: una flota entera en estado DISPONIBLE tiene que producir averias, porque
        // esas unidades saldran a operar durante el turno. Que la averia le ocurra a una
        // unidad en operacion lo comprueba el motor en el instante del incidente.
        List<UnidadTransporte> parados = flotaEnRuta(10, 15, 12);
        for (UnidadTransporte unidad : parados) {
            unidad.estado(EstadoUnidad.DISPONIBLE);
        }
        // Con probabilidad uno se averian con certeza las motos (factor 1.00) y las bicicletas
        // (factor 1.80); los autos, con factor 0.60, solo algunos.
        List<Averia> deParados = generador.averiasDelTurno(TURNO_MANANA, parados);
        assertTrue(deParados.size() >= 15 + 12,
                "con probabilidad uno toda moto y toda bicicleta que puede operar se averia, y salieron "
                        + deParados.size());
        assertTrue(deParados.size() <= parados.size());

        // Una flota entera fuera de servicio no produce ninguna averia.
        List<UnidadTransporte> fueraDeServicio = flotaEnRuta(10, 15, 12);
        for (UnidadTransporte unidad : fueraDeServicio) {
            unidad.estado(EstadoUnidad.EN_MANTENIMIENTO);
        }
        assertTrue(generador.averiasDelTurno(TURNO_MANANA, fueraDeServicio).isEmpty());
    }

    @Test
    @DisplayName("La probabilidad crece de auto a moto y de moto a bicicleta")
    void laProbabilidadDependeDelTipoDeUnidad() {
        GeneradorAverias generador = new GeneradorAverias(11L, 0.10);
        assertTrue(generador.probabilidad(TipoUnidad.AUTO) < generador.probabilidad(TipoUnidad.MOTO),
                "un auto es mas robusto que una moto");
        assertTrue(generador.probabilidad(TipoUnidad.MOTO) < generador.probabilidad(TipoUnidad.BICICLETA),
                "una moto es mas robusta que una bicicleta");
        assertEquals(0.10, generador.probabilidad(TipoUnidad.MOTO), 1e-12,
                "la moto es la referencia: su factor vale uno");
        assertEquals(0.10 * GeneradorAverias.FACTOR_AUTO, generador.probabilidad(TipoUnidad.AUTO), 1e-12);
        assertEquals(0.10 * GeneradorAverias.FACTOR_BICICLETA, generador.probabilidad(TipoUnidad.BICICLETA), 1e-12);
        assertEquals(1.0, new GeneradorAverias(11L, 0.9).probabilidad(TipoUnidad.BICICLETA), 1e-12,
                "la probabilidad modulada se acota a uno");

        // La frecuencia observada respeta el mismo orden sobre una muestra grande.
        GeneradorAverias muestreo = new GeneradorAverias(20260901L, 0.10);
        List<UnidadTransporte> flota = flotaEnRuta(40, 40, 40);
        Map<TipoUnidad, Integer> averias = new EnumMap<>(TipoUnidad.class);
        for (TipoUnidad tipo : TipoUnidad.values()) {
            averias.put(tipo, 0);
        }
        int turnos = 600;
        for (int t = 0; t < turnos; t++) {
            long turno = TURNO_MANANA + (long) t * Turno.DURACION_MIN;
            for (Averia averia : muestreo.averiasDelTurno(turno, flota)) {
                TipoUnidad tipo = TipoUnidad.porCodigo(averia.codigoUnidad());
                averias.merge(tipo, 1, Integer::sum);
            }
        }
        assertTrue(averias.get(TipoUnidad.AUTO) < averias.get(TipoUnidad.MOTO),
                "autos " + averias.get(TipoUnidad.AUTO) + " frente a motos " + averias.get(TipoUnidad.MOTO));
        assertTrue(averias.get(TipoUnidad.MOTO) < averias.get(TipoUnidad.BICICLETA),
                "motos " + averias.get(TipoUnidad.MOTO) + " frente a bicicletas "
                        + averias.get(TipoUnidad.BICICLETA));
        // Las frecuencias deben rondar la probabilidad declarada de cada tipo.
        for (TipoUnidad tipo : TipoUnidad.values()) {
            double observada = averias.get(tipo) / (40.0 * turnos);
            assertEquals(muestreo.probabilidad(tipo), observada, 0.01,
                    "la frecuencia observada de " + tipo + " se aparta de su probabilidad");
        }
    }

    @Test
    @DisplayName("El reparto entre tipos de averia es 70, 25 y 5 por ciento")
    void repartoEntreTiposDeAveria() {
        assertEquals(0.70, GeneradorAverias.REPARTO_POR_DEFECTO[0], 1e-12);
        assertEquals(0.25, GeneradorAverias.REPARTO_POR_DEFECTO[1], 1e-12);
        assertEquals(0.05, GeneradorAverias.REPARTO_POR_DEFECTO[2], 1e-12);

        // Probabilidad uno y factores neutros: toda unidad en ruta se averia en cada turno,
        // de modo que la muestra del reparto es grande sin necesidad de muchos turnos y el
        // sorteo del tipo de averia queda aislado del sorteo del tipo de unidad.
        GeneradorAverias generador = new GeneradorAverias(31415L, 1.0,
                new double[] {1.0, 1.0, 1.0}, GeneradorAverias.REPARTO_POR_DEFECTO, true);
        List<UnidadTransporte> flota = flotaEnRuta(15, 15, 10);
        Map<TipoAveria, Integer> cuenta = new EnumMap<>(TipoAveria.class);
        for (TipoAveria tipo : TipoAveria.values()) {
            cuenta.put(tipo, 0);
        }
        int turnos = 1000;
        int total = 0;
        for (int t = 0; t < turnos; t++) {
            long turno = TURNO_MANANA + (long) t * Turno.DURACION_MIN;
            List<Averia> averias = generador.averiasDelTurno(turno, flota);
            assertEquals(flota.size(), averias.size(), "con probabilidad uno se averia toda la flota");
            for (Averia averia : averias) {
                cuenta.merge(averia.tipo(), 1, Integer::sum);
                total++;
            }
        }
        assertEquals(0.70, (double) cuenta.get(TipoAveria.TIPO_1) / total, 0.01);
        assertEquals(0.25, (double) cuenta.get(TipoAveria.TIPO_2) / total, 0.01);
        assertEquals(0.05, (double) cuenta.get(TipoAveria.TIPO_3) / total, 0.01);
        assertTrue(cuenta.get(TipoAveria.TIPO_1) > cuenta.get(TipoAveria.TIPO_2),
                "las menores deben ser mucho mas frecuentes que las intermedias");
        assertTrue(cuenta.get(TipoAveria.TIPO_2) > cuenta.get(TipoAveria.TIPO_3),
                "las intermedias deben ser mas frecuentes que las mayores");
    }

    @Test
    @DisplayName("Cada averia cae dentro del turno y lleva el lugar en que estaba la unidad")
    void elInstanteCaeDentroDelTurno() {
        GeneradorAverias generador = new GeneradorAverias(555L, 0.50);
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);
        for (long turno = -60L; turno < 10 * 1440; turno += Turno.DURACION_MIN) {
            long anteriorMinuto = Long.MIN_VALUE;
            String anteriorCodigo = "";
            for (Averia averia : generador.averiasDelTurno(turno, flota)) {
                assertTrue(averia.minutoAveria() >= turno && averia.minutoAveria() < turno + Turno.DURACION_MIN,
                        "la averia " + averia + " se sale del turno que arranca en " + turno);
                assertEquals(Turno.inicioDelTurno(averia.minutoAveria()), turno,
                        "la averia debe pertenecer al turno consultado");
                assertEquals(Almacen.crearCentral().nodo(), averia.nodo(),
                        "la averia ocurre donde estaba la unidad");
                if (averia.minutoAveria() == anteriorMinuto) {
                    assertTrue(averia.codigoUnidad().compareTo(anteriorCodigo) > 0,
                            "a igualdad de instante el orden lo fija el codigo de unidad");
                } else {
                    assertTrue(averia.minutoAveria() > anteriorMinuto, "las averias deben salir ordenadas");
                }
                anteriorMinuto = averia.minutoAveria();
                anteriorCodigo = averia.codigoUnidad();
            }
        }
    }

    @Test
    @DisplayName("Consultar a mitad del turno da lo mismo que consultar en su arranque")
    void elInstanteDeConsultaSeNormalizaAlTurno() {
        GeneradorAverias generador = new GeneradorAverias(909L, 0.40);
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);
        List<Averia> enElArranque = generador.averiasDelTurno(TURNO_MANANA, flota);
        assertEquals(enElArranque, generador.averiasDelTurno(TURNO_MANANA + 1, flota));
        assertEquals(enElArranque, generador.averiasDelTurno(TURNO_MANANA + 137, flota));
        assertEquals(enElArranque, generador.averiasDelTurno(TURNO_MANANA + Turno.DURACION_MIN - 1, flota));
        assertNotEquals(enElArranque, generador.averiasDelTurno(TURNO_MANANA + Turno.DURACION_MIN, flota),
                "el turno siguiente es otro sorteo");
    }

    @Test
    @DisplayName("Con la generacion desactivada no se produce ninguna averia")
    void generacionDesactivada() {
        GeneradorAverias apagado = new GeneradorAverias(1L, 1.0,
                GeneradorAverias.FACTORES_POR_DEFECTO, GeneradorAverias.REPARTO_POR_DEFECTO, false);
        List<UnidadTransporte> flota = flotaEnRuta(10, 15, 12);
        assertTrue(apagado.averiasDelTurno(TURNO_MANANA, flota).isEmpty());
        assertNull(apagado.averiaDe(flota.get(0), TURNO_MANANA));
        assertFalse(apagado.activo());
    }

    @Test
    @DisplayName("La configuracion del escenario alimenta la semilla, la probabilidad y el interruptor")
    void derivacionDesdeLaConfiguracion() {
        ConfiguracionEscenario configuracion = ConfiguracionEscenario.simulacion5D(
                LocalDate.of(2026, 9, 1), 45, 15, "HGS", 2026L);
        GeneradorAverias generador = GeneradorAverias.deConfiguracion(configuracion);

        assertEquals(2026L, generador.semilla());
        assertEquals(configuracion.averiasPorUnidadPorTurno(), generador.probabilidadPorUnidadPorTurno());
        assertTrue(generador.activo(), "el escenario 5D genera averias por reglas");
        assertEquals(GeneradorAverias.REPARTO_POR_DEFECTO.length, generador.reparto().length);

        ConfiguracionEscenario sinAverias = new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), 15, 1.0, ModoReloj.LIBRE,
                "ALNS", 7L, 1, false, 0.02);
        assertFalse(GeneradorAverias.deConfiguracion(sinAverias).activo());
        assertThrows(IllegalArgumentException.class, () -> GeneradorAverias.deConfiguracion(null));
    }

    @Test
    @DisplayName("Los parametros invalidos se rechazan en la construccion")
    void parametrosInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, 0.1,
                new double[] {1.0, 1.0}, GeneradorAverias.REPARTO_POR_DEFECTO, true));
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, 0.1,
                new double[] {1.0, 1.0, -1.0}, GeneradorAverias.REPARTO_POR_DEFECTO, true));
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, 0.1,
                GeneradorAverias.FACTORES_POR_DEFECTO, new double[] {1.0, 1.0}, true));
        assertThrows(IllegalArgumentException.class, () -> new GeneradorAverias(1L, 0.1,
                GeneradorAverias.FACTORES_POR_DEFECTO, new double[] {0.0, 0.0, 0.0}, true));
    }

    @Test
    @DisplayName("Los factores y el reparto son parametricos y se devuelven en copia")
    void reglasParametricas() {
        double[] reparto = {0.5, 0.3, 0.2};
        GeneradorAverias generador = new GeneradorAverias(3L, 0.20,
                new double[] {2.0, 1.0, 0.5}, reparto, true);

        assertEquals(0.40, generador.probabilidad(TipoUnidad.AUTO), 1e-12);
        assertEquals(0.20, generador.probabilidad(TipoUnidad.MOTO), 1e-12);
        assertEquals(0.10, generador.probabilidad(TipoUnidad.BICICLETA), 1e-12);
        assertEquals(2.0, generador.factor(TipoUnidad.AUTO), 1e-12);

        double[] copia = generador.reparto();
        copia[0] = 99.0;
        assertEquals(0.5, generador.reparto()[0], 1e-12, "la copia no puede alterar el generador");
        reparto[0] = 99.0;
        assertEquals(0.5, generador.reparto()[0], 1e-12, "el arreglo del llamante tampoco");
    }
}
