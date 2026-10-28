# Operaciones diarias

El backend operativo se publica en `/api/operaciones` y `/ws/operaciones`. Permite iniciar sin pedidos, incorporar pedidos durante la ejecucion, planificar con ALNS o HGS, registrar averias y continuar entre dias sin reiniciar vehiculos, cargas o inventarios.

## Componentes

`core.operacion` contiene `ConfiguracionOperacion`, `ContextoOperacion`, `MotorOperacion`, `RelojOperacion`, `RegistroPedidosOperacion`, `PedidoOperativo`, `EstadoPedido` y los comandos. `service.operacion.ServicioOperacion` coordina el ciclo cada 100 milisegundos. `ControladorOperacion` publica HTTP y `ManejadorWebSocketOperacion` entrega instantaneas una vez por segundo.

`MotorEjecucion` contiene las reglas compartidas de movimiento, carga, servicio, averias, trasvase y construccion de instancias. `MotorSimulacion` conserva el constructor y la interfaz que utilizan las simulaciones existentes. La ejecucion operativa utiliza pasos controlados por su reloj y una agenda que se extiende; no llama al bucle finito de simulacion.

La carga operativa usa `RepositorioDatos.cargarEntorno`, que lee flota, bloqueos y mantenimiento, sin leer ventas historicas ni exigir que existan pedidos. El calendario interno conserva su referencia inicial a medianoche para mantener los turnos originales. Los bloqueos activos del mes anterior se conservan hasta su fin aunque el siguiente archivo mensual no los repita.

## Inicio y reloj

La primera operacion inicia en la fecha y hora del servidor en `America/Lima`. Todas las unidades comienzan en el almacen central. La zona es configurable al crear la operacion; se recomienda mantener `America/Lima`, sin cambios estacionales. Un retroceso del reloj del servidor no hace retroceder la ejecucion.

El nucleo representa el tiempo en minutos enteros. La fecha efectiva de registro manual se asigna al aplicar el comando y se trunca al minuto. La reposicion conserva la convencion existente del minuto 1439 del dia. La hora de servicio es de 60 minutos por entrega, incluida cada entrega parcial; el plazo de un pedido limita la llegada al destino y no agrega esa hora al compromiso.

La agenda se extiende al cruzar la fecha y cubre tres dias futuros. Conserva servicios, rutas, cargas, pendientes e incidencias. Una operacion sin pedidos sigue esperando entradas. El planificador se convoca al aplicar un pedido, por incidencias y reposicion, y periodicamente segun el intervalo configurado.

## API

| Metodo | Ruta | Respuesta |
| --- | --- | --- |
| POST | `/api/operaciones` | 201 con el contexto creado |
| GET | `/api/operaciones/actual` | Ultima operacion registrada, incluso si termino |
| GET | `/api/operaciones/{id}` | Instantanea, pedidos, parametros, incidentes, revision y estado |
| POST | `/api/operaciones/{id}/pedidos` | 202 con el estado del comando de alta |
| GET | `/api/operaciones/{id}/comandos/{comandoId}` | Estado de aceptacion, aplicacion o rechazo |
| GET | `/api/operaciones/{id}/pedidos` | Pedidos paginados del registro vivo |
| GET | `/api/operaciones/{id}/metricas` | Indicadores acumulados |
| POST | `/api/operaciones/{id}/averias` | 202 con el comando de averia |
| PUT | `/api/operaciones/{id}/parametros` | 202 con el comando de parametros |
| POST | `/api/operaciones/{id}/detener` | Contexto detenido con sus datos consultables |

`GET /pedidos` admite `pagina` desde cero, `tamano` entre 1 y 500, `busqueda` y `soloActivos`. Devuelve `contextoId`, `revision`, `pedidos`, `pagina`, `tamanoPagina`, `totalPedidos` y `totalPaginas`. Los filtros se aplican en el servidor sobre el registro completo.

Una operacion o comando inexistente devuelve 404. Una solicitud invalida devuelve 400. Un inicio incompatible, alta posterior al cierre o reutilizacion de identificador con otro contenido devuelve 409. El formato de error es el mismo de los controladores de simulacion.

## Ejemplo de inicio

