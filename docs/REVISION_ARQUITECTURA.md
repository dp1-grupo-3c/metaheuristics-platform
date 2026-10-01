# Arquitectura de KindBox y ruta de implementación

Revisión del 30 de septiembre de 2026 del documento `24.dis.arquitectura.v02.pdf`,
la Lista de Exigencias v02 y los repositorios `metaheuristics-platform` y `web-app`.
La operación diaria en tiempo real es parte del producto, junto con las simulaciones.
La revisión identifica brechas arquitectónicas; no certifica todas las reglas logísticas
ni el rendimiento en el servidor del laboratorio.

## Decisión sobre los repositorios

Conservar los dos repositorios. `metaheuristics-platform` ya es un backend multimódulo:
`core` contiene dominio, algoritmos y motor; `service` contiene Spring Boot, REST y
WebSocket; `experiments` conserva los experimentos. Añadir persistencia y gestión de
operación en `service`; mantener `core` independiente de Spring y MySQL.

`web-app` debe ser la interfaz de producto. `visualizador/` puede conservarse como
herramienta de diagnóstico mientras se migra la entrega. El empaquetador actual de
`despliegue/empacar.sh` todavía construye ese visualizador, no `web-app`.
No eliminarlo hasta reemplazar y verificar su recorrido de despliegue.

No hace falta otro repositorio para la base de datos, el diario, el motor ni Compose.
Las migraciones pertenecen al backend y la integración de despliegue puede vivir en
`metaheuristics-platform/despliegue/`, indicando los commits de ambos repositorios.
Un tercer repositorio de infraestructura solo se justificaría por un ciclo de entrega
independiente, que no es necesario para la estructura observada.

## Estado del producto

| Área | Evidencia en código | Trabajo pendiente |
| --- | --- | --- |
| Planificación | `core` implementa HGS, ALNS, presupuesto y arranque desde plan vigente | Medir calidad y tiempos del modelo actual en la VM; la arquitectura no demuestra el cumplimiento |
| Motor y notificaciones | `MotorSimulacion`, `ObservadorSimulacion`, `DifusorInstantaneas` | Conservarlos; extender contratos solo cuando una función lo necesite |
| API y tiempo real | `service` publica REST y `/ws/simulacion`; la web usa ambos | Versionado de estado, resincronización y aislamiento por contexto |
| Día a día | `ServicioSimulacion` admite `DIA_A_DIA`, factor 1 y reloj acompasado | Operación continua, pedidos dinámicos, inicio/reanudación en hora operativa y continuidad entre fechas |
| Simulaciones | 5D y colapso se pueden iniciar desde la web | Pausa/reanudación/velocidad, configuración completa, archivos y checkpoints |
| Persistencia | Corridas en `ConcurrentHashMap`; poda de resultados en memoria | MySQL, migraciones, historial durable, comandos y recuperación |
| Entrada de datos | Lectura de escenarios del filesystem y carga multipart de averías | Registro manual de pedidos e importación de pedidos/escenarios desde producto |
| Seguimiento de cliente | `ClientScreen` usa el store operativo y su lista de pedidos | Consulta y canal limitados en el servidor al código consultado; no exponer flota ni otros pedidos |
| Roles | La selección de vistas no equivale a permisos de servidor | Adaptar acciones por rol y definir qué protección exige el uso previsto |
| Despliegue | Nginx, JAR y systemd para el visualizador anterior | Empaquetar `web-app`; decidir y validar Compose o documentar systemd como despliegue real |

## Operación diaria y simulación son contextos distintos

El código permite una sola corrida activa global. El store y el canal WebSocket siguen
esa misma restricción. Una operación diaria a 1× puede ocupar ese lugar toda la jornada.
Si el producto debe simular mientras mantiene la operación, esa exclusión global no sirve.
Recomiendo un contexto operativo y contextos de simulación aislados, dentro del mismo
proceso Java inicialmente. Cada uno necesita estado, parámetros, comandos e identificador
propios. El cliente se suscribe al contexto correspondiente, en vez de seguir cualquier
cabecera de corrida difundida globalmente.

Eso no obliga a ejecutar varios planificadores pesados simultáneamente. Un ejecutor
acotado puede priorizar la operación y poner en cola la planificación de simulaciones.
Si se exige además terminar 5D en 30–60 minutos durante la operación, hay que medir si
la VM de 2 vCPU y 2 GB documentada lo sostiene; solo entonces evaluar más recursos o
un proceso trabajador independiente. Crear otro repositorio no resuelve esa competencia.

