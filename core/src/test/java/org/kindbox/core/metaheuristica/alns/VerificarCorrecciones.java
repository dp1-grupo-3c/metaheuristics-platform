package org.kindbox.core.metaheuristica.alns;

import java.lang.reflect.Method;
import java.util.*;
import org.kindbox.core.apoyo.InstanciasDePrueba;
import org.kindbox.core.evaluacion.*;
import org.kindbox.core.metaheuristica.*;
import org.kindbox.core.modelo.*;
import org.kindbox.core.problema.*;
import org.kindbox.core.simulacion.*;
import org.kindbox.core.util.Aleatorio;

/** Regresiones ejecutables sin dependencias adicionales: ver LEEME de la correccion. */
public final class VerificarCorrecciones {
    private static void exigir(boolean condicion, String motivo) {
        if (!condicion) throw new AssertionError(motivo);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty(HolguraTemporal.PROPIEDAD_MARGEN, "30");
        probarMovimiento();
        probarReconstruccion();
        probarPartesYCopia();
        probarLlegadasConPausa();
        probarDeltasAleatorios();
        probarFabrica();
        probarAlns();
        System.out.println("OK: movimiento, desempates, reconstruccion, partes, cache, pausa y ALNS.");
    }

    private static long redireccion(UnidadEnCurso u, int indice, ParametrosOperacion.Instantanea foto)
            throws Exception {
        Method m = org.kindbox.core.simulacion.MotorEjecucion.class.getDeclaredMethod("minutoEnRedireccion",
                UnidadEnCurso.class, int.class, ParametrosOperacion.Instantanea.class);
        m.setAccessible(true);
        return (long) m.invoke(null, u, indice, foto);
    }

    private static void probarMovimiento() throws Exception {
        int comprobaciones = 0;
        var parametros = new ParametrosOperacion().instantanea();
        for (String codigo : List.of("TA01", "TM01", "TB01")) {
            for (int km = 1; km <= 60; km++) {
                var unidad = UnidadTransporte.de(codigo, Ciudad.nodo(0, 0));
                var u = new UnidadEnCurso(0, unidad);
                int[] camino = new int[km + 1];
                for (int i = 0; i <= km; i++) camino[i] = Ciudad.nodo(i, 0);
                long llegada = 100 + parametros.minutosDeViaje(unidad.tipo(), km);
                u.asignarItinerario(null, camino, new int[] {km}, new long[] {llegada},
                        new long[] {llegada + 60}, 100);
                // Replanificar repetidamente, conservando el mismo destino.
                for (long minuto = 101; minuto < llegada; minuto++) {
                    int indice = u.indiceRedireccion(minuto);
                    long disponible = redireccion(u, indice, parametros);
                    while (disponible < minuto && indice < u.desplazamientoParada(0)) {
                        disponible = redireccion(u, ++indice, parametros);
                    }
                    int restantes = u.desplazamientoParada(0) - indice;
                    long nueva = Math.max(minuto, disponible)
                            + parametros.minutosDeViaje(unidad.tipo(), restantes);
                    exigir(nueva == llegada, "Deriva " + codigo + " km=" + km + " minuto=" + minuto
                            + " vieja=" + llegada + " nueva=" + nueva);
                    int fisico = u.indiceNodoActual(minuto);
                    int[] nuevoCamino = Arrays.copyOfRange(camino, fisico, camino.length);
                    u.asignarItinerario(null, nuevoCamino, new int[] {nuevoCamino.length - 1},
                            new long[] {nueva}, new long[] {nueva + 60}, minuto);
                    camino = nuevoCamino;
                    comprobaciones++;
                }
            }
        }
        // Tramo exacto observado en pedido 120: 19:11 -> 19:34, 15 km; foto 19:30.
        var u = new UnidadEnCurso(0, UnidadTransporte.de("TA02", Ciudad.nodo(57, 27)));
        int[] camino = new int[16]; int k = 0;
        for (int x = 57; x <= 62; x++) camino[k++] = Ciudad.nodo(x, 27);
        for (int y = 28; y <= 37; y++) camino[k++] = Ciudad.nodo(62, y);
        u.asignarItinerario(null, camino, new int[] {15}, new long[] {1174}, new long[] {1234}, 1151);
        int indice = u.indiceRedireccion(1170);
        exigir(indice == 13, "Debe redirigir desde el nodo futuro, no el ya sobrepasado");
        long nueva = redireccion(u, indice, parametros) + parametros.minutosDeViaje(TipoUnidad.AUTO, 15 - indice);
        exigir(nueva == 1174, "El pedido 120 debe conservar llegada 19:34");
        var modificados = new ParametrosOperacion();
        modificados.velocidad(TipoUnidad.AUTO, 10);
        exigir(redireccion(u, indice, modificados.instantanea()) == redireccion(u, indice, parametros),
                "Una nueva velocidad no cambia retroactivamente el tramo comprometido");
        System.out.println("Movimiento: " + comprobaciones + " replanificaciones sin deriva; caso 120 conserva 19:34.");
    }

