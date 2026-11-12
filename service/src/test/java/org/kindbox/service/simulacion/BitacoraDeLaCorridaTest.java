package org.kindbox.service.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.simulacion.ColorSemaforo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.service.dto.EventoCorrida;
import org.kindbox.service.dto.EventoCorrida.Tono;

// La seccion "Eventos de la simulacion" del resumen sale de esta bitacora.
class BitacoraDeLaCorridaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 11, 12);
    private static final ConfiguracionEscenario CONFIGURACION =
            ConfiguracionEscenario.simulacion5D(DIA, 30, 30, "ALNS", 1L);

    private static Corrida corrida() {
        RepositorioDatos.DatosEscenario datos = new RepositorioDatos.DatosEscenario(CalendarioEscenario.desde(DIA),
                DIA, DIA.plusDays(4), null, List.of(), List.of(), List.of(), List.of());
        return new Corrida("sim-001", CONFIGURACION, datos, 30);
    }

    @Test
    @DisplayName("Anota en orden los hechos de la corrida con su fecha simulada y su gravedad")
    void anotaLosHechosDeLaCorrida() {
        Corrida corrida = corrida();
        corrida.alReplanificar(600, "ALNS", 40, 0, 100.0, 5);
        corrida.alAveriarse(700, "TA03", 2, 30, 14, 12);
        corrida.alCambiarSemaforo(800, "Almacen Intermedio Este", ColorSemaforo.AMBAR, 400);
        corrida.alReplanificar(3510, "ALNS", 80, 1, 100.0, 5);
        corrida.alReplanificar(3540, "ALNS", 80, 1, 100.0, 5);
        corrida.alIncumplirPedido(4765, 2816, 8);
        corrida.alTerminar(new ResultadoSimulacion(CONFIGURACION, EstadoCorrida.COLAPSADA, 4765,
                LocalDateTime.of(2026, 11, 15, 7, 25), 1000, null, 4765, 2816, "colapso"));

        assertEquals(List.of(
                new EventoCorrida(LocalDateTime.of(2026, 11, 12, 11, 40), Tono.ROJO,
                        "Avería tipo 2 de TA03 en (30, 14) con 12 paquetes a bordo"),
                new EventoCorrida(LocalDateTime.of(2026, 11, 12, 13, 20), Tono.AMBAR,
                        "Almacen Intermedio Este pasó a ámbar con 400 paquetes disponibles"),
                new EventoCorrida(LocalDateTime.of(2026, 11, 14, 10, 30), Tono.AMBAR,
                        "La planificación con ALNS dejó 1 pedido sin asignar"),
                new EventoCorrida(LocalDateTime.of(2026, 11, 15, 7, 25), Tono.ROJO,
                        "El pedido 2816 venció su plazo con 8 paquetes sin entregar"),
                new EventoCorrida(LocalDateTime.of(2026, 11, 15, 7, 25), Tono.ROJO, "Colapso de operaciones")),
                corrida.eventos());
    }

    @Test
    @DisplayName("Los pedidos sin asignar se anotan al aparecer y al resolverse, no en cada replanificacion")
    void soloLosCambiosDeSinAsignar() {
        Corrida corrida = corrida();
        corrida.alReplanificar(30, "HGS", 10, 2, 0.0, 1);
        corrida.alReplanificar(60, "HGS", 10, 3, 0.0, 1);
        corrida.alReplanificar(90, "HGS", 10, 0, 0.0, 1);
        corrida.alReplanificar(120, "HGS", 10, 0, 0.0, 1);

        assertEquals(List.of("La planificación con HGS dejó 2 pedidos sin asignar",
                        "La planificación volvió a asignar todos los pedidos"),
                corrida.eventos().stream().map(EventoCorrida::mensaje).toList());
    }

    @Test
    @DisplayName("La bitacora se recorta descartando los eventos mas antiguos")
    void seRecortaPorLoMasAntiguo() {
        Corrida corrida = corrida();
        for (int i = 0; i < Corrida.MAXIMO_EVENTOS + 5; i++) {
            corrida.alIncumplirPedido(i, i, 1);
        }
        List<EventoCorrida> eventos = corrida.eventos();
        assertEquals(Corrida.MAXIMO_EVENTOS, eventos.size());
        assertEquals("El pedido 5 venció su plazo con 1 paquete sin entregar", eventos.get(0).mensaje());
    }
}
