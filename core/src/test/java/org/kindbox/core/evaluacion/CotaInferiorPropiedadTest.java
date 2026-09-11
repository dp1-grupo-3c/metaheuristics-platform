package org.kindbox.core.evaluacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.grafo.MatrizDistanciasReticula;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.hgs.Educacion;
import org.kindbox.core.metaheuristica.hgs.EstabilidadPlan;
import org.kindbox.core.metaheuristica.hgs.ParametrosHgs;
import org.kindbox.core.metaheuristica.hgs.TareasEntrega;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.util.Aleatorio;

/**
 * Prueba de propiedad del filtro de cota inferior del apartado 10 del ISA: intenta refutar
 * que la concatenacion de resumenes de {@link DatosSecuencia} acote por debajo lo que mide
 * {@link ProgramadorRuta}, que es la condicion de la que depende que el filtro no cambie
 * ninguna decision de HGS ni de ALNS (apartado 12.4).
 *
 * <p>No se conforma con las fotografias holgadas: ademas de las cuatro fijas de
 * {@link InstanciasDePrueba} genera por semilla fotografias con los casos en que el modelo de
 * la concatenacion y el del decodificador se separan. Hay unidades con carga a bordo y sin
 * ella, de modo que el decodificador abastece al inicio o a mitad de ruta con desvio;
 * almacenes intermedios con poco inventario, que obligan a desviarse al central; pedidos
 * repetidos en la secuencia, que son las entregas parciales; rutas vacias; tramos bloqueados
 * y nodos aislados, que dejan puntos inalcanzables; unidades que arrancan tarde, a pocos
 * minutos del cierre de su turno o con un horizonte explicito; los tres turnos del dia, el de
 * noche cruzando la medianoche; plazos holgados y ajustados; velocidades no enteras, que
 * cambian el redondeo de los minutos de viaje, y un plan vigente para el termino de
 * estabilidad.</p>
 *
 * <p>Sobre miles de secuencias aleatorias por unidad se exige siempre: el desfase de la
 * concatenacion no supera el del decodificador, los kilometros sin abastecimiento no superan
 * los del decodificador, un tramo directo sin camino implica que el decodificador tampoco
 * programa, y toda posicion que poda el filtro de ALNS es de verdad infactible. Para HGS se
 * invocan por reflexion los metodos privados reales de {@code Educacion}, la cota penalizada y
 * la valoracion con el decodificador, y se comprueba con umbrales elegidos en el borde que
 * ningun movimiento podado por las reglas de aceptacion, con su margen {@code EPSILON}, habria
 * sido aceptado con el valor exacto.</p>
 */
class CotaInferiorPropiedadTest {

    private static final long SEMILLA = 20260911L;
    /** Fotografias generadas al azar, ademas de las cuatro fijas. */
    private static final int INSTANCIAS_GENERADAS = 12;
    private static final int SECUENCIAS_POR_INSTANCIA = 500;
    /** Visitas de la ruta base; con la insertada, la secuencia no pasa de ocho. */
    private static final int LARGO_MAXIMO = 8;
    /** Aristas bloqueadas al azar en las fotografias con bloqueos, ademas de los nodos aislados. */
    private static final int ARISTAS_BLOQUEADAS = 1500;

    /** Contadores que prueban que la prueba recorrio los casos dificiles y no solo los faciles. */
    private static final class Cobertura {
        long decodificadas;
        long factibles;
        long conCotaPositiva;
        long conDesvio;
        long inalcanzables;
        long sinProgramacion;
        long parciales;
        long podadas;
        long podadasHgs;
        long conTerminoDesfase;
        boolean ultimaFactible;
    }

    // -------------------------------------------------------------------- ALNS

    @Test
    @DisplayName("La cota de desfase y kilometros no supera al decodificador y toda posicion podada es infactible")
    void cotaDeInsercionValida() {
        Cobertura cobertura = new Cobertura();
        Aleatorio aleatorio = new Aleatorio(SEMILLA);
        for (InstanciaPlanificacion instancia : instancias()) {
            recorrerInserciones(instancia, aleatorio, cobertura);
        }
        assertTrue(cobertura.decodificadas > 10_000, "deben decodificarse miles de secuencias y solo hubo "
                + cobertura.decodificadas);
        assertTrue(cobertura.factibles > 1_000, "debe haber secuencias factibles y solo hubo " + cobertura.factibles);
        assertTrue(cobertura.conDesvio > 1_000, "el decodificador debe desviarse a abastecer y solo lo hizo "
                + cobertura.conDesvio + " veces");
        assertTrue(cobertura.inalcanzables > 200, "deben aparecer tramos sin camino y solo hubo "
                + cobertura.inalcanzables);
        assertTrue(cobertura.parciales > 200, "deben aparecer entregas parciales y solo hubo " + cobertura.parciales);
        assertTrue(cobertura.podadas > 2_000, "el filtro debe podar posiciones y solo podo " + cobertura.podadas);
    }