    private static InstanciaPlanificacion instancia(List<Pedido> pedidos, List<UnidadTransporte> flota) {
        var parametros = InstanciasDePrueba.parametros();
        parametros.minutosAcondicionamiento(20);
        return InstanciasDePrueba.fotografia(510, pedidos, flota, 1100, parametros);
    }
    private static List<Pedido> pedidos() {
        return List.of(InstanciasDePrueba.pedido(0, 28, 14, 1, 420, 2),
                InstanciasDePrueba.pedido(1, 28, 14, 1, 420, 8));
    }
    private static void coherente(EstadoAlns e) {
        long antes = e.riesgoTemporal();
        Solucion s = e.materializar();
        long recalculado = HolguraTemporal.riesgo(e.instancia(), s, 30);
        exigir(antes == recalculado && e.riesgoTemporal() == recalculado,
                "Cache de riesgo distinta de las llegadas finales: " + antes + " vs " + recalculado);
        exigir(new VerificadorRestricciones().verificar(e.instancia(), s).factible(), "Plan infactible");
    }

    private static void probarReconstruccion() {
        var i = instancia(pedidos(), List.of(InstanciasDePrueba.unidad("TA01", 510)));
        for (boolean filtro : List.of(false, true)) {
            var e = new EstadoAlns(i, new ProgramadorRuta(i));
            exigir(e.insertar(0, 0, 1), "Semilla flexible");
            new MotorInsercion(i, new ParametrosAlns().filtroCotaInferior(filtro))
                    .reconstruir(e, new Aleatorio(2), PresupuestoComputo.deIteraciones(1).arrancar(), 1, 0);
            exigir(e.tareaEn(0, 0) == 0, "El empate debe colocar el urgente antes del flexible");
            exigir(e.riesgoTemporal() == 4, "Llegada urgente 08:32, limite 09:00, margen 28 -> riesgo 4");
            coherente(e);
        }
        var dos = instancia(List.of(pedidos().get(0)), List.of(
                InstanciasDePrueba.unidad("TA01", 530), InstanciasDePrueba.unidad("TA02", 510)));
        var e = new EstadoAlns(dos, new ProgramadorRuta(dos));
        new MotorInsercion(dos, new ParametrosAlns()).reconstruir(e, new Aleatorio(2),
                PresupuestoComputo.deIteraciones(1).arrancar(), 1, 0);
        exigir(e.longitudRuta(1) == 1, "A igual costo debe elegir el vehiculo con mas margen");
        coherente(e);
        var distintoCosto = instancia(List.of(pedidos().get(0)), List.of(
                InstanciasDePrueba.unidad("TA01", 530),
                InstanciasDePrueba.unidadEn("TA02", 20, 14, 510)));
        var barato = new EstadoAlns(distintoCosto, new ProgramadorRuta(distintoCosto));
        var holgado = new EstadoAlns(distintoCosto, new ProgramadorRuta(distintoCosto));
        exigir(barato.insertar(0, 0, 0) && holgado.insertar(1, 0, 0), "Dos rutas factibles");
        exigir(barato.riesgoTemporal() > holgado.riesgoTemporal(), "Caso con sobrecosto mas robusto");
        exigir(barato.compararConHolgura(holgado) < 0, "El costo original domina a la holgura");
    }

