package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Orden y clausura de los mapas de los indicadores acumulados.
 *
 * <p>Los cuatro mapas del record se copiaban con {@code Map.copyOf}, cuyo orden de iteracion
 * depende de una semilla que la maquina virtual sortea en cada arranque: el mismo escenario con
 * los mismos numeros producia un JSON con las claves en un orden distinto en cada ejecucion del
 * servicio, y con el las columnas del panel de metricas bailaban. La copia conserva ahora el
 * orden con que se entregan los mapas, que es el del enumerado para los tipos de unidad y el
 * creciente para plazos y tipos de averia.</p>
 */
class MetricasSimulacionTest {

    private static MetricasSimulacion con(Map<String, Integer> kilometros, Map<Integer, Double> minutos,
                                          Map<Integer, Integer> entregas, Map<Integer, Integer> averias) {
        return new MetricasSimulacion(0, 0, 0, 0, 0, 0, 0.0, kilometros, minutos, entregas,
                List.of(), averias, 0L, 0L);
    }

    @Test
    @DisplayName("Los cuatro mapas conservan el orden con que se entregan")
    void conservanElOrdenDeEntrega() {
        Map<String, Integer> kilometros = new LinkedHashMap<>();
        kilometros.put("AUTO", 120);
        kilometros.put("MOTO", 80);
        kilometros.put("BICICLETA", 15);
        Map<Integer, Double> minutos = new TreeMap<>(Map.of(4, 90.0, 12, 300.0, 36, 900.0));
        Map<Integer, Integer> entregas = new TreeMap<>(Map.of(4, 3, 12, 7, 36, 11));
        Map<Integer, Integer> averias = new TreeMap<>(Map.of(1, 5, 2, 2, 3, 1));

        MetricasSimulacion m = con(kilometros, minutos, entregas, averias);

        assertEquals(List.of("AUTO", "MOTO", "BICICLETA"), new ArrayList<>(m.kilometrosPorTipo().keySet()),
                "los tipos de unidad van en el orden del enumerado");
        assertEquals(List.of(4, 12, 36), new ArrayList<>(m.minutosEntregaPorPlazo().keySet()),
                "los plazos van en orden creciente");
        assertEquals(List.of(4, 12, 36), new ArrayList<>(m.entregasPorPlazo().keySet()),
                "los plazos de las entregas van en orden creciente");
        assertEquals(List.of(1, 2, 3), new ArrayList<>(m.averiasPorTipo().keySet()),
                "los tipos de averia van en orden creciente");
        assertEquals(215, m.kilometrosTotales(), "kilometros totales de la flota");
    }

    @Test
    @DisplayName("El orden no depende del orden del mapa que se recibe")
    void elOrdenEsElDelProductor() {
        Map<String, Integer> alReves = new LinkedHashMap<>();
        alReves.put("BICICLETA", 15);
        alReves.put("MOTO", 80);
        alReves.put("AUTO", 120);

        MetricasSimulacion m = con(alReves, Map.of(), Map.of(), Map.of());

        assertEquals(List.of("BICICLETA", "MOTO", "AUTO"), new ArrayList<>(m.kilometrosPorTipo().keySet()),
                "el record respeta el orden que le dan y no lo sortea");
    }

    @Test
    @DisplayName("Los mapas que salen del record no se pueden modificar")
    void losMapasSonInmutables() {
        MetricasSimulacion m = con(new LinkedHashMap<>(Map.of("AUTO", 1)), Map.of(), Map.of(), Map.of());

        assertThrows(UnsupportedOperationException.class, () -> m.kilometrosPorTipo().put("MOTO", 2),
                "kilometros por tipo");
        assertThrows(UnsupportedOperationException.class, () -> m.minutosEntregaPorPlazo().put(4, 1.0),
                "minutos de entrega por plazo");
        assertThrows(UnsupportedOperationException.class, () -> m.entregasPorPlazo().put(4, 1),
                "entregas por plazo");
        assertThrows(UnsupportedOperationException.class, () -> m.averiasPorTipo().put(1, 1),
                "averias por tipo");
    }

    @Test
    @DisplayName("La copia es defensiva: cambiar el mapa de origen no altera las metricas")
    void laCopiaEsDefensiva() {
        Map<String, Integer> origen = new LinkedHashMap<>();
        origen.put("AUTO", 10);
        MetricasSimulacion m = con(origen, Map.of(), Map.of(), Map.of());

        origen.put("MOTO", 99);

        assertEquals(1, m.kilometrosPorTipo().size(), "las metricas no ven el cambio posterior");
        assertEquals(10, m.kilometrosTotales(), "kilometros totales tras el cambio del origen");
    }
}