    private static void recorrerInserciones(InstanciaPlanificacion instancia, Aleatorio aleatorio,
                                            Cobertura cobertura) {
        ProgramadorRuta programador = new ProgramadorRuta(instancia);
        ResumenesRuta resumenes = new ResumenesRuta(instancia);
        final int n = instancia.cantidadPedidos();
        int[] ruta = new int[LARGO_MAXIMO];
        int[] cantidadesRuta = new int[LARGO_MAXIMO];
        int[] insertada = new int[LARGO_MAXIMO + 1];
        int[] cantidades = new int[LARGO_MAXIMO + 1];

        for (int intento = 0; intento < SECUENCIAS_POR_INSTANCIA; intento++) {
            final int unidad = aleatorio.siguienteEntero(instancia.cantidadUnidades());
            final int capacidad = instancia.unidadCapacidad(unidad);
            final int longitud = longitudAlAzar(aleatorio, LARGO_MAXIMO - 1);
            for (int i = 0; i < longitud; i++) {
                if (i > 0 && aleatorio.conProbabilidad(0.2)) {
                    // Otra visita a un pedido ya presente: una entrega parcial.
                    ruta[i] = ruta[aleatorio.siguienteEntero(i)];
                    cobertura.parciales++;
                } else {
                    ruta[i] = aleatorio.siguienteEntero(n);
                }
                cantidadesRuta[i] = cantidadDeVisita(instancia, aleatorio, ruta[i], capacidad);
            }
            final int pedido = aleatorio.siguienteEntero(n);
            final int cantidad = cantidadDeVisita(instancia, aleatorio, pedido, capacidad);

            // Lo mismo que hace MotorInsercion.valorarUnidad: resumenes una vez por unidad y
            // una consulta en tiempo constante por posicion.
            resumenes.preparar(unidad, ruta, longitud);
            for (int posicion = 0; posicion <= longitud; posicion++) {
                int k = 0;
                for (int i = 0; i < posicion; i++) {
                    cantidades[k] = cantidadesRuta[i];
                    insertada[k++] = ruta[i];
                }
                cantidades[k] = cantidad;
                insertada[k++] = pedido;
                for (int i = posicion; i < longitud; i++) {
                    cantidades[k] = cantidadesRuta[i];
                    insertada[k++] = ruta[i];
                }
                final long cota = resumenes.desfaseInsertando(posicion, pedido);
                final long acumulada = comprobarSecuencia(programador, resumenes, unidad, insertada, cantidades, k,
                        cobertura);
                assertEquals(acumulada, cota, "la cota por prefijo y sufijo debe ser la acumulada");
                if (cota > 0L) {
                    // Regla del filtro de ALNS: se salta la posicion sin decodificarla.
                    cobertura.podadas++;
                    assertFalse(cobertura.ultimaFactible, "el filtro poda una posicion factible: unidad " + unidad
                            + ", posicion " + posicion + ", cota " + cota);
                }
            }
        }
    }

