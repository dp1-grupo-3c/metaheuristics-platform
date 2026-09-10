package org.kindbox.core.metaheuristica.hgs;

import org.kindbox.core.util.Aleatorio;

/**
 * Cruce ordenado OX sobre el cromosoma de la busqueda genetica hibrida, conforme al
 * apartado 6.3.3 del ISA.
 *
 * <p>OX es el operador que Prins (2004) y Vidal y otros (2012) usan sobre la permutacion sin
 * delimitadores, y la razon es que en un problema de enrutamiento lo que hereda un
 * descendiente no son posiciones absolutas sino <b>vecindades</b>: que el pedido A se visite
 * justo antes del B es informacion util, que se visite en la posicion diecisiete no lo es.
 * OX copia un tramo contiguo del primer progenitor conservando sus posiciones y completa el
 * resto recorriendo el segundo progenitor de forma circular a partir del final del tramo, de
 * modo que preserva el orden relativo heredado de cada progenitor y nunca produce
 * repeticiones ni ausencias.</p>
 *
 * <p>El vector de tipos de unidad se hereda <b>gen a gen</b>: cada tarea toma el tipo que le
 * asigna uno u otro progenitor con igual probabilidad. Heredarlo en bloque junto con el
 * tramo copiado ataria el reparto por tipo al orden de visita, cuando son dos decisiones
 * separables; heredarlo gen a gen deja que la seleccion combine el orden de un progenitor
 * con el reparto por tipo del otro. Los bits de decision se sacan de una sola palabra de 64
 * bits del generador, que se repone cada 64 genes, para no gastar una llamada por gen.</p>
 *
 * <p>El operador no asigna memoria: escribe sobre el descendiente que recibe y usa un
 * arreglo de marcas de instancia con sello incremental, que evita tener que borrarlo entre
 * dos cruces.</p>
 */
public final class CruceOrdenado {

    private final Aleatorio aleatorio;
    private final int[] marca;
    private int sello;

    /**
     * @param aleatorio      generador de la corrida
     * @param cantidadTareas longitud del cromosoma
     */
    public CruceOrdenado(Aleatorio aleatorio, int cantidadTareas) {
        this.aleatorio = aleatorio;
        this.marca = new int[Math.max(1, cantidadTareas)];
    }

    /**
     * Cruza los dos progenitores y deja el resultado en el descendiente.
     *
     * @param primero     progenitor que aporta el tramo contiguo
     * @param segundo     progenitor que aporta el orden del resto
     * @param descendiente individuo sobre el que se escribe el cromosoma resultante
     */
    public void cruzar(Individuo primero, Individuo segundo, Individuo descendiente) {
        final int n = primero.longitud();
        final int[] origen = primero.permutacion();
        final int[] complemento = segundo.permutacion();
        final int[] destino = descendiente.permutacion();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            destino[0] = origen[0];
            heredarTipos(primero, segundo, descendiente);
            return;
        }

        int corteInicial = aleatorio.siguienteEntero(n);
        int corteFinal = aleatorio.siguienteEntero(n);
        if (corteFinal < corteInicial) {
            int intercambio = corteInicial;
            corteInicial = corteFinal;
            corteFinal = intercambio;
        }

        sello++;
        for (int i = corteInicial; i <= corteFinal; i++) {
            destino[i] = origen[i];
            marca[origen[i]] = sello;
        }

        // El relleno arranca justo despues del tramo copiado, en ambos progenitores, que es
        // lo que conserva el orden relativo del segundo.
        int posicion = (corteFinal + 1) % n;
        int lectura = (corteFinal + 1) % n;
        int colocados = corteFinal - corteInicial + 1;
        while (colocados < n) {
            int candidato = complemento[lectura];
            lectura++;
            if (lectura == n) {
                lectura = 0;
            }
            if (marca[candidato] == sello) {
                continue;
            }
            destino[posicion] = candidato;
            marca[candidato] = sello;
            posicion++;
            if (posicion == n) {
                posicion = 0;
            }
            colocados++;
        }

        heredarTipos(primero, segundo, descendiente);
    }

    /** Herencia gen a gen del vector de tipos de unidad. */
    private void heredarTipos(Individuo primero, Individuo segundo, Individuo descendiente) {
        final int n = primero.longitud();
        final byte[] tipoPrimero = primero.tipo();
        final byte[] tipoSegundo = segundo.tipo();
        final byte[] destino = descendiente.tipo();
        long bits = 0L;
        int restantes = 0;
        for (int t = 0; t < n; t++) {
            if (restantes == 0) {
                bits = aleatorio.siguienteLong();
                restantes = 64;
            }
            destino[t] = (bits & 1L) == 0L ? tipoPrimero[t] : tipoSegundo[t];
            bits >>>= 1;
            restantes--;
        }
    }
}
