package org.kindbox.experiments;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import org.kindbox.core.evaluacion.PruebaFactibilidadIndependiente;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.ParametrosOperacion;

/**
 * Ejecuta el filtro de factibilidad independiente, sin crear ningun algoritmo.
 *
 * <p>Uso: {@code VerificarFactibilidad <raizDatos> <primerDia> <dias>}. El informe separa
 * imposibilidad individual de pedidos que la politica conservadora no logra programar.</p>
 */
public final class VerificarFactibilidad {

    private VerificarFactibilidad() {
    }

    public static void main(String[] argumentos) throws Exception {
        Path raiz = Path.of(argumentos.length > 0 ? argumentos[0] : "data");
        LocalDate primerDia = LocalDate.parse(argumentos.length > 1 ? argumentos[1] : "2026-09-01");
        int dias = argumentos.length > 2 ? Integer.parseInt(argumentos[2]) : 5;
        if (dias <= 0) {
            throw new IllegalArgumentException("La cantidad de dias debe ser positiva");
        }
        LocalDate ultimoDia = primerDia.plusDays(dias - 1L);
        RepositorioDatos.DatosEscenario datos = new RepositorioDatos(raiz).cargar(primerDia, ultimoDia);
        PruebaFactibilidadIndependiente.Resultado resultado =
                PruebaFactibilidadIndependiente.evaluar(datos, new ParametrosOperacion());

        System.out.printf(Locale.ROOT,
                "PRUEBA INDEPENDIENTE %s..%s%n  pedidos=%d flota=%d bloqueos=%d mantenimiento=%d%n",
                primerDia, ultimoDia, resultado.pedidos(), datos.unidades().size(),
                datos.bloqueos().size(), datos.mantenimientos().size());
        System.out.printf(Locale.ROOT,
                "  resultado=%s imposiblesIndividualmente=%d noProgramables=%d%n",
                resultado.pasa() ? "PASA_FILTRO_OPTIMISTA" : "NO_CERTIFICADO",
                resultado.individualesImposibles(), resultado.noProgramables());
        for (PruebaFactibilidadIndependiente.Diagnostico diagnostico : resultado.diagnosticos()) {
            System.out.printf(Locale.ROOT, "  pedido=%d %s: %s%n",
                    diagnostico.pedido(), diagnostico.codigo(), diagnostico.detalle());
        }
        if (resultado.pasa()) {
            System.out.println("  Lectura: no se encontro un cuello de botella bajo la prueba independiente.");
            System.out.println("  Advertencia: bloqueos, mantenimiento, turnos e interaccion de rutas "
                    + "aun deben validarse en la simulacion.");
        } else if (resultado.individualesImposibles() > 0) {
            System.out.println("  Lectura: hay pedidos fisicamente imposibles incluso bajo supuestos optimistas.");
        } else {
            System.out.println("  Lectura: la instancia no queda certificada por esta politica; "
                    + "no demuestra imposibilidad matematica.");
        }
    }
}