`DIA_A_DIA` actualmente representa una jornada de datos precargados desde las 00:00.
El factor 1 significa que un minuto del escenario tarda un minuto real; no sincroniza
la fecha y hora del escenario con la hora actual del servidor. Tampoco proporciona
operación ininterrumpida, pedidos creados durante la ejecución ni continuidad al día
siguiente. No debe declararse el producto operativo completo por conectar ese modo.

## Cambios de esta revisión

En `web-app` se conectó la pantalla diaria al modo `DIA_A_DIA`, con arranque explícito,
fecha, detención confirmada, mapa, pedidos, métricas y averías. Las lecturas diarias no
usan fotografías de una simulación. El arranque diario desactiva averías aleatorias;
las incidencias operativas se registran explícitamente. La pantalla informa los límites
de la jornada precargada. No se inicia una operación por el mero hecho de abrir una pestaña.

Se eliminó la cancelación automática de corridas al iniciar otra. Un conflicto HTTP 409
se comunica al usuario. El registro manual de pedidos devuelve ahora indisponibilidad,
en lugar de confirmar falsamente que guardó un pedido y limpiar el formulario.
El mapa toma el inventario de la instantánea cuando está disponible.

En el motor, el presupuesto diario se limita a 18 segundos por planificación. Antes,
SA=30 minutos y K=1 permitían 1 080 000 ms, es decir, 18 minutos de búsqueda síncrona.
El tope toma el extremo superior del rango operativo ya descrito en el núcleo; es un
límite inicial que requiere medición, no una garantía de latencia total. Los presupuestos
experimentales de 5D y colapso se conservan. El ejecutable experimental informa el mismo
presupuesto que usa el motor.

## Qué conservar y qué corregir en el documento

Conservar el monolito modular, la interfaz de algoritmos, el motor común, los observadores,
REST para comandos, el canal de estado, el mismo origen web y el almacenamiento durable.
MySQL es razonable para pedidos e historial; no hace falta serializar cada fotografía
completa a tablas ni añadir un sistema de mensajería distribuido.

1. **La vista frontend describe otra implementación.** `web-app` usa React, un store
   con `useSyncExternalStore`, fetch, WebSocket y mapa SVG. No usa Leaflet ni TanStack
   Query. El visualizador anterior usa Canvas. No introducir esas bibliotecas ni un
   montador imperativo solo para coincidir con el diagrama. Documentar el frontend elegido.
2. **Los contenedores C4 no determinan repositorios ni contenedores Docker.** Mostrar
   navegador, entrega web y almacenes de datos puede ser útil, pero un directorio de
   archivos no necesita un servicio ejecutable propio. Cinco elementos C4 y tres
   contenedores Docker no son, por sí solos, una contradicción.
3. **WebSocket no garantiza consistencia.** Hacen falta identificador de contexto,
   revisión monotónica, rechazo de estados viejos, instantánea al reconectar y política
   para clientes lentos. Hoy además existe sondeo REST cada segundo para detalle y
   cada tres segundos para pedidos; no afirmar ausencia de sondeo. Separar la cadencia
   en segundos reales de los pasos del reloj simulado.
4. **El diario no garantiza reproducción exacta.** UUID más cola no bastan. Persistir
   secuencia, contenido, resultado y momento efectivo; deduplicar transaccionalmente.
   Registrar un comando aceptado no implica que ya se aplicó. Los algoritmos con límite
   de tiempo pueden tomar decisiones diferentes aun con igual semilla; para recuperar
   exactamente hay que conservar también planes/decisiones, o usar un replay determinista.
5. **Un checkpoint no es la fotografía de pantalla.** Debe incluir cola de eventos,
   reloj, rutas en curso, entregas parciales, carga, inventarios, incidencias futuras,
   contadores/estado aleatorio, configuración, versión y huellas de entradas, junto con
   la secuencia del diario aplicada. Guardarlo en un límite consistente del motor.
   Tener volúmenes Docker no implementa nada de esto.
6. **H.14 está mal resumido.** La LE lo clasifica como deseable y pide volver a cualquier
   checkpoint diario, descartando resultados posteriores. El PDF lo usa para justificar
   recuperación tras reinicio. Distinguir recuperación y retroceso, definir una nueva rama
   de ejecución al retroceder y actualizar esa trazabilidad.
