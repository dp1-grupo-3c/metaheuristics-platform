package org.kindbox.core.io;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.Comparator;

/** Ejecutable tambien sin JUnit; prueba la carga completa con ambas convenciones. */
public final class VerificarCompatibilidadDp1 {
    static void exigir(boolean c,String mensaje) { if(!c)throw new AssertionError(mensaje); }
    static void escribir(Path raiz,String nombre,String contenido)throws Exception {
        Path p=raiz.resolve(nombre);Files.createDirectories(p.getParent());Files.writeString(p,contenido);
    }
    static RepositorioDatos.DatosEscenario cargar(Path raiz)throws Exception {
        return new RepositorioDatos(raiz).cargar(LocalDate.of(2026,9,1),LocalDate.of(2026,9,5));
    }
    static void probar(Path raiz,boolean dp1)throws Exception {
        escribir(raiz,dp1?"ventas/2026/VENTAS.202609.TXT":"ventas/ventas202609.txt",
                "01d07h00m:30,20,c1234,3,4\n06d07h00m:30,20,c1235,2,4\n");
        escribir(raiz,dp1?"bloqueos/bloqueo.2609.txt":"bloqueos/202609.bloqueadas",
                "01d08h00m-01d09h00m:12,20,13,20\n");
        escribir(raiz,dp1?"mant.preventivo.09.10.txt":"mantenimiento/2026/mant.preventivo.09.10",
                "20260902:TA01\n");
        escribir(raiz,"ventas/desktop.ini","no es un archivo de ventas");
        escribir(raiz,"ventas/ventas202609.txt.bak","copia que no debe cargarse");
        var datos=cargar(raiz);
        exigir(datos.pedidos().size()==1,"Pedido de cinco dias cargado y fecha externa filtrada");
        exigir(datos.pedidos().get(0).cantidad()==3,"Cantidad original preservada");
        exigir(datos.bloqueos().size()==1,"Bloqueo reconocido");
        exigir(datos.mantenimientos().size()==1,"Mantenimiento reconocido");
        exigir(datos.avisos().stream().noneMatch(a->a.contains("el mes se omite")||a.contains("sin mantenimiento")),
                "No omitir datos presentes");
        // El mismo mantenimiento en ambas ubicaciones no debe programarse dos veces.
        escribir(raiz,dp1?"mantenimiento/2026/mant.preventivo.09.10":"mant.preventivo.09.10.txt",
                "20260902:TA01\n");
        exigir(cargar(raiz).mantenimientos().size()==1,"Desduplicar entradas de mantenimiento");
        // Dos aliases para el mismo mes son ambiguos; no elegir una copia en silencio.
        escribir(raiz,dp1?"ventas/ventas202609":"ventas/ventas.202609.txt",
                "01d07h00m:30,20,c1234,3,4\n");
        boolean ambiguo=false;
        try{cargar(raiz);}catch(java.io.IOException e){ambiguo=e.getMessage().contains("ambiguos");}
        exigir(ambiguo,"Detectar archivos mensuales duplicados");
    }
    static void probarDosMeses(Path raiz)throws Exception {
        escribir(raiz,"ventas/ventas.202609.txt","30d23h00m:30,20,c1234,3,4\n");
        escribir(raiz,"ventas/ventas.202610.txt","01d07h00m:30,20,c1234,3,4\n");
        var d=new RepositorioDatos(raiz).cargar(LocalDate.of(2026,9,30),LocalDate.of(2026,10,1));
        exigir(d.pedidos().size()==2,"Reconocer dos meses");
        exigir(d.pedidos().get(0).id()!=d.pedidos().get(1).id(),"IDs correlativos sin colisiones");
    }
    public static void main(String[]args)throws Exception {
        Path temporal=Files.createTempDirectory("prueba-compatibilidad-dp1-");
        try {
            probar(temporal.resolve("dp1"),true);probar(temporal.resolve("canonico"),false);
            probarDosMeses(temporal.resolve("dos-meses"));
            System.out.println("OK: DP1 y canonico; ventas, bloqueos, mantenimiento, meses, filtrado y duplicados.");
        } finally {
            try(var paths=Files.walk(temporal)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
            }
        }
    }
}
