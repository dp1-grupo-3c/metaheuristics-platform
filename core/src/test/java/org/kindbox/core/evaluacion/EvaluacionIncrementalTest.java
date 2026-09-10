package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Ruta critica del apartado 14 del ISA, criterio de cierre de la etapa 2: prueba de
 * equivalencia entre la evaluacion incremental y la evaluacion completa sobre mil
 * movimientos aleatorios.
 *
 * <p>La evaluacion <b>completa</b> recorre la secuencia resultante de principio a fin,
 * encadenando parada a parada con {@link DatosSecuencia#concatenar}. La evaluacion
 * <b>incremental</b> combina en tiempo constante los resumenes de bloques de la secuencia
 * original, precalculados antes del movimiento, sin volver a recorrer nada: es exactamente
 * el mecanismo del que dependen la busqueda local de HGS (apartado 6.3.3) y los operadores
 * de insercion de ALNS (apartado 7.3.2). Si ambas evaluaciones no coincidieran, todos los
 * movimientos que esos algoritmos aceptan estarian valorados con numeros que no
 * corresponden a la ruta que se construye despues.</p>
 *
 * <p>Se ejercitan los tres movimientos del vecindario: reubicacion, intercambio e inversion
 * de un tramo, este ultimo el que produce el operador 2-opt. Se comparan los siete campos
 * del resumen, no solo la duracion, porque la ventana de arranque admisible es la parte del
 * resumen que hace correcta la siguiente concatenacion.</p>
 *
 * <p>Una segunda prueba comprueba que el resumen describe de verdad el recorrido: se simula
 * la ruta minuto a minuto, con esperas y con absorcion de los incumplimientos de plazo, y
 * se exige que el instante de fin y el desfase total coincidan con los del resumen.</p>
 */
class EvaluacionIncrementalTest {

    /** Movimientos aleatorios que exige el apartado 14 del ISA. */
    private static final int MOVIMIENTOS = 1000;
    /** Secuencias base distintas sobre las que se reparten los movimientos. */
    private static final int SECUENCIAS_BASE = 25;
    /** Pedidos de la instancia de prueba. */
    private static final int PEDIDOS = 14;

    private final InstanciaPlanificacion instancia = InstanciasDePrueba.instanciaVariada(PEDIDOS);
    private final TipoUnidad tipo = instancia.unidadTipo(0);
    private final int minutosServicio = instancia.parametros().minutosAcondicionamiento();

    @Test
    @DisplayName("Mil movimientos aleatorios: la evaluacion incremental coincide con la completa")
    void equivalenciaSobreMilMovimientos() {
        Aleatorio aleatorio = new Aleatorio(20260914L);
        int[] base = new int[PEDIDOS];
        int movimientosPorBase = MOVIMIENTOS / SECUENCIAS_BASE;
        int aplicados = 0;
        int conDesfase = 0;

        for (int b = 0; b < SECUENCIAS_BASE; b++) {
            for (int i = 0; i < PEDIDOS; i++) {
                base[i] = i;
            }
            aleatorio.barajar(base);
            Tablas tablas = new Tablas(base);

            for (int m = 0; m < movimientosPorBase; m++) {
                int[] resultado;
                Trozo incremental;
                switch (aleatorio.siguienteEntero(3)) {
                    case 0 -> {
                        int p = aleatorio.siguienteEntero(PEDIDOS);
                        int destino = aleatorio.siguienteEntero(PEDIDOS);
                        resultado = reubicar(base, p, destino);
                        incremental = tablas.reubicar(p, destino);
                    }
                    case 1 -> {
                        int p = aleatorio.siguienteEntero(PEDIDOS);
                        int q = aleatorio.siguienteEntero(PEDIDOS);
                        resultado = intercambiar(base, p, q);
                        incremental = tablas.intercambiar(Math.min(p, q), Math.max(p, q));
                    }
                    default -> {
                        int p = aleatorio.siguienteEntero(PEDIDOS);
                        int q = aleatorio.siguienteEntero(PEDIDOS);
                        resultado = invertir(base, Math.min(p, q), Math.max(p, q));
                        incremental = tablas.invertir(Math.min(p, q), Math.max(p, q));
                    }
                }

                DatosSecuencia completa = evaluacionCompleta(resultado);
                comparar(completa, incremental.datos, resultado, aplicados);
                if (completa.desfase() > 0) {
                    conDesfase++;
                }
                aplicados++;
            }
        }

        assertEquals(MOVIMIENTOS, aplicados, "deben evaluarse los mil movimientos del apartado 14");
        // Sin desfase la prueba solo recorreria el camino facil de la concatenacion.
        assertTrue(conDesfase > MOVIMIENTOS / 10,
                "la instancia debe producir incumplimientos de plazo en una fraccion apreciable"
                        + " de los movimientos, y solo produjo " + conDesfase);
    }

    @Test
    @DisplayName("El resumen de una secuencia describe el recorrido real de la ruta")
    void elResumenCoincideConElRecorridoMinutoAMinuto() {
        Aleatorio aleatorio = new Aleatorio(4242L);
        int[] secuencia = new int[PEDIDOS];
        for (int intento = 0; intento < 200; intento++) {
            for (int i = 0; i < PEDIDOS; i++) {
                secuencia[i] = i;
            }
            aleatorio.barajar(secuencia);
            int longitud = 1 + aleatorio.siguienteEntero(PEDIDOS);
            int[] recorte = new int[longitud];
            System.arraycopy(secuencia, 0, recorte, 0, longitud);

            DatosSecuencia resumen = evaluacionCompleta(recorte);
            Recorrido recorrido = recorrer(recorte, resumen.inicioMasTemprano());

            assertEquals(resumen.inicioMasTemprano() + resumen.duracion() - resumen.desfase(),
                    recorrido.minutoFin,
                    "el instante de fin del recorrido debe salir del resumen, intento " + intento);
            assertEquals(resumen.desfase(), recorrido.desfase,
                    "el desfase acumulado debe coincidir con el del resumen, intento " + intento);
            assertEquals(resumen.kilometros(), recorrido.kilometros,
                    "los kilometros deben coincidir, intento " + intento);
            assertEquals(resumen.carga(), recorrido.carga,
                    "la carga entregada debe coincidir, intento " + intento);
            assertEquals(longitud, resumen.paradas(), "el resumen debe contar todas las paradas");
        }
    }

    // ------------------------------------------------------- evaluacion completa

    /** Recorre la secuencia entera encadenando parada a parada. Es el costo lineal. */
    private DatosSecuencia evaluacionCompleta(int[] secuencia) {
        DatosSecuencia acumulado = parada(secuencia[0]);
        for (int i = 1; i < secuencia.length; i++) {
            acumulado = DatosSecuencia.concatenar(acumulado, parada(secuencia[i]),
                    viaje(secuencia[i - 1], secuencia[i]), kilometros(secuencia[i - 1], secuencia[i]));
        }
        return acumulado;
    }

    /**
     * Simulacion explicita de la ruta: se avanza minuto a minuto por las paradas, se espera
     * cuando el pedido aun no ha llegado y se absorbe el incumplimiento de plazo, que es lo
     * que el resumen llama desfase.
     */
    private Recorrido recorrer(int[] secuencia, int inicio) {
        long instante = inicio;
        long desfase = 0;
        int kilometros = 0;
        int carga = 0;
        for (int i = 0; i < secuencia.length; i++) {
            if (i > 0) {
                instante += viaje(secuencia[i - 1], secuencia[i]);
                kilometros += kilometros(secuencia[i - 1], secuencia[i]);
            }
            long masTemprano = instancia.pedidoMinutoRegistro(secuencia[i]);
            if (instante < masTemprano) {
                instante = masTemprano;
            }
            long limite = instancia.pedidoMinutoLimite(secuencia[i]);
            if (instante > limite) {
                desfase += instante - limite;
                instante = limite;
            }
            carga += instancia.pedidoCantidad(secuencia[i]);
            instante += minutosServicio;
        }
        return new Recorrido(instante, desfase, kilometros, carga);
    }

    /** Resultado de la simulacion explicita. */
    private record Recorrido(long minutoFin, long desfase, int kilometros, int carga) {
    }

    // ---------------------------------------------------- evaluacion incremental

    /**
     * Resumen de un bloque contiguo de la secuencia base, con el pedido inicial y el final
     * para poder unirlo con el bloque vecino en tiempo constante.
     */
    private static final class Trozo {
        private final DatosSecuencia datos;
        private final int primero;
        private final int ultimo;

        Trozo(DatosSecuencia datos, int primero, int ultimo) {
            this.datos = datos;
            this.primero = primero;
            this.ultimo = ultimo;
        }

        static Trozo vacio() {
            return new Trozo(DatosSecuencia.VACIA, -1, -1);
        }

        boolean esVacio() {
            return datos.paradas() == 0;
        }
    }

    /** Une dos trozos contiguos con una sola concatenacion, en tiempo constante. */
    private Trozo unir(Trozo a, Trozo b) {
        if (a.esVacio()) {
            return b;
        }
        if (b.esVacio()) {
            return a;
        }
        DatosSecuencia datos = DatosSecuencia.concatenar(a.datos, b.datos,
                viaje(a.ultimo, b.primero), kilometros(a.ultimo, b.primero));
        return new Trozo(datos, a.primero, b.ultimo);
    }

    /**
     * Resumenes precalculados de todos los bloques contiguos de una secuencia base, en
     * orden directo y en orden inverso. Una vez construidos, cualquier movimiento del
     * vecindario se evalua uniendo a lo sumo cinco trozos, con independencia del tamano de
     * la secuencia.
     */
    private final class Tablas {
        private final int[] base;
        private final Trozo[][] directo;
        private final Trozo[][] inverso;

        Tablas(int[] base) {
            this.base = base.clone();
            int n = base.length;
            this.directo = new Trozo[n][n];
            this.inverso = new Trozo[n][n];
            for (int i = 0; i < n; i++) {
                directo[i][i] = new Trozo(parada(base[i]), base[i], base[i]);
                inverso[i][i] = directo[i][i];
            }
            for (int longitud = 2; longitud <= n; longitud++) {
                for (int i = 0; i + longitud - 1 < n; i++) {
                    int j = i + longitud - 1;
                    directo[i][j] = unir(directo[i][j - 1], directo[j][j]);
                    inverso[i][j] = unir(inverso[j][j], inverso[i][j - 1]);
                }
            }
        }

        /** Bloque {@code [desde, hasta]} en orden directo, vacio si el rango es vacio. */
        Trozo bloque(int desde, int hasta) {
            return desde > hasta || desde < 0 || hasta >= base.length ? Trozo.vacio() : directo[desde][hasta];
        }

        /** Bloque {@code [desde, hasta]} recorrido al reves. */
        Trozo bloqueInverso(int desde, int hasta) {
            return desde > hasta || desde < 0 || hasta >= base.length ? Trozo.vacio() : inverso[desde][hasta];
        }

        /**
         * Reubicacion del pedido de la posicion {@code p} en la posicion {@code destino} de
         * la secuencia que queda tras quitarlo.
         */
        Trozo reubicar(int p, int destino) {
            Trozo movido = bloque(p, p);
            int ultimo = base.length - 1;
            if (destino < p) {
                return unir(unir(unir(bloque(0, destino - 1), movido), bloque(destino, p - 1)),
                        bloque(p + 1, ultimo));
            }
            return unir(unir(unir(bloque(0, p - 1), bloque(p + 1, destino)), movido),
                    bloque(destino + 1, ultimo));
        }

        /** Intercambio de los pedidos de las posiciones {@code p} y {@code q}, con {@code p <= q}. */
        Trozo intercambiar(int p, int q) {
            if (p == q) {
                return bloque(0, base.length - 1);
            }
            return unir(unir(unir(unir(bloque(0, p - 1), bloque(q, q)), bloque(p + 1, q - 1)),
                    bloque(p, p)), bloque(q + 1, base.length - 1));
        }

        /** Inversion del tramo {@code [p,q]}, que es el movimiento del operador 2-opt. */
        Trozo invertir(int p, int q) {
            return unir(unir(bloque(0, p - 1), bloqueInverso(p, q)), bloque(q + 1, base.length - 1));
        }
    }

    // ------------------------------------------------- movimientos sobre arreglos

    /**
     * Aplica la reubicacion sobre el arreglo: quita el elemento de la posicion {@code p} y
     * lo vuelve a insertar en la posicion {@code destino} de la secuencia reducida.
     */
    private static int[] reubicar(int[] base, int p, int destino) {
        int[] reducido = new int[base.length - 1];
        int k = 0;
        for (int i = 0; i < base.length; i++) {
            if (i != p) {
                reducido[k++] = base[i];
            }
        }
        int[] resultado = new int[base.length];
        int escritos = 0;
        for (int i = 0; i < destino; i++) {
            resultado[escritos++] = reducido[i];
        }
        resultado[escritos++] = base[p];
        for (int i = destino; i < reducido.length; i++) {
            resultado[escritos++] = reducido[i];
        }
        return resultado;
    }

    /** Aplica el intercambio sobre el arreglo. */
    private static int[] intercambiar(int[] base, int p, int q) {
        int[] resultado = base.clone();
        int temporal = resultado[p];
        resultado[p] = resultado[q];
        resultado[q] = temporal;
        return resultado;
    }

    /** Aplica la inversion del tramo {@code [p,q]} sobre el arreglo. */
    private static int[] invertir(int[] base, int p, int q) {
        int[] resultado = base.clone();
        for (int i = p, j = q; i < j; i++, j--) {
            int temporal = resultado[i];
            resultado[i] = resultado[j];
            resultado[j] = temporal;
        }
        return resultado;
    }

    // ------------------------------------------------------------- utilitarios

    private DatosSecuencia parada(int pedido) {
        return DatosSecuencia.deParada((int) instancia.pedidoMinutoRegistro(pedido),
                (int) instancia.pedidoMinutoLimite(pedido), minutosServicio,
                instancia.pedidoCantidad(pedido));
    }

    private int viaje(int pedidoOrigen, int pedidoDestino) {
        return instancia.minutosDeViaje(tipo, instancia.puntoPedido(pedidoOrigen),
                instancia.puntoPedido(pedidoDestino));
    }

    private int kilometros(int pedidoOrigen, int pedidoDestino) {
        MatrizDistancias matriz = instancia.matriz();
        return matriz.km(instancia.puntoPedido(pedidoOrigen), instancia.puntoPedido(pedidoDestino));
    }

    private static void comparar(DatosSecuencia completa, DatosSecuencia incremental,
                                 int[] secuencia, int movimiento) {
        String contexto = " en el movimiento " + movimiento + " sobre la secuencia "
                + java.util.Arrays.toString(secuencia);
        assertEquals(completa.duracion(), incremental.duracion(), "duracion" + contexto);
        assertEquals(completa.desfase(), incremental.desfase(), "desfase" + contexto);
        assertEquals(completa.inicioMasTemprano(), incremental.inicioMasTemprano(),
                "inicio mas temprano" + contexto);
        assertEquals(completa.inicioMasTardio(), incremental.inicioMasTardio(),
                "inicio mas tardio" + contexto);
        assertEquals(completa.carga(), incremental.carga(), "carga" + contexto);
        assertEquals(completa.kilometros(), incremental.kilometros(), "kilometros" + contexto);
        assertEquals(completa.paradas(), incremental.paradas(), "paradas" + contexto);
    }
}