```sh
curl -X POST http://localhost:8080/api/operaciones \
  -H 'Content-Type: application/json' \
  -d '{"zonaHoraria":"America/Lima","saltoMinutos":30,"algoritmo":"ALNS","semilla":20262,"presupuestoPlanificacionMs":2000}'
```

El cuerpo puede omitirse. Los valores anteriores son los predeterminados. El intervalo admite de 1 a 1440 minutos y el presupuesto de busqueda de 50 a 18000 milisegundos. No hay fecha final ni factor de aceleracion operativo.

Conservar `contextoId` de la respuesta para los comandos siguientes. En los ejemplos, `OPERACION_ID` representa ese identificador.

## Ejemplo de alta

```sh
curl -X POST "http://localhost:8080/api/operaciones/${OPERACION_ID}/pedidos" \
  -H 'Content-Type: application/json' \
  -d '{"solicitudId":"pedido-operador-001","codigoCliente":"c123","nombreCliente":"Ana Perez","telefonoCliente":"999111222","direccion":"Calle Uno","x":28,"y":14,"cantidad":2,"prioridad":"NORMAL"}'
```

Todos los campos son obligatorios. X esta entre 0 y 70, Y entre 0 y 50 y la cantidad es positiva. La API acepta el siguiente catalogo cerrado:

| Prioridad | Plazo en horas |
| --- | --- |
| NORMAL | 36 |
| PRIORITARIO_4 | 18 |
| PRIORITARIO_3 | 12 |
| PRIORITARIO_2 | 8 |
| PRIORITARIO_1 | 4 |

El cliente no decide la fecha de registro ni envia un plazo libre. Un nombre, telefono o direccion vacio invalida la solicitud. Se limitan las longitudes de los campos y la cola admite hasta 4096 comandos pendientes.

La respuesta 202 identifica un comando aceptado, no una asignacion de vehiculo. Consultar `/api/operaciones/{id}/comandos/pedido-operador-001` hasta que `estado` sea `APLICADO` o `RECHAZADO`. Un comando aplicado incluye `pedidoId` y la hora efectiva en `aplicado`. El alta aplicada queda inicialmente `REGISTRADO` y el planificador trabaja despues sobre ella.

Repetir exactamente la misma solicitud con igual `solicitudId` devuelve el mismo resultado y crea un solo pedido. Cambiar su contenido conservando la clave genera conflicto. La deduplicacion es por operacion y cubre el tiempo de vida del proceso.

## Ejemplos de averia y parametros

```sh
curl -X POST "http://localhost:8080/api/operaciones/${OPERACION_ID}/averias" \
  -H 'Content-Type: application/json' \
  -d '{"solicitudId":"averia-001","placa":"TA01","tipo":1}'

curl -X PUT "http://localhost:8080/api/operaciones/${OPERACION_ID}/parametros" \
  -H 'Content-Type: application/json' \
  -d '{"solicitudId":"parametros-001","velocidades":{"AUTO":35,"MOTO":25},"ambar":250,"verde":500}'
```

Los tipos de averia admitidos son 1, 2 y 3. La unidad debe pertenecer a la flota y no estar ya deshabilitada al aplicar el comando. El rechazo queda consultable si el conflicto aparece despues de la aceptacion. Las averias operativas se registran explicitamente; no se generan al azar.

Las velocidades deben ser positivas y finitas, con claves `AUTO`, `MOTO` o `BICICLETA`. Los umbrales se envian juntos y cumplen `0 <= ambar < verde`. Cada operacion posee parametros independientes de los parametros globales de simulacion. El siguiente calculo captura una instantanea inmutable.

## Pedidos, calculo y consistencia

Cada pedido expone datos del cliente, destino, cantidad, pendiente, prioridad, registro, limite, estado, indicador de incumplimiento y transiciones con fecha. Los estados son `REGISTRADO`, `PLANIFICADO`, `EN_TRANSITO`, `EN_REPLANIFICACION`, `ENTREGADO_PARCIALMENTE`, `ENTREGADO` y `CANCELADO`. El alcance actual implementa el alta y la ejecucion; los endpoints de modificacion y cancelacion individual quedan para una ampliacion.

