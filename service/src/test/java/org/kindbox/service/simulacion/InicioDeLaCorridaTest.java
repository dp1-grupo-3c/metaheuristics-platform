package org.kindbox.service.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.datagen.GeneradorBloqueos;
import org.kindbox.core.datagen.GeneradorMantenimiento;
import org.kindbox.core.datagen.GeneradorVentas;
import org.kindbox.core.datagen.PerfilDemanda;
import org.kindbox.core.io.LectorFlota;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.kindbox.service.dto.ResumenCorrida;
import org.kindbox.service.dto.SolicitudSimulacion;
import org.kindbox.service.error.SolicitudInvalida;

class InicioDeLaCorridaTest {

    static final YearMonth MES = YearMonth.of(2026, 9);

    @TempDir
    Path raiz;
    ServicioSimulacion servicio;

    @BeforeEach
    void preparar() throws IOException {
        new GeneradorVentas(PerfilDemanda.LIGERO.conPedidosPorDiaInicial(8.0), MES)
                .escribirMes(MES, raiz.resolve(RepositorioDatos.DIRECTORIO_VENTAS));
        new GeneradorBloqueos(7L, 1.0).escribirMes(MES, raiz.resolve(RepositorioDatos.DIRECTORIO_BLOQUEOS));
        GeneradorMantenimiento.escribirPar(GeneradorMantenimiento.primerMesDelPar(MES),
                raiz.resolve(RepositorioDatos.DIRECTORIO_MANTENIMIENTO));
        Files.writeString(raiz.resolve(LectorFlota.NOMBRE_ARCHIVO), "TA01\nTM01\nTB01\n");
        PublicadorDeEstado publicador = new PublicadorDeEstado() {
            @Override
            public ObservadorSimulacion observadorDe(String id) {
                return new ObservadorSimulacion() { };
            }

            @Override
            public void publicar(String tipo, String id, Object carga) {
            }
        };
        servicio = new ServicioSimulacion(new RepositorioDatos(raiz), new ParametrosOperacion(), new FabricaMotor(),
                new RegistroAlgoritmos(), publicador, new PropiedadesKindBox());
    }

    @AfterEach
    void cerrar() {
        servicio.cerrar();
    }

    static SolicitudSimulacion solicitud(LocalDate primerDia, LocalDateTime inicio) {
        return new SolicitudSimulacion(TipoEscenario.SIMULACION_5D, primerDia, null, 30, null, null, null, null,
                false, inicio);
    }

    @Test
    void laCorridaArrancaEnLaFechaYHoraPedidas() {
        ResumenCorrida resumen = servicio.arrancar(solicitud(null, LocalDateTime.of(2026, 9, 2, 10, 30)));
        try {
            assertEquals(LocalDate.of(2026, 9, 2), resumen.primerDia());
            assertEquals(LocalDate.of(2026, 9, 6), resumen.ultimoDia(), "cinco dias desde el arranque");
            assertEquals(LocalDateTime.of(2026, 9, 2, 10, 30), resumen.fechaHoraInicio());
            assertEquals(630L, resumen.minutoInicial());
            assertEquals(630L + 5 * 1440L, resumen.minutosHorizonte(), "el cierre se corre con el arranque");
            assertTrue(resumen.avance() < 0.01, "recien arrancada el avance es casi cero: " + resumen.avance());
        } finally {
            servicio.cancelar(resumen.id());
        }
    }

    @Test
    void rechazaUnInicioConSegundosOFueraDelPrimerDia() {
        assertThrows(SolicitudInvalida.class,
                () -> servicio.arrancar(solicitud(null, LocalDateTime.of(2026, 9, 2, 10, 30, 15))));
        assertThrows(SolicitudInvalida.class,
                () -> servicio.arrancar(solicitud(LocalDate.of(2026, 9, 1), LocalDateTime.of(2026, 9, 2, 10, 30))));
    }
}