    /**
     * Resume la secuencia parada a parada, la decodifica y comprueba las relaciones entre las
     * dos. Deja en la cobertura si el decodificador la declaro factible.
     *
     * @return la cota del desfase, o {@link ResumenesRuta#INALCANZABLE}
     */
    private static long comprobarSecuencia(ProgramadorRuta programador, ResumenesRuta resumenes, int unidad,
                                           int[] pedidos, int[] cantidades, int longitud, Cobertura cobertura) {
        resumenes.iniciar(unidad);
        boolean alcanzable = true;
        for (int i = 0; i < longitud && alcanzable; i++) {
            alcanzable = resumenes.anadir(pedidos[i]);
        }
        final long cota = resumenes.cerrar();
        final int cotaKilometros = programador.cotaInferiorKilometros(unidad, pedidos, longitud);
        final boolean decodificada = programador.evaluar(unidad, pedidos, cantidades, longitud);
        cobertura.ultimaFactible = decodificada && programador.ultimaFactible();

        if (!alcanzable) {
            cobertura.inalcanzables++;
            assertEquals(ResumenesRuta.INALCANZABLE, cota, "un tramo sin camino debe dar la cota inalcanzable");
            assertEquals(MatrizDistancias.INALCANZABLE, cotaKilometros, "la cota de kilometros debe coincidir");
            assertFalse(decodificada, "el decodificador programa una secuencia con un tramo directo sin camino");
            return cota;
        }
        assertEquals(cotaKilometros, resumenes.kilometrosAcumulados(),
                "los kilometros acumulados deben ser la cota de kilometros del decodificador");
        if (!decodificada) {
            cobertura.sinProgramacion++;
            return cota;
        }
        cobertura.decodificadas++;
        assertTrue(cota <= programador.ultimoDesfase(), "la cota " + cota + " supera el desfase del decodificador "
                + programador.ultimoDesfase() + " en la unidad " + unidad);
        assertTrue(resumenes.kilometrosAcumulados() <= programador.ultimosKilometros(), "los kilometros "
                + resumenes.kilometrosAcumulados() + " superan los del decodificador "
                + programador.ultimosKilometros());
        if (programador.ultimaFactible()) {
            cobertura.factibles++;
            assertEquals(0L, cota, "una secuencia factible no puede tener cota positiva");
        }
        if (programador.ultimosKilometros() > resumenes.kilometrosAcumulados()) {
            cobertura.conDesvio++;
        }
        if (cota > 0L) {
            cobertura.conCotaPositiva++;
        }
        return cota;
    }

    /**
     * Longitud entre cero y el maximo dado, sesgada hacia rutas cortas: con una hora de
     * acondicionamiento por visita, una ruta larga casi nunca es factible y la propiedad de que
     * una ruta factible tenga cota cero se quedaria sin casos.
     */
    private static int longitudAlAzar(Aleatorio aleatorio, int maximo) {
        return aleatorio.conProbabilidad(0.5)
                ? aleatorio.siguienteEntero(Math.min(3, maximo) + 1)
                : aleatorio.siguienteEntero(maximo + 1);
    }

    /** Cantidad de una visita, entre una unidad y lo que admiten el pedido y la capacidad. */
    private static int cantidadDeVisita(InstanciaPlanificacion instancia, Aleatorio aleatorio, int pedido,
                                        int capacidad) {
        return 1 + aleatorio.siguienteEntero(Math.min(capacidad, instancia.pedidoCantidad(pedido)));
    }

    // --------------------------------------------------------------------- HGS

