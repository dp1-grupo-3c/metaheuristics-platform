package org.kindbox.core.metaheuristica.tabu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.kindbox.core.evaluacion.FuncionObjetivoJerarquica;
import org.kindbox.core.evaluacion.ProgramadorRuta;
import org.kindbox.core.evaluacion.Programacion;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.metaheuristica.alns.TareasAlns;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Busqueda tabu portada del prototipo del grupo 6 (DP1-G6F, {@code TabuSearchPlanner}) a la
 * base de KindBox, para comparar su logica con la nuestra sobre el mismo simulador, los mismos
 * datos y el mismo decodificador.
 *
 * <h2>Lo que se conserva del original</h2>
 * <ul>
 *   <li>Solucion inicial por insercion voraz en orden de plazo (EDF), eligiendo en cada paso la
 *       mejor posicion segun el objetivo.</li>
 *   <li>Dos vecindarios que se alternan por iteracion, cada uno con la mitad del cupo de
 *       candidatos: <em>asignacion</em> (mover una parte a otra ruta en cualquier posicion,
 *       sacarla al banco o sacarla del banco) y <em>ruteo</em> (intercambio y reubicacion dentro
 *       de una ruta).</li>
 *   <li>Lista tabu de movimientos inversos con tenencia 7: tras mover una parte de A a B se
 *       prohibe devolverla a A. Aspiracion: un movimiento tabu se admite si mejora la mejor
 *       solucion conocida.</li>
 *   <li>Diversificacion tras 40 iteraciones sin mejora o sin vecino admisible: se devuelve al
 *       banco una octava parte de las partes asignadas (al menos dos) y se reinsertan en orden
 *       aleatorio; la memoria tabu se limpia.</li>
 *   <li>Su objetivo: paquetes pendientes y, a igualdad, mayor holgura media de los pedidos
 *       completos ({@code pendientes + 0,5 - atan(holguraMedia/60)/pi}). El costo no interviene.</li>
 * </ul>
 *
 * <h2>Lo que se adapta</h2>
 * <ul>
 *   <li>Las rutas se programan con nuestro {@link ProgramadorRuta}: abastecimiento, pausa,
 *       turnos, plazos y bloqueos son los mismos que para ALNS y HGS, de modo que la comparacion
 *       mide la busqueda y el objetivo, no el modelo.</li>
 *   <li>Las partes son las tareas de {@link TareasAlns} (pedidos mayores que la unidad mas
 *       grande partidos en entregas).</li>
 *   <li>El criterio de parada es el presupuesto de reloj de la simulacion, como para los otros
 *       dos algoritmos, en lugar de 100 o 300 iteraciones fijas.</li>
 *   <li>El objetivo es configurable con {@code -Dts.objetivo=HOLGURA|JERARQUICO}: el del grupo 6
 *       (por defecto) o el nuestro (H, urgencia, costo), para separar el efecto del objetivo del
 *       efecto de la busqueda.</li>
 * </ul>
 */
public final class BusquedaTabu implements Algoritmo {

    public static final String NOMBRE = "TS";

    /** Objetivo con que la busqueda compara soluciones. */
    public enum Objetivo { HOLGURA, JERARQUICO }

    private static final int TENENCIA = 7;
    private static final int SIN_MEJORA_MAXIMO = 40;
    private static final int CANDIDATOS_POR_ITERACION = 1000;
    private static final int TAMANO_CACHE = 5000;

    private final long semilla;
    private final Objetivo objetivo;
    private final FuncionObjetivoJerarquica funcionComun = new FuncionObjetivoJerarquica();

    public BusquedaTabu(long semilla) {
        this(semilla, Objetivo.valueOf(System.getProperty("ts.objetivo", "HOLGURA").toUpperCase(Locale.ROOT)));
    }

