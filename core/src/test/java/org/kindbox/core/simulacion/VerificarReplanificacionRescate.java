package org.kindbox.core.simulacion;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.kindbox.core.io.*;
import org.kindbox.core.metaheuristica.*;
import org.kindbox.core.modelo.*;
import org.kindbox.core.problema.*;
import org.kindbox.core.evaluacion.*;
import org.kindbox.core.simulacion.*;

/** Regresiones deterministas del rescate y la replanificacion reactiva. */
public class VerificarReplanificacionRescate {
 static void exigir(boolean c,String m){if(!c)throw new AssertionError(m);}
 static Object campo(Object o,String nombre)throws Exception{
  Field f=org.kindbox.core.simulacion.MotorEjecucion.class.getDeclaredField(nombre);f.setAccessible(true);return f.get(o);
 }
 static void reloj(MotorSimulacion m,long minuto)throws Exception{
  Field f=org.kindbox.core.simulacion.MotorEjecucion.class.getDeclaredField("minutoActual");f.setAccessible(true);f.setLong(m,minuto);
 }
 static Object invocar(Object o,String nombre,Class<?>[] tipos,Object...args)throws Exception{
  Method f=org.kindbox.core.simulacion.MotorEjecucion.class.getDeclaredMethod(nombre,tipos);f.setAccessible(true);
  try{return f.invoke(o,args);}catch(InvocationTargetException ex){throw new RuntimeException(ex.getCause());}
 }
 static final class Planificador implements Algoritmo {
  final List<Long> instantes=new ArrayList<>();
  final List<Boolean> motoIncluida=new ArrayList<>();
  public String nombre(){return "PRUEBA_RESCATE";}
  public ResultadoPlanificacion resolver(InstanciaPlanificacion i,PresupuestoComputo b){
   instantes.add(i.minutoActual());int u=i.indiceDeUnidad("TM05"),p=i.indiceDePedido(897);
   motoIncluida.add(u>=0);
   Solucion s=Solucion.vacia(i);
   if(u>=0&&p>=0&&i.unidadCarga(u)>=7){
    var pr=new ProgramadorRuta(i).programar(u,new int[]{p},new int[]{7},1,false);
    if(pr.factible())s=new Solucion(List.of(pr.ruta()),Map.of(),ValorObjetivo.cero());
   }
   return new ResultadoPlanificacion(nombre(),s,null,0,1,1,Map.of());
  }
 }
 static final class Traza implements ObservadorSimulacion {
  final List<String> eventos=new ArrayList<>();
  public void alIniciarReplanificacion(long m,String motivos){eventos.add("PLAN:"+m+":"+motivos);}
  public void alDespacharRescate(long m,String a,String r,long l,long f,int c,int libre){eventos.add("DESPACHO:"+m);}
  public void alIniciarTrasvase(long m,String a,String r,long f){eventos.add("INICIO:"+m);}
  public void alCompletarTrasvase(long m,String a,String r,int c,int ca,int cr){eventos.add("FIN:"+m+":"+c);}
  public void alNoAsignarRescate(long m,String a,String motivo){eventos.add("SIN_RESCATE:"+m);}
 }
 record Fixture(MotorSimulacion motor,ColaEventos cola,Planificador algoritmo,Traza traza){}
 static Fixture crear(int cargaMoto,boolean segundaAveria,boolean sinRescate)throws Exception{
  var dia=LocalDate.of(2026,9,1);
  var auto=UnidadTransporte.de("TA08",Ciudad.nodo(4,5));
  auto.cargaABordo(15);auto.estado(EstadoUnidad.EN_RUTA);
  List<UnidadTransporte> unidades=new ArrayList<>();unidades.add(auto);
  if(!sinRescate){
   var moto=UnidadTransporte.de("TM05",Ciudad.nodo(3,4));moto.cargaABordo(cargaMoto);
   moto.estado(EstadoUnidad.DISPONIBLE);unidades.add(moto);
  }
  if(segundaAveria){
   var otro=UnidadTransporte.de("TA09",Ciudad.nodo(4,6));otro.cargaABordo(15);
   otro.estado(EstadoUnidad.EN_RUTA);unidades.add(otro);
  }
  var pedido=new Pedido(897,"c897",Ciudad.nodo(5,6),7,8124,8);
  var flota=new LectorFlota.FlotaLeida(unidades,Map.of(),Map.of(),Map.of());
  var datos=new RepositorioDatos.DatosEscenario(CalendarioEscenario.desde(dia),dia,dia.plusDays(6),
    flota,List.of(pedido),List.of(),List.of(),List.of());
  var cfg=new ConfiguracionEscenario(TipoEscenario.COLAPSO,dia,dia.plusDays(6),30,144000,
    ModoReloj.LIBRE,"PRUEBA_RESCATE",1,15,false,0,true);
  var alg=new Planificador();
  var traza=new Traza();
  var motor=new MotorSimulacion(datos,cfg,new ParametrosOperacion(),alg,List.of(traza));
  var cola=(ColaEventos)campo(motor,"cola");cola.vaciar();
  cola.programar(8580,TipoEvento.REPLANIFICACION);cola.programar(8610,TipoEvento.REPLANIFICACION);
  motor.estado().registrarPedido(0);reloj(motor,8560);
  return new Fixture(motor,cola,alg,traza);
 }
 static void averiar(Fixture f,int indice)throws Exception{
  invocar(f.motor,"averiar",new Class<?>[]{int.class,int.class},indice,-2);
 }
 static void procesarHasta(Fixture f,long hasta)throws Exception{
  while(!f.cola.vacia()&&f.cola.asomar().minuto()<=hasta){
   var e=f.cola.siguiente();reloj(f.motor,e.minuto());
   invocar(f.motor,"procesar",new Class<?>[]{Evento.class},e);
   if(e.tipo()==TipoEvento.FIN_SERVICIO&&e.minuto()==8595){
    int antes=f.motor.estado().unidad(1).unidad().cargaABordo()
      +f.motor.estado().unidad(0).unidad().cargaABordo();
    invocar(f.motor,"procesar",new Class<?>[]{Evento.class},e);
    int despues=f.motor.estado().unidad(1).unidad().cargaABordo()
      +f.motor.estado().unidad(0).unidad().cargaABordo();
    exigir(antes==despues,"Un FIN_SERVICIO obsoleto duplico carga");
   }
  }
 }
 static int contar(ColaEventos cola,TipoEvento tipo,long minuto){
  int cuenta=0;List<Evento> eventos=new ArrayList<>();
  while(!cola.vacia()){Evento e=cola.siguiente();eventos.add(e);if(e.tipo()==tipo&&e.minuto()==minuto)cuenta++;}
  // Solo para pruebas: no restaurar numeros de secuencia pues ya no se ejecuta esa cola.
  return cuenta;
 }
 static void probarOrden(){
  ColaEventos c=new ColaEventos();
  for(TipoEvento t:List.of(TipoEvento.REPLANIFICACION,TipoEvento.FIN_SERVICIO,
    TipoEvento.LLEGADA_A_PARADA,TipoEvento.AVERIA))c.programar(100,t);
  exigir(c.siguiente().tipo()==TipoEvento.AVERIA,"Orden averia");
  exigir(c.siguiente().tipo()==TipoEvento.LLEGADA_A_PARADA,"Orden llegada");
  exigir(c.siguiente().tipo()==TipoEvento.FIN_SERVICIO,"Orden servicio");
  exigir(c.siguiente().tipo()==TipoEvento.REPLANIFICACION,"Planificacion tras estado actualizado");
  System.out.println("OK orden: averia -> llegada -> fin servicio -> replanificacion.");
 }
 static void probarRescate()throws Exception{
  Fixture f=crear(0,false,false);
  var auto=f.motor.estado().unidad(0);auto.secuenciaMovimiento(999999);
  var movimientoViejo=new Evento(8565,TipoEvento.LLEGADA_A_PARADA,999999,0,0);
  averiar(f,0);
  exigir(!auto.vigente(movimientoViejo),"La averia debe invalidar el movimiento anterior");
  var moto=f.motor.estado().unidad(1);
  exigir(moto.misionTrasvase()&&moto.unidadAsistida()==0,"Rescate no despachado");
  exigir(moto.minutoLlegadaParada(0)==8565&&moto.minutoSalidaParada(0)==8595,"Tiempos de rescate");
  procesarHasta(f,8595);
  exigir(!moto.misionTrasvase(),"Mision no liberada");
  exigir(auto.unidad().cargaABordo()==7&&moto.unidad().cargaABordo()==8,"Trasvase no conserva 15 paquetes");
  exigir(f.motor.estado().pendienteDe(0)==7,"Recoger carga no equivale a entregar el pedido");
  int indice23=f.algoritmo.instantes.indexOf(8580L);
  exigir(indice23>=0&&!f.algoritmo.motoIncluida.get(indice23),"No debe interrumpirse la mision a las 23:00");
  procesarHasta(f,8604);
  reloj(f.motor,8604);
  invocar(f.motor,"resolverVencimientos",new Class<?>[]{});
  exigir(f.motor.estado().pedidosEntregados()==1&&f.motor.estado().pedidosIncumplidos()==0,
    "La replanificacion de 23:15 debe permitir entregar a 23:20");
  exigir(f.algoritmo.instantes.equals(List.of(8560L,8580L,8595L)), "Instantes reactivos incorrectos");
  exigir(f.motor.estado().unidad(0).unidad().cargaABordo()
    +f.motor.estado().unidad(1).unidad().cargaABordo()
    +f.motor.estado().metricas().unidadesEntregadas()==15,"Conservacion global de paquetes");
  exigir(f.traza.eventos.contains("PLAN:8560:AVERIA:TA08"),"Motivo de averia publicado");
  exigir(f.traza.eventos.contains("PLAN:8595:FIN_TRASVASE:TM05"),"Motivo fin trasvase publicado");
  exigir(Collections.frequency(f.traza.eventos,"FIN:8595:8")==1,"Notificar una sola transferencia real");
  exigir(f.traza.eventos.containsAll(List.of("DESPACHO:8560","INICIO:8565")),"Notificar despacho e inicio");
  System.out.println("OK rescate real: eventos automaticos 22:40 y 23:15, entrega 23:20 antes de 23:24.");
 }
 static void probarCapacidadYDuplicados()throws Exception{
  Fixture f=crear(3,false,false);averiar(f,0);procesarHasta(f,8595);
  exigir(f.motor.estado().unidad(0).unidad().cargaABordo()==10,"El rescate solo puede retirar 5");
  exigir(f.motor.estado().unidad(1).unidad().cargaABordo()==8,"No sobrepasar capacidad de moto");
  exigir(f.motor.estado().pendienteDe(0)==7,"Trasvase no debe reducir deuda de pedido");
  Fixture dos=crear(0,true,false);averiar(dos,0);averiar(dos,2);
  exigir(dos.motor.estado().unidad(1).unidadAsistida()==0,"Se reasigno el mismo rescatista a dos averias");
  int cuenta=dos.motor.estado().metricas().averiasPorTipo().get(2);averiar(dos,0);
  exigir(cuenta==dos.motor.estado().metricas().averiasPorTipo().get(2),"Averia duplicada contabilizada");
  Fixture ninguno=crear(0,false,true);averiar(ninguno,0);
  exigir(ninguno.motor.estado().pendienteDe(0)==7,"Sin rescate debe conservar pendiente");
  exigir(ninguno.traza.eventos.contains("SIN_RESCATE:8560"),"Notificar ausencia de rescatista");
  System.out.println("OK carga: capacidad libre, conservacion, evento obsoleto, averias simultaneas y ausencia de rescatista.");
 }
 static void probarAgrupacion()throws Exception{
  Fixture f=crear(0,false,true);f.cola.vaciar();
  for(int i=0;i<3;i++)invocar(f.motor,"replanificarPorBloqueo",new Class<?>[]{});
  exigir(contar(f.cola,TipoEvento.REPLANIFICACION,8560)==1,"Agrupar avisos del mismo minuto");
  Fixture periodica=crear(0,false,true);reloj(periodica.motor,8580);
  invocar(periodica.motor,"replanificarPorBloqueo",new Class<?>[]{});
  exigir(contar(periodica.cola,TipoEvento.REPLANIFICACION,8580)==1,"No duplicar periodica pendiente");
  Fixture posterior=crear(0,false,true);posterior.cola.vaciar();reloj(posterior.motor,8580);
  invocar(posterior.motor,"replanificarPorBloqueo",new Class<?>[]{});
  exigir(contar(posterior.cola,TipoEvento.REPLANIFICACION,8580)==1,"Un cambio posterior debe volver a planificar en el mismo minuto");
  ColaEventos c=new ColaEventos();
  Evento primero=c.programar(100,TipoEvento.REPLANIFICACION);
  exigir(c.programar(100,TipoEvento.REPLANIFICACION)==primero,"Reutilizar cita pendiente");
  c.siguiente();
  exigir(c.programar(100,TipoEvento.REPLANIFICACION).secuencia()!=primero.secuencia(),"Permitir otra tras consumir la primera");
  c.vaciar();exigir(c.programar(100,TipoEvento.REPLANIFICACION)!=primero,"Limpiar indice de citas");
  System.out.println("OK agrupacion: periodicas, incidencias simultaneas y cambio posterior en el mismo minuto.");
 }
 public static void main(String[]args)throws Exception{
  probarOrden();probarRescate();probarCapacidadYDuplicados();probarAgrupacion();
  System.out.println("REGRESIONES DE REPLANIFICACION Y RESCATE COMPLETADAS.");
 }
}