    @Test
    @DisplayName("La cota penalizada de HGS nunca supera el valor exacto ni poda un movimiento que se aceptaria")
    void cotaPenalizadaDeHgsValida() throws ReflectiveOperationException {
        final double epsilon = constante("EPSILON");
        final double infinito = constante("INFINITO");
        final double noAtendida = ParametrosHgs.PENALIZACION_TAREA_NO_ATENDIDA;
        final double[] pesosEstabilidad = {ParametrosHgs.porDefecto().pesoEstabilidad(), 0.0, 350.0};
        Cobertura cobertura = new Cobertura();
        Aleatorio aleatorio = new Aleatorio(SEMILLA + 1L);
        int indice = 0;

        for (InstanciaPlanificacion instancia : instancias()) {
            ParametrosHgs parametros = ParametrosHgs.porDefecto();
            TareasEntrega tareas = new TareasEntrega(instancia, parametros.granularidadVecindario());
            EstabilidadPlan estabilidad = new EstabilidadPlan(instancia, tareas,
                    pesosEstabilidad[indice++ % pesosEstabilidad.length]);
            EspejoEducacion espejo = new EspejoEducacion(new Educacion(instancia, tareas,
                    new ProgramadorRuta(instancia), new Aleatorio(1L), parametros, PresupuestoComputo.deIteraciones(1L),
                    estabilidad));
            final double[] pesos = {parametros.penalizacionDesfaseMinima(), parametros.penalizacionDesfaseInicial(),
                    parametros.penalizacionDesfaseMaxima(), 1.0 / 3.0, 0.0};
            final int cantidadTareas = tareas.cantidad();
            int[] orden = new int[cantidadTareas];
            int[] secuencia = new int[LARGO_MAXIMO];
            double cotaPrevia = 0.0;
            double exactoPrevio = 0.0;

            for (int intento = 0; intento < SECUENCIAS_POR_INSTANCIA * 3; intento++) {
                for (int i = 0; i < cantidadTareas; i++) {
                    orden[i] = i;
                }
                aleatorio.barajar(orden);
                final int longitud = longitudAlAzar(aleatorio, Math.min(LARGO_MAXIMO, cantidadTareas));
                System.arraycopy(orden, 0, secuencia, 0, longitud);
                final int unidad = aleatorio.siguienteEntero(instancia.cantidadUnidades());
                pesos[4] = aleatorio.siguienteDouble(1e-3, 2e4);
                final double peso = pesos[aleatorio.siguienteEntero(pesos.length)];

                final double cota = espejo.cota(unidad, secuencia, longitud, peso);
                final double cotaSinDesfase = espejo.cotaSinDesfase();
                final double exacto = espejo.valor(unidad, secuencia, longitud, peso);
                if (cota >= infinito) {
                    cobertura.inalcanzables++;
                    assertTrue(exacto >= infinito, "la cota declara inalcanzable una secuencia que se programa");
                    continue;
                }
                if (exacto >= infinito) {
                    cobertura.sinProgramacion++;
                    continue;
                }
                cobertura.decodificadas++;
                assertTrue(cota <= exacto, "la cota " + cota + " supera el valor exacto " + exacto + " con peso "
                        + peso + " en la unidad " + unidad);
                if (cota > cotaSinDesfase) {
                    cobertura.conTerminoDesfase++;
                }

                // Umbrales en el borde: justo donde la poda empieza o deja de actuar.
                final double[] umbrales = {cota, exacto, cota + epsilon, Math.nextUp(cota + epsilon),
                        Math.nextDown(exacto + epsilon), exacto + epsilon, 0.5 * (cota + exacto) + epsilon};
                for (double valorActual : umbrales) {
                    // Una sola ruta: reubicar, intercambiar, dos-opt y reasignar unidades.
                    final double actual = valorActual - epsilon;
                    if (cota >= actual) {
                        cobertura.podadasHgs++;
                        assertTrue(exacto >= actual, "poda de una ruta que el valor exacto aceptaria");
                    }
                    // Retirada al banco.
                    if (cota + noAtendida >= actual) {
                        assertTrue(exacto + noAtendida >= actual, "poda de una retirada que se aceptaria");
                    }
                    // Reinsercion del banco en una ruta con valor actual y mejor incremento dado.
                    final double[] incrementos = {cota - valorActual + epsilon, exacto - valorActual + epsilon,
                            noAtendida};
                    for (double mejorIncremento : incrementos) {
                        if (cota - valorActual >= mejorIncremento - epsilon) {
                            assertTrue(exacto - valorActual >= mejorIncremento - epsilon,
                                    "poda de una reinsercion que se aceptaria");
                        }
                        if (cota >= mejorIncremento - epsilon) {
                            assertTrue(exacto >= mejorIncremento - epsilon,
                                    "poda de una apertura de ruta que se aceptaria");
                        }
                    }
                    // Dos rutas, con la secuencia anterior como primera: la poda por la primera
                    // sola y la poda por la suma de las dos.
                    final double actualDos = cotaPrevia + valorActual - epsilon;
                    if (cotaPrevia >= actualDos || cotaPrevia + cota >= actualDos) {
                        assertTrue(exactoPrevio + exacto >= actualDos, "poda de un movimiento entre dos rutas "
                                + "que se aceptaria");
                    }
                }
                cotaPrevia = cota;
                exactoPrevio = exacto;
            }
        }
        assertTrue(cobertura.decodificadas > 10_000, "deben valorarse miles de secuencias y solo hubo "
                + cobertura.decodificadas);
        assertTrue(cobertura.conTerminoDesfase > 1_000, "la cota debe incluir desfase en una fraccion apreciable "
                + "y solo lo hizo " + cobertura.conTerminoDesfase + " veces");
        assertTrue(cobertura.inalcanzables > 100, "deben aparecer secuencias sin camino y solo hubo "
                + cobertura.inalcanzables);
        assertTrue(cobertura.podadasHgs > 10_000, "las reglas deben podar y solo podaron " + cobertura.podadasHgs);
    }

    /** Acceso por reflexion a la cota y a la valoracion privadas de {@link Educacion}. */
    private static final class EspejoEducacion {
        private final Educacion educacion;
        private final Method cota;
        private final Method valor;
        private final Field cotaSinDesfase;