    private static void probarPartesYCopia() {
        var i = instancia(List.of(InstanciasDePrueba.pedido(0, 28, 14, 30, 420, 2)), List.of(
                InstanciasDePrueba.unidad("TA01", 510), InstanciasDePrueba.unidad("TA02", 525)));
        var e = new EstadoAlns(i, new ProgramadorRuta(i));
        exigir(e.insertar(0, 0, 0), "Primera parte");
        exigir(e.riesgoTemporal() == 0, "No contar como completo un pedido con parte en banco");
        e.programador().evaluar(1, new int[] {0}, new int[] {15}, 1);
        long delta = e.deltaRiesgoInsercionValorada(1, new int[] {0}, 1, 1);
        exigir(e.insertar(1, 0, 1), "Segunda parte");
        exigir(e.riesgoTemporal() == delta && delta == 289, "Usar ultima parte: 08:47 -> margen 13 -> riesgo 289");
        coherente(e);
        var copia = new EstadoAlns(i, new ProgramadorRuta(i));
        copia.copiarDesde(e);
        exigir(copia.riesgoTemporal() == e.riesgoTemporal(), "Copia riesgo");
        copia.quitar(1);
        exigir(copia.riesgoTemporal() == 0 && e.riesgoTemporal() == 289, "Copia independiente y retiro");
        copia.vaciar();
        exigir(copia.riesgoTemporal() == 0, "Vaciado invalida cache");
        // El numero de pedidos atendidos domina cualquier preferencia temporal.
        exigir(e.compararConHolgura(copia) < 0, "H debe dominar al riesgo");
        var reconstruida = new EstadoAlns(i, new ProgramadorRuta(i));
        new MotorInsercion(i, new ParametrosAlns()).reconstruir(reconstruida, new Aleatorio(3),
                PresupuestoComputo.deIteraciones(1).arrancar(), 3, 0);
        exigir(reconstruida.valor().h() == 0, "Reconstruccion de las dos partes");
        coherente(reconstruida);
    }

    private static void probarLlegadasConPausa() {
        var pedidos = List.of(InstanciasDePrueba.pedido(0, 60, 40, 2, 0, 36),
                InstanciasDePrueba.pedido(1, 5, 45, 2, 0, 36),
                InstanciasDePrueba.pedido(2, 60, 5, 2, 0, 36));
        var i = InstanciasDePrueba.fotografia(420, pedidos,
                List.of(InstanciasDePrueba.unidad("TA01", 420)), 1800, InstanciasDePrueba.parametros());
        var p = new ProgramadorRuta(i);
        exigir(p.evaluar(0, new int[] {0,1,2}, new int[] {2,2,2}, 3), "Evaluacion pausa");
        long[] llegadas = new long[3];
        exigir(p.copiarLlegadasEntregas(llegadas) == 3, "Tres llegadas");
        var ruta = p.programar(0, new int[] {0,1,2}, new int[] {2,2,2}, 3, false).ruta();
        exigir(ruta.incluyeAlimentacion(), "El caso debe ejercer la pausa");
        int j = 0;
        for (Parada parada : ruta.paradas()) if (parada.tipo() == TipoParada.ENTREGA) {
            exigir(llegadas[j++] == parada.minutoLlegada(), "La cache debe incluir el desplazamiento de pausa");
        }
    }