El incumplimiento es un indicador separado para conservar el remanente y la trazabilidad, siguiendo las ventanas duras del nucleo existente. La identidad de metricas es registrados igual a entregados mas pendientes mas incumplidos. Las entregas parciales son un subconjunto, no otra categoria que deba sumarse.

Solo el ciclo operativo modifica el mundo. El algoritmo calcula sobre una fotografia en un ejecutor propio, con una sola busqueda a la vez. Las solicitudes cercanas se agrupan. La lectura de estados y la aceptacion de comandos no esperan a que termine la busqueda.

Antes de comprometer un plan se comprueba que no cambio el minuto, el estado fisico ni la version de parametros de la fotografia. Ademas se verifica la factibilidad. Si el plan quedo obsoleto se conserva el itinerario vigente y se solicita otro calculo. Las altas nuevas no invalidan por si solas las cantidades o posiciones de pedidos existentes: se conservan y se consideran en el calculo siguiente sin borrarlas al aplicar un plan anterior.

El presupuesto limita la busqueda del algoritmo, no garantiza el tiempo total de carga de datos, construccion de matriz, serializacion o ejecucion en el servidor del laboratorio.

## WebSocket

Conectar a `/ws/operaciones?contextoId=OPERACION_ID`. La suscripcion exige un contexto existente y no cambia automaticamente cuando se crea otra operacion. Al conectar o reconectar se obtiene una instantanea completa de ese contexto.

Cada mensaje es un `VistaOperacion` con `contextoId`, `revision`, `estado`, `zonaHoraria`, `horaServidor`, `fechaHoraOperativa`, `configuracion`, `instantanea`, `parametros`, `pedidos`, `incidentes`, `avisos`, `calculando`, `planesDescartados` y `error`.

El frontend debe conservar su contexto y descartar revisiones anteriores a la ultima aplicada. Puede resincronizar por `GET /api/operaciones/{id}`. El canal publica aproximadamente cada segundo real, independientemente de los minutos del dominio. Se admiten 32 sesiones y se desconectan sesiones con envios detenidos; los envios ocurren fuera del ciclo operativo.

## Detencion, aislamiento y limites

Detener cierra esa ejecucion. Sus datos permanecen consultables mientras viva el proceso y los comandos pendientes quedan rechazados con una explicacion. Se puede crear otra operacion, que comienza con estado inicial nuevo. No se ofrece reanudacion de una ejecucion detenida.

`CoordinadorEjecucion` mantiene exclusion entre una operacion activa y una corrida de simulacion. El inicio competidor devuelve 409; no cancela implicitamente la ejecucion existente. Al detener o fallar se libera la reserva. La simultaneidad con 5D y la medicion de competencia de CPU siguen siendo una ampliacion separada.

La informacion esta en memoria. Reiniciar Java pierde el estado operativo y la deduplicacion; no hay recuperacion de planes ni checkpoints. Se conservan hasta veinte contextos terminados, ademas de los datos de la ejecucion actual dentro de ese limite. Persistencia y recuperacion siguen siendo una ampliacion separada.

La pantalla diaria existente aun utiliza el recorrido legado `DIA_A_DIA` de `/api/simulaciones`. El punto 2 debe conectarla a este contrato, sustituir el alta no disponible y suscribirse al contexto operativo. Los contratos de 5D y colapso siguen publicados en `/api/simulaciones`.

## Compilacion y verificacion

Desde la raiz del backend:

```sh
./mvnw -o -q verify
java -jar service/target/service-1.0-SNAPSHOT.jar
```

La bateria incluye pruebas de reloj, alta dinamica, crecimiento del registro, deduplicacion, parametros aislados, busqueda en curso, entregas parciales, hora de servicio, averias, mantenimiento, cruce de dia y mes, validacion HTTP y una prueba con Spring Boot y dos conexiones WebSocket reales. Esta ultima requiere abrir un puerto local. Mockito requiere que el entorno permita conectar su agente a la JVM.

La campana completa de aceptacion de ambos escenarios, la medicion de 5D durante 30 a 60 minutos en el servidor objetivo y la integracion del formulario corresponden a los puntos 2 y 3 del reparto del grupo.