        EspejoEducacion(Educacion educacion) throws ReflectiveOperationException {
            this.educacion = educacion;
            this.cota = Educacion.class.getDeclaredMethod("cotaDeSecuencia", int.class, int[].class, int.class,
                    double.class);
            this.valor = Educacion.class.getDeclaredMethod("evaluarSecuencia", int.class, int[].class, int.class,
                    double.class);
            this.cotaSinDesfase = Educacion.class.getDeclaredField("cotaSinDesfase");
            cota.setAccessible(true);
            valor.setAccessible(true);
            cotaSinDesfase.setAccessible(true);
        }

        double cota(int unidad, int[] secuencia, int longitud, double peso) throws ReflectiveOperationException {
            return invocar(cota, unidad, secuencia, longitud, peso);
        }

        double valor(int unidad, int[] secuencia, int longitud, double peso) throws ReflectiveOperationException {
            return invocar(valor, unidad, secuencia, longitud, peso);
        }

        double cotaSinDesfase() throws IllegalAccessException {
            return cotaSinDesfase.getDouble(educacion);
        }

        private double invocar(Method metodo, int unidad, int[] secuencia, int longitud, double peso)
                throws IllegalAccessException {
            try {
                return (double) metodo.invoke(educacion, unidad, secuencia, longitud, peso);
            } catch (InvocationTargetException e) {
                throw new AssertionError("fallo dentro de " + metodo.getName(), e.getCause());
            }
        }
    }

    private static double constante(String nombre) throws ReflectiveOperationException {
        Field campo = Educacion.class.getDeclaredField(nombre);
        campo.setAccessible(true);
        return campo.getDouble(null);
    }

    // --------------------------------------------------------------- instancias

    private static List<InstanciaPlanificacion> instancias() {
        List<InstanciaPlanificacion> lista = new ArrayList<>();
        lista.add(InstanciasDePrueba.instanciaVariada(14));
        lista.add(InstanciasDePrueba.instanciaFlotaMixta(16));
        lista.add(InstanciasDePrueba.instanciaPlazosAjustados(18));
        lista.add(InstanciasDePrueba.instanciaPlazosAjustados(26));
        for (int s = 0; s < INSTANCIAS_GENERADAS; s++) {
            lista.add(generada(s));
        }
        return lista;
    }