    private static void probarDeltasAleatorios() {
        List<Pedido> pedidos = new ArrayList<>();
        for (int p = 0; p < 8; p++) pedidos.add(InstanciasDePrueba.pedido(p,
                27 + p % 3, 14 + p % 2, p == 0 ? 30 : 2 + p, 420, 2 + p % 3));
        var i = instancia(pedidos, List.of(InstanciasDePrueba.unidad("TA01", 510),
                InstanciasDePrueba.unidad("TA02", 515), InstanciasDePrueba.unidad("TM01", 510)));
        var e = new EstadoAlns(i, new ProgramadorRuta(i));
        var azar = new Random(7);
        int comprobaciones = 0;
        for (int paso = 0; paso < 100; paso++) {
            e.quitar(azar.nextInt(e.tareas().cantidad()));
            if (e.tamanoBanco() == 0) continue;
            int tarea = e.bancoEn(azar.nextInt(e.tamanoBanco()));
            for (int u = 0; u < i.cantidadUnidades(); u++) {
                int longitud = e.longitudRuta(u);
                for (int pos = 0; pos <= longitud; pos++) {
                    long anterior = e.riesgoTemporal();
                    int[] secuencia = new int[longitud + 1];
                    int[] cantidades = new int[longitud + 1];
                    int j = 0;
                    for (int k = 0; k <= longitud; k++) {
                        int t = k == pos ? tarea : e.tareaEn(u, j++);
                        secuencia[k] = e.tareas().pedido(t);
                        cantidades[k] = e.tareas().unidades(t);
                    }
                    if (!e.programador().evaluar(u, secuencia, cantidades, secuencia.length)
                            || !e.programador().ultimaFactible()) continue;
                    long delta = e.deltaRiesgoInsercionValorada(u, secuencia, secuencia.length, tarea);
                    var copia = new EstadoAlns(i, new ProgramadorRuta(i));
                    copia.copiarDesde(e);
                    exigir(copia.insertar(u, pos, tarea), "Candidata debe seguir siendo factible");
                    exigir(copia.riesgoTemporal() - anterior == delta, "Delta de riesgo incremental incorrecto");
                    comprobaciones++;
                }
            }
            new MotorInsercion(i, new ParametrosAlns()).reconstruir(e, new Aleatorio(paso + 1),
                    PresupuestoComputo.deIteraciones(1).arrancar(), 2, 0);
        }
        exigir(comprobaciones > 50, "Cobertura de deltas insuficiente");
        coherente(e);
        System.out.println("Deltas de riesgo: " + comprobaciones + " candidatas contrastadas con copia reevaluada.");
    }

    private static void probarFabrica() {
        for (String valor : List.of("0", "15", "30", "1440")) {
            var propiedades = new Properties();
            propiedades.setProperty(HolguraTemporal.PROPIEDAD_MARGEN, valor);
            FabricaAlgoritmos.validar(propiedades);
            var a = (BusquedaAdaptativaVecindadAmplia) FabricaAlgoritmos.crear("ALNS", 123, propiedades);
            exigir(a.parametros().holguraObjetivoMinutos() == Integer.parseInt(valor),
                    "La fabrica no propago la propiedad explicita");
            exigir(a.toString().contains("holguraObjetivoMinutos=" + valor), "Falta parametro en traza");
        }
        for (String valor : List.of("-1", "1441", "abc")) {
            var propiedades = new Properties();
            propiedades.setProperty(HolguraTemporal.PROPIEDAD_MARGEN, valor);
            boolean rechazo = false;
            try { FabricaAlgoritmos.validar(propiedades); }
            catch (IllegalArgumentException esperado) { rechazo = true; }
            exigir(rechazo, "Debe rechazar margen invalido: " + valor);
        }
        exigir(FabricaAlgoritmos.clavesAlns().contains(HolguraTemporal.PROPIEDAD_MARGEN),
                "Debe publicar la clave admitida");
        System.out.println("Fabrica: propiedad explicita reconocida, propagada y validada.");
    }

    private static void probarAlns() {
        var i = instancia(pedidos(), List.of(InstanciasDePrueba.unidad("TA01", 510)));
        var a = new BusquedaAdaptativaVecindadAmplia(new org.kindbox.core.construccion.AhorrosClarkeWright(), 123);
        var r = a.resolver(i, PresupuestoComputo.deIteraciones(100).arrancar());
        exigir(r.solucion().h() == 0, "ALNS debe servir ambos pedidos");
        exigir(HolguraTemporal.riesgo(i, r.solucion(), 30) == 4, "ALNS debe conservar el empate robusto");
    }
}