    public BusquedaTabu(long semilla, Objetivo objetivo) {
        this.semilla = semilla;
        this.objetivo = objetivo;
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public String toString() {
        return NOMBRE + "[semilla=" + semilla + " objetivo=" + objetivo + " tenencia=" + TENENCIA
                + " sinMejora=" + SIN_MEJORA_MAXIMO + " candidatos=" + CANDIDATOS_POR_ITERACION + "]";
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto) {
        return resolver(instancia, presupuesto, semilla);
    }

    @Override
    public ResultadoPlanificacion resolver(InstanciaPlanificacion instancia, PresupuestoComputo presupuesto,
                                           long semillaEjecucion) {
        if (instancia.cantidadPedidos() == 0 || instancia.cantidadUnidades() == 0) {
            Solucion vacia = Solucion.vacia(instancia);
            ValorObjetivo v = funcionComun.evaluar(instancia, vacia);
            presupuesto.cerrarPerfil(v);
            return new ResultadoPlanificacion(NOMBRE, vacia.conValor(v), presupuesto.perfil(),
                    presupuesto.milisegundosTranscurridos(), 0, semillaEjecucion, Map.of());
        }
        Busqueda b = new Busqueda(instancia, new Random(semillaEjecucion));
        b.buscar(presupuesto);
        Solucion solucion = b.materializar();
        ValorObjetivo valor = funcionComun.evaluar(instancia, solucion);
        presupuesto.cerrarPerfil(valor);
        return new ResultadoPlanificacion(NOMBRE, solucion.conValor(valor), presupuesto.perfil(),
                presupuesto.milisegundosTranscurridos(), b.iteraciones, semillaEjecucion, Map.of());
    }

    // ------------------------------------------------------------------ evaluacion

    /** Valor lexicografico de una solucion: primario, luego secundario, luego terciario. */
    private record Valor(double primario, double secundario, double terciario) {
        boolean mejorQue(Valor o) {
            if (Math.abs(primario - o.primario) > 1e-9) {
                return primario < o.primario;
            }
            if (Math.abs(secundario - o.secundario) > 1e-9) {
                return secundario < o.secundario;
            }
            return terciario < o.terciario - 1e-9;
        }

        static final Valor PEOR = new Valor(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    }

    /** Programacion cacheada de una ruta: factibilidad, costo y salida de cada visita. */
    private record EvalRuta(boolean factible, double costo, int[] pedidos, int[] cantidades, long[] salidas) {
    }

    /** Movimiento tabu: tipo, parte, segundo atributo y unidad o posicion de destino/origen. */
    private record Clave(int tipo, int parteA, int parteB, int lugar) {
    }

    private static final int ASIGNACION = 0;
    private static final int SWAP = 1;
    private static final int RELOCATE = 2;
    private static final int BANCO = -1;

    private final class Busqueda {
        final InstanciaPlanificacion inst;
        final TareasAlns tareas;
        final ProgramadorRuta programador;
        final Random random;
        final int unidades;
        final Map<Clave, Long> tabu = new HashMap<>();
        final Map<String, EvalRuta> cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, EvalRuta> e) {
                return size() > TAMANO_CACHE;
            }
        };
        long iteraciones;

        List<List<Integer>> actual;
        EvalRuta[] evActual;
        List<List<Integer>> mejor;
        Valor valorActual;
        Valor valorMejor;

        Busqueda(InstanciaPlanificacion inst, Random random) {
            this.inst = inst;
            this.tareas = new TareasAlns(inst);
            this.programador = new ProgramadorRuta(inst);
            this.random = random;
            this.unidades = inst.cantidadUnidades();
        }

        void buscar(PresupuestoComputo presupuesto) {
            actual = vacias();
            List<Integer> orden = new ArrayList<>();
            for (int t = 0; t < tareas.cantidad(); t++) {
                orden.add(t);
            }
            orden.sort((a, b) -> {
                int c = Long.compare(tareas.limite(a), tareas.limite(b));
                return c != 0 ? c : Integer.compare(a, b);
            });
            actual = reparar(actual, orden, presupuesto);
            evActual = evaluarTodas(actual);
            valorActual = agregar(evActual);
            mejor = copiar(actual);
            valorMejor = valorActual;

            int sinMejora = 0;
            while (!presupuesto.agotado()) {
                final ValorObjetivo muestra = new ValorObjetivo(pendientes(mejor) > 0 ? 1 : 0, 0, 0);
                presupuesto.muestrear(() -> muestra);
                iteraciones++;
                final long iter = iteraciones;
                tabu.values().removeIf(fin -> fin < iter);
                Seleccion sel = new Seleccion(iter);
                for (int fase = 0; fase < 2 && !presupuesto.agotado(); fase++) {
                    int cupo = CANDIDATOS_POR_ITERACION / 2;
                    if ((iter + fase) % 2 == 0) {
                        vecindarioAsignacion(sel, cupo, presupuesto);
                    } else {
                        vecindarioRuteo(sel, cupo, presupuesto);
                    }
                }
                presupuesto.contarIteracion();
                if (sel.elegida == null) {
                    if (presupuesto.agotado()) {
                        break;
                    }
                    diversificar(presupuesto);
                    sinMejora = 0;
                    continue;
                }
                actual = sel.elegida;
                evActual = sel.evals;
                valorActual = sel.valor;
                tabu.merge(sel.inversa, iter + TENENCIA, Math::max);
                if (valorActual.mejorQue(valorMejor)) {
                    mejor = copiar(actual);
                    valorMejor = valorActual;
                    sinMejora = 0;
                } else if (++sinMejora >= SIN_MEJORA_MAXIMO) {
                    diversificar(presupuesto);
                    sinMejora = 0;
                }
            }
        }

        /** Mejor candidato admisible de la iteracion, con aspiracion. */
        final class Seleccion {
            final long iter;
            List<List<Integer>> elegida;
            EvalRuta[] evals;
            Valor valor = Valor.PEOR;
            Clave inversa;

            Seleccion(long iter) {
                this.iter = iter;
            }

            void considerar(List<List<Integer>> candidata, int[] cambiadas, Clave consulta, Clave inversaMovimiento) {
                EvalRuta[] ev = evActual.clone();
                for (int u : cambiadas) {
                    ev[u] = evaluarRuta(u, candidata.get(u));
                    if (!ev[u].factible()) {
                        return;
                    }
                }
                Valor v = agregar(ev);
                boolean esTabu = tabu.getOrDefault(consulta, -1L) >= iter;
                if (esTabu && !v.mejorQue(valorMejor)) {
                    return;
                }
                if (elegida == null || v.mejorQue(valor)) {
                    elegida = candidata;
                    evals = ev;
                    valor = v;
                    inversa = inversaMovimiento;
                }
            }
        }

        void vecindarioAsignacion(Seleccion sel, int cupo, PresupuestoComputo presupuesto) {
            int[] usados = {0};
            List<Integer> indices = new ArrayList<>();
            for (int u = 0; u < unidades; u++) {
                indices.add(u);
            }
            Collections.shuffle(indices, random);
            List<Integer> partes = new ArrayList<>();
            for (int t = 0; t < tareas.cantidad(); t++) {
                partes.add(t);
            }
            Collections.shuffle(partes, random);
            for (int parte : partes) {
                int origen = unidadDe(actual, parte);
                if (origen >= 0) {
                    List<List<Integer>> c = copiar(actual);
                    c.get(origen).remove((Integer) parte);
                    if (!consumir(sel, c, new int[] {origen}, new Clave(ASIGNACION, parte, 0, BANCO),
                            new Clave(ASIGNACION, parte, 0, origen), usados, cupo, presupuesto)) {
                        return;
                    }
                }
                for (int destino : indices) {
                    if (destino == origen || tareas.unidades(parte) > inst.unidadCapacidad(destino)) {
                        continue;
                    }
                    int largo = actual.get(destino).size();
                    for (int pos = 0; pos <= largo; pos++) {
                        List<List<Integer>> c = copiar(actual);
                        if (origen >= 0) {
                            c.get(origen).remove((Integer) parte);
                        }
                        c.get(destino).add(pos, parte);
                        if (!consumir(sel, c, origen >= 0 ? new int[] {origen, destino} : new int[] {destino},
                                new Clave(ASIGNACION, parte, 0, destino),
                                new Clave(ASIGNACION, parte, 0, origen >= 0 ? origen : BANCO), usados, cupo,
                                presupuesto)) {
                            return;
                        }
                    }
                }
            }
        }

        void vecindarioRuteo(Seleccion sel, int cupo, PresupuestoComputo presupuesto) {
            int[] usados = {0};
            List<Integer> indices = new ArrayList<>();
            for (int u = 0; u < unidades; u++) {
                indices.add(u);
            }
            Collections.shuffle(indices, random);
            for (int u : indices) {
                List<Integer> ruta = actual.get(u);
                for (int i = 0; i < ruta.size(); i++) {
                    for (int j = i + 1; j < ruta.size(); j++) {
                        List<List<Integer>> c = copiar(actual);
                        Collections.swap(c.get(u), i, j);
                        int a = Math.min(ruta.get(i), ruta.get(j));
                        int b = Math.max(ruta.get(i), ruta.get(j));
                        Clave k = new Clave(SWAP, a, b, u);
                        if (!consumir(sel, c, new int[] {u}, k, k, usados, cupo, presupuesto)) {
                            return;
                        }
                    }
                }
                for (int i = 0; i < ruta.size(); i++) {
                    for (int j = 0; j < ruta.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        List<List<Integer>> c = copiar(actual);
                        int parte = c.get(u).remove(i);
                        c.get(u).add(j, parte);
                        if (!consumir(sel, c, new int[] {u}, new Clave(RELOCATE, parte, u, j),
                                new Clave(RELOCATE, parte, u, i),
                                usados, cupo, presupuesto)) {
                            return;
                        }
                    }
                }
            }
        }

        private boolean consumir(Seleccion sel, List<List<Integer>> c, int[] cambiadas, Clave consulta, Clave inversa,
                                 int[] usados, int cupo, PresupuestoComputo presupuesto) {
            if (usados[0] >= cupo || presupuesto.agotado()) {
                return false;
            }
            usados[0]++;
            sel.considerar(c, cambiadas, consulta, inversa);
            return usados[0] < cupo;
        }

        /** Devuelve al banco una octava parte de lo asignado y lo reinserta en orden aleatorio. */
        void diversificar(PresupuestoComputo presupuesto) {
            List<Integer> asignadas = new ArrayList<>();
            for (List<Integer> r : actual) {
                asignadas.addAll(r);
            }
            if (asignadas.isEmpty()) {
                return;
            }
            Collections.shuffle(asignadas, random);
            List<Integer> quitar = asignadas.subList(0, Math.min(asignadas.size(), Math.max(2, asignadas.size() / 8)));
            List<List<Integer>> c = copiar(actual);
            for (int t : quitar) {
                c.get(unidadDe(c, t)).remove((Integer) t);
            }
            List<Integer> orden = new ArrayList<>(quitar);
            for (int t = 0; t < tareas.cantidad(); t++) {
                if (unidadDe(c, t) < 0 && !orden.contains(t)) {
                    orden.add(t);
                }
            }
            Collections.shuffle(orden, random);
            actual = reparar(c, orden, presupuesto);
            evActual = evaluarTodas(actual);
            valorActual = agregar(evActual);
            tabu.clear();
        }

        /** Insercion voraz de las partes indicadas en su mejor posicion; lo que no cabe queda en el banco. */
        List<List<Integer>> reparar(List<List<Integer>> s, List<Integer> orden, PresupuestoComputo presupuesto) {
            EvalRuta[] ev = evaluarTodas(s);
            for (int parte : orden) {
                if (unidadDe(s, parte) >= 0) {
                    continue;
                }
                int mejorU = -1;
                int mejorPos = -1;
                EvalRuta mejorEval = null;
                Valor mejorValor = null;
                for (int u = 0; u < unidades; u++) {
                    if (tareas.unidades(parte) > inst.unidadCapacidad(u)) {
                        continue;
                    }
                    List<Integer> ruta = s.get(u);
                    EvalRuta original = ev[u];
                    for (int pos = 0; pos <= ruta.size(); pos++) {
                        List<Integer> nueva = new ArrayList<>(ruta);
                        nueva.add(pos, parte);
                        EvalRuta e = evaluarRuta(u, nueva);
                        if (!e.factible()) {
                            continue;
                        }
                        ev[u] = e;
                        Valor v = agregar(ev);
                        ev[u] = original;
                        if (mejorValor == null || v.mejorQue(mejorValor)) {
                            mejorValor = v;
                            mejorU = u;
                            mejorPos = pos;
                            mejorEval = e;
                        }
                    }
                }
                if (mejorU >= 0) {
                    s.get(mejorU).add(mejorPos, parte);
                    ev[mejorU] = mejorEval;
                }
            }
            return s;
        }

        EvalRuta[] evaluarTodas(List<List<Integer>> s) {
            EvalRuta[] ev = new EvalRuta[unidades];
            for (int u = 0; u < unidades; u++) {
                ev[u] = evaluarRuta(u, s.get(u));
            }
            return ev;
        }

        /** Valor de la solucion a partir de la programacion de cada ruta. */
        Valor agregar(EvalRuta[] ev) {
            int[] entregadas = new int[inst.cantidadPedidos()];
            long[] ultimaSalida = new long[inst.cantidadPedidos()];
            Arrays.fill(ultimaSalida, Long.MIN_VALUE);
            double costo = 0.0;
            for (EvalRuta e : ev) {
                costo += e.costo();
                for (int k = 0; k < e.pedidos().length; k++) {
                    int p = e.pedidos()[k];
                    entregadas[p] += e.cantidades()[k];
                    ultimaSalida[p] = Math.max(ultimaSalida[p], e.salidas()[k]);
                }
            }
            int paquetesPendientes = 0;
            int pedidosPendientes = 0;
            double urgencia = 0.0;
            double sumaHolgura = 0.0;
            int completos = 0;
            for (int p = 0; p < inst.cantidadPedidos(); p++) {
                int falta = inst.pedidoCantidad(p) - entregadas[p];
                if (falta > 0) {
                    paquetesPendientes += falta;
                    pedidosPendientes++;
                    urgencia += ValorObjetivo.urgenciaDe(inst.pedidoMinutoLimite(p) - inst.minutoActual());
                } else {
                    sumaHolgura += inst.pedidoMinutoLimite(p) - ultimaSalida[p];
                    completos++;
                }
            }
            if (objetivo == Objetivo.HOLGURA) {
                double calidad = completos == 0 ? 0.5 : 0.5 - Math.atan(sumaHolgura / completos / 60.0) / Math.PI;
                return new Valor(paquetesPendientes + calidad, 0.0, 0.0);
            }
            return new Valor(pedidosPendientes, urgencia, costo);
        }

        EvalRuta evaluarRuta(int u, List<Integer> ruta) {
            String clave = u + ":" + ruta;
            EvalRuta e = cache.get(clave);
            if (e != null) {
                return e;
            }
            int n = ruta.size();
            int[] pedidos = new int[n];
            int[] cantidades = new int[n];
            for (int i = 0; i < n; i++) {
                pedidos[i] = tareas.pedido(ruta.get(i));
                cantidades[i] = tareas.unidades(ruta.get(i));
            }
            Programacion prog = programador.programar(u, pedidos, cantidades, n, false);
            if (prog.sinProgramacion() || !prog.factible()) {
                e = new EvalRuta(false, 0.0, new int[0], new int[0], new long[0]);
            } else {
                List<Parada> entregas = new ArrayList<>();
                for (Parada p : prog.ruta().paradas()) {
                    if (p.tipo() == TipoParada.ENTREGA) {
                        entregas.add(p);
                    }
                }
                int[] ps = new int[entregas.size()];
                int[] cs = new int[entregas.size()];
                long[] salidas = new long[entregas.size()];
                for (int i = 0; i < entregas.size(); i++) {
                    ps[i] = inst.indiceDePedido(entregas.get(i).idPedido());
                    cs[i] = entregas.get(i).cantidad();
                    salidas[i] = entregas.get(i).minutoSalida();
                }
                e = new EvalRuta(true, prog.costo(), ps, cs, salidas);
            }
            cache.put(clave, e);
            return e;
        }

        /** Programa las rutas consumiendo inventario; recorta por la cola lo que deje de caber. */
        Solucion materializar() {
            programador.reiniciarInventarios();
            List<Ruta> rutas = new ArrayList<>();
            int[] entregado = new int[inst.cantidadPedidos()];
            for (int u = 0; u < unidades; u++) {
                List<Integer> ruta = new ArrayList<>(mejor.get(u));
                Programacion prog = null;
                while (true) {
                    int n = ruta.size();
                    int[] pedidos = new int[n];
                    int[] cantidades = new int[n];
                    for (int i = 0; i < n; i++) {
                        pedidos[i] = tareas.pedido(ruta.get(i));
                        cantidades[i] = tareas.unidades(ruta.get(i));
                    }
                    prog = programador.programar(u, pedidos, cantidades, n, true);
                    if (n == 0 || (!prog.sinProgramacion() && prog.factible())) {
                        break;
                    }
                    ruta.remove(ruta.size() - 1);
                }
                rutas.add(prog.ruta());
                for (int t : ruta) {
                    entregado[tareas.pedido(t)] += tareas.unidades(t);
                }
            }
            programador.reiniciarInventarios();
            Map<Integer, Integer> banco = new LinkedHashMap<>();
            for (int p = 0; p < inst.cantidadPedidos(); p++) {
                int falta = inst.pedidoCantidad(p) - entregado[p];
                if (falta > 0) {
                    banco.put(inst.pedidoId(p), falta);
                }
            }
            return new Solucion(rutas, banco, ValorObjetivo.PEOR);
        }

        int pendientes(List<List<Integer>> s) {
            int asignadas = 0;
            for (List<Integer> r : s) {
                asignadas += r.size();
            }
            return tareas.cantidad() - asignadas;
        }

        List<List<Integer>> vacias() {
            List<List<Integer>> s = new ArrayList<>(unidades);
            for (int u = 0; u < unidades; u++) {
                s.add(new ArrayList<>());
            }
            return s;
        }

        List<List<Integer>> copiar(List<List<Integer>> s) {
            List<List<Integer>> c = new ArrayList<>(s.size());
            for (List<Integer> r : s) {
                c.add(new ArrayList<>(r));
            }
            return c;
        }

        int unidadDe(List<List<Integer>> s, int parte) {
            for (int u = 0; u < s.size(); u++) {
                if (s.get(u).contains(parte)) {
                    return u;
                }
            }
            return -1;
        }
    }
}