7. **El canal global contradice la vista Cliente.** No enviar todas las instantáneas a
   todos los usuarios si I.4 dice que el cliente solo ve sus pedidos. Filtrar en el servidor
   tanto REST como WebSocket; ocultar controles o filas en React no cumple ese contrato.
8. **Los parámetros de operación y escenario deben separarse.** H.1 exige semáforos
   específicos de simulación. Hoy iniciar una simulación modifica primero el semáforo
   global con otro HTTP request. Falta un arranque atómico con parámetros propios; incluso
   un arranque rechazado por carrera puede haber modificado esos umbrales.
9. **El despliegue es una propuesta, no el existente.** Compose es razonable si el
   laboratorio lo permite, pero Nginx y systemd también pueden satisfacer la entrega.
   `web-app` no configura `output: "export"`; el build de Next por sí solo no deja el
   paquete estático esperado para Nginx. Si se elige exportación, separar rewrites de
   desarrollo del proxy Nginx y comprobar todas las rutas. Ver la
   [documentación de exportación estática de Next.js](https://nextjs.org/docs/app/guides/static-exports).
10. **No confundir presupuesto de algoritmo con duración garantizada del sistema.**
    Lectura, construcción de instancia, planificación, serialización y persistencia
    consumen tiempo. La recuperación y el rendimiento deben demostrarse con pruebas.
    Tampoco se justifican ocho capas o una interfaz por clase solo por dibujarlas.

El documento además conserva `v01` en encabezados y una portada de “Informe de Diseño
de Experimento”, aunque su contenido es arquitectura v02. Corregir ambos.

## Implementación propuesta y criterios de aceptación

1. **Base operativa.** Definir contexto de operación, reloj/zona horaria y transición
   entre días; conservar pendientes y vehículos en curso. Una operación debe poder
   reanudarse sin reiniciar inventarios ni perder pedidos al cruzar medianoche.
2. **Pedidos y comandos durables.** Añadir esquema y migraciones en `service`, registro
   manual/importación, validación e idempotencia. Generalizar la cola de averías del motor
   a comandos; el hilo del motor aplica cambios en orden. Dos envíos del mismo comando
   deben generar un solo pedido y dos sesiones deben observar su resultado.
3. **Aislamiento de escenarios.** Parametrización por contexto y suscripción por corrida.
   Una simulación no altera semáforos, inventarios, pedidos ni reloj de operación.
   Iniciar/cancelar simulaciones nunca detiene la operación implícitamente. Definir
   explícitamente si la entrega exige simultaneidad y medir la política de CPU elegida.
4. **Persistencia y recuperación.** Guardar configuración, entradas identificadas,
   comandos, planes y resultados; después checkpoints completos y replay. Matar el
   proceso entre aceptación y aplicación no debe perder ni duplicar pedidos. Una prueba
   de retroceso debe descartar efectos posteriores sin repetir entregas.
5. **Cerrar recorridos web.** Seguimiento limitado por código, acciones según rol,
   configuración completa, pausa/velocidad y paginación real. `useOrders` hoy pide solo
   los primeros 500 pedidos; paginar en React sobre esos 500 no cubre el resto. El historial
   de averías de la web solo contiene las enviadas desde esa sesión: usar datos del servidor.
6. **Entrega integrada.** Empaquetar `web-app` y backend con identificadores de versión,
   desplegar bajo el mismo origen y verificar REST/WSS desde dos navegadores. Medir el
   escenario de cinco días en el servidor objetivo, incluyendo carga operativa si se exige.

Los pasos 1–4 completan la base del producto operativo. Los cambios de esta revisión
habilitan el recorrido diario existente y corrigen errores concretos; no implementan
por sí solos ese conjunto pendiente.

## Verificación de los cambios

`./mvnw -q verify`: 224 pruebas Java, sin fallos ni omisiones. En `web-app`,
`npm test`: seis regresiones; `npm run lint` y `npm run build`: correctos.
Una prueba local con Chromium y el JAR real confirmó inicio diario, factor 1,
monitoreo desde dos pestañas con dos conexiones WebSocket, rechazo 409 de un arranque
competidor y detención explícita, sin errores JavaScript. Se inspeccionó la pantalla.
Se usó el dataset reducido de enero, sin esperar una jornada completa ni medir calidad
de rutas o carga del servidor del laboratorio. MySQL, recuperación y operación continua
no se probaron porque todavía no están implementados.