    /**
     * Fotografia al azar para la semilla dada. Las semillas pares tienen plazos ajustados y las
     * impares holgados; dos de cada cuatro tienen tramos bloqueados y nodos aislados; una de cada
     * tres cambia las velocidades a valores que no dividen a sesenta; el turno rota entre los tres.
     */
    private static InstanciaPlanificacion generada(int semilla) {
        Aleatorio aleatorio = new Aleatorio(Aleatorio.derivarSemilla(SEMILLA, semilla));
        final boolean ajustados = semilla % 2 == 0;
        final boolean conBloqueos = semilla % 4 >= 2;
        final long[] iniciosDeTurno = {420L, 900L, 1380L};
        final long minutoActual = iniciosDeTurno[semilla % 3] + aleatorio.siguienteEntero(0, 300);

        ParametrosOperacion parametros = new ParametrosOperacion();
        if (semilla % 3 == 1) {
            parametros.velocidad(TipoUnidad.AUTO, 33.0);
            parametros.velocidad(TipoUnidad.MOTO, 17.0);
            parametros.velocidad(TipoUnidad.BICICLETA, 9.0);
        }

        final int[] plazosAjustados = {4, 5, 6, 8};
        final int[] plazosHolgados = {12, 24, 36};
        final int cantidadPedidos = aleatorio.siguienteEntero(10, 18);
        List<Pedido> pedidos = new ArrayList<>(cantidadPedidos);
        for (int i = 0; i < cantidadPedidos; i++) {
            int[] plazos = ajustados ? plazosAjustados : plazosHolgados;
            int plazo = plazos[aleatorio.siguienteEntero(plazos.length)];
            pedidos.add(InstanciasDePrueba.pedido(i, aleatorio.siguienteEntero(0, Ciudad.LARGO_KM),
                    aleatorio.siguienteEntero(0, Ciudad.ANCHO_KM), aleatorio.siguienteEntero(1, 30),
                    minutoActual - aleatorio.siguienteEntero(0, 240), plazo));
        }

        final String[] prefijos = {"TA", "TM", "TB"};
        final int cantidadUnidades = 6;
        List<UnidadTransporte> unidades = new ArrayList<>(cantidadUnidades);
        for (int i = 0; i < cantidadUnidades; i++) {
            String codigo = prefijos[i % 3] + "0" + (1 + i / 3);
            long disponible = minutoActual + aleatorio.siguienteEntero(0, 120);
            if (i == cantidadUnidades - 2) {
                // Arranca a pocos minutos del cierre: el fin virtual es el que manda.
                disponible = Turno.finDelTurno(minutoActual) - aleatorio.siguienteEntero(10, 120);
            }
            UnidadTransporte unidad = i % 2 == 0
                    ? InstanciasDePrueba.unidad(codigo, disponible)
                    : InstanciasDePrueba.unidadEn(codigo, aleatorio.siguienteEntero(0, Ciudad.LARGO_KM),
                            aleatorio.siguienteEntero(0, Ciudad.ANCHO_KM), disponible);
            unidad.cargaABordo(aleatorio.conProbabilidad(0.5) ? 0 : aleatorio.siguienteEntero(0, unidad.capacidad()));
            unidades.add(unidad);
        }

        List<Almacen> almacenes = Almacen.todos();
        int[] nodos = new int[almacenes.size() + pedidos.size() + unidades.size()];
        int k = 0;
        for (Almacen a : almacenes) {
            nodos[k++] = a.nodo();
        }
        for (Pedido p : pedidos) {
            nodos[k++] = p.nodoDestino();
        }
        for (UnidadTransporte u : unidades) {
            nodos[k++] = u.nodo();
        }
        boolean[] mascara = null;
        if (conBloqueos) {
            mascara = new boolean[RegistroBloqueos.TOTAL_ARISTAS];
            for (int b = 0; b < ARISTAS_BLOQUEADAS; b++) {
                bloquear(mascara, aleatorio.siguienteEntero(Ciudad.TOTAL_NODOS),
                        aleatorio.siguienteEntero(RegistroBloqueos.DIRECCIONES));
            }
            // Dos destinos y una unidad fuera del central quedan aislados del resto.
            aislar(mascara, pedidos.get(0).nodoDestino());
            aislar(mascara, pedidos.get(1).nodoDestino());
            aislar(mascara, unidades.get(1).nodo());
        }

        InstanciaPlanificacion.Constructor constructor = InstanciaPlanificacion.constructor()
                .minutoActual(minutoActual)
                .parametros(parametros.instantanea())
                .matriz(MatrizDistanciasReticula.construir(nodos, mascara));
        for (Almacen a : almacenes) {
            // Inventario intermedio escaso: a menudo el abastecimiento tiene que ir al central.
            constructor.almacen(a, a.central() ? Integer.MAX_VALUE : aleatorio.siguienteEntero(0, 40));
        }
        for (Pedido p : pedidos) {
            constructor.pedido(p, p.cantidad());
            if (aleatorio.conProbabilidad(0.5)) {
                String vigente = unidades.get(aleatorio.siguienteEntero(cantidadUnidades)).codigo();
                constructor.asignacionVigente(p.id(), vigente);
            }
        }
        for (int i = 0; i < cantidadUnidades; i++) {
            UnidadTransporte u = unidades.get(i);
            // La ultima unidad tiene un horizonte explicito, mas corto que el turno.
            long horizonte = i == cantidadUnidades - 1
                    ? Math.max(minutoActual, u.minutoDisponibleDesde()) + aleatorio.siguienteEntero(30, 240)
                    : -1L;
            constructor.unidad(u, horizonte);
        }
        return constructor.construir();
    }

    /** Bloquea la calle que sale del nodo en la direccion dada, en los dos sentidos. */
    private static void bloquear(boolean[] mascara, int nodo, int direccion) {
        int vecino = RegistroBloqueos.nodoVecino(nodo, direccion);
        if (vecino < 0) {
            return;
        }
        mascara[RegistroBloqueos.arista(nodo, direccion)] = true;
        mascara[RegistroBloqueos.arista(vecino, RegistroBloqueos.direccionOpuesta(direccion))] = true;
    }

    private static void aislar(boolean[] mascara, int nodo) {
        for (int d = 0; d < RegistroBloqueos.DIRECCIONES; d++) {
            bloquear(mascara, nodo, d);
        }
    }
}
