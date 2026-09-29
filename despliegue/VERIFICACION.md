# Verificación de entrega — 28 de septiembre de 2026

## Componentes y datos

- Java 21; Spring Boot 3.3.5; exportación estática de Next.js.
- 195 pruebas del núcleo y 27 del servicio: 222, sin fallos.
- Pruebas JavaScript de consultas y formato y compilación estática correctas.
- Datos publicados: 36 archivos mensuales de ventas y 36 de bloqueos normalizados.
- Mantenimiento: 666 registros únicos, 37 unidades por cada uno de 18 bimestres,
  enero de 2026 a diciembre de 2028. Se validaron fechas y duplicados.
- Nuevas regresiones: relevo durante acondicionamiento, reserva del retorno previo
  al mantenimiento incluso sin pedidos y exclusión durante el minuto 23:59.

## Navegador y proxy

Siete pruebas Playwright aprobadas en 1,3 minutos.

Servidor real iniciado con `./mvnw -q -pl service spring-boot:run`, datos publicados
y puerto 18080; interfaz compilada con proxy local en 14173. Se comprueban arranque
y cancelación con ambos algoritmos, errores 400/404/409, carga de averías,
WebSocket y reconexión, cambios de parámetros, desenlace natural, rutas directas,
mapa cartesiano, teclado y dimensiones de 360, 390, 768, 1024 y 1440 px.
Las contingencias FALLIDA y culminación con incumplimientos usan respuestas controladas
para garantizar esos casos. El resto usa el backend real.

El gesto de pellizco se comprobó con eventos táctiles de Chromium, incluyendo contactos
sobre marcadores. Las rutas y bloqueos se dibujan en Canvas; los marcadores conservan
botones con nombre accesible. No se carga Leaflet. No se realizó un benchmark comparativo
contra la versión anterior: no se atribuye un porcentaje de mejora al reemplazo.

Nginx 1.28.0 local, imagen oficial `nginx:stable-bookworm`, digest
`sha256:552e7481ca93ffccd046aa658dbbed22caefbc09c66fa7cd247cbb90b8a5c609`:
HTTPS autofirmado; acceso anónimo 401; página y salud autenticadas 200; origen ajeno
403; actualización WebSocket 101. Se adaptaron únicamente rutas de montaje y puertos
para la prueba. No se instaló Docker ni software adicional en la VM.

## Corridas de humo, no comparación de calidad

Comando por algoritmo: `CorrerEscenario <datos> DIA 2026-09-01 <algoritmo> 30 20260928 RAPIDO`.
Un día, 187 pedidos, presupuesto de 150 ms por llamada con reserva de cierre de 50 ms,
heap 640 MB y SerialGC. Bloqueos, mantenimiento y generación de averías habilitados.

| Algoritmo | Estado | Entregados | Pendientes | Incumplidos | Averías ejecutadas | Costo (S/) |
|---|---|---:|---:|---:|---:|---:|
| HGS | CULMINADA | 139 | 48 | 0 | 0 | 15845 |
| ALNS | CULMINADA | 135 | 51 | 1 | 1 | 14928 |

Ambas completaron 1440 minutos y 88 planificaciones, con partición de pedidos coherente
y costo coincidente con kilómetros por tipo. Son pruebas deliberadamente rápidas,
fuera del presupuesto operativo de 2–18 segundos. Los pendientes al corte no son todos
vencidos. No prueban superioridad de HGS ni operación sin incumplimientos; las averías
ejecutadas difieren porque dependen del estado de la unidad.

## Límites de esta verificación

No se midió CPU/memoria ni se ejecutó systemd en la VM del curso. SSH agotó el tiempo
de espera antes de autenticar; posteriormente el usuario delegó la instalación a su
DevOps. Se entrega el instalador para revisión y ejecución por ese responsable.
La comprobación de Nginx local no sustituye validar `nginx -t` en Ubuntu 24.04.
Los pendientes funcionales están descritos en README.md; en particular, no se certifica
la pausa obligatoria de cada conductor en recorridos de varios turnos.
