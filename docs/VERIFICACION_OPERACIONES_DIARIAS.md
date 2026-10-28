# Verificacion de operaciones diarias

Fecha: 28 de octubre de 2026. Alcance: backend del punto 1 del plan de operaciones diarias. Base revisada: `3cd04c1`.

## Resultado

`./mvnw -o -q verify` termino con codigo cero. Los informes Surefire contienen 262 pruebas: 224 en `core` y 38 en `service`, sin fallos, errores ni omisiones. Se genero el JAR ejecutable `service/target/service-1.0-SNAPSHOT.jar` y los informes de cobertura de ambos modulos.

La ejecucion se realizo fuera del aislamiento restringido para permitir que Mockito conectara su agente a la JVM y que Spring Boot abriera un puerto local de prueba. La primera ejecucion restringida no pudo iniciar el agente de Mockito; ese resultado no se considero un defecto funcional ni se oculto desactivando pruebas.

## Criterios del plan

| Criterio | Evidencia |
| --- | --- |
| Inicio vacio en hora operativa y espera de pedidos | `MotorOperacionTest.iniciaEnHoraOperativaSinPedidosYEspera` y `entornoNoLeeVentasMalformadasNiExigePedidos` |
| Alta posterior al inicio, una sola aplicacion y convocatoria del planificador | Pruebas de crecimiento, deduplicacion y prioridad de `MotorOperacionTest`; `OperacionHttpTest.altaConsultaIdempotenciaYPaginacionFuncionanPorHttp`; prueba con ALNS real |
| Conservacion y reintento de trabajo, sin duplicar entregas | Prueba de entrega parcial durante 240 minutos con replanificaciones; invariantes de cantidades y particion de metricas; prueba de incumplimiento con remanente visible |
| Continuidad al cambiar de fecha | Pruebas de hora de servicio al cruzar medianoche, extension al cambiar de mes, mantenimiento inicial y consecutivo, averias y bloqueo del mes anterior |
| Plan compatible al terminar una busqueda concurrente | `noPierdeComandosDuranteBusquedaYDescartaElPlanObsoleto` y `nuevasAltasNoImpidenComprometerUnPlanCompatible` |
| Consultas y publicaciones coherentes para varias sesiones | `OperacionWebSocketTest` inicia Spring Boot y abre dos conexiones reales; comprueba el alta, revisiones y reconexion |
| Aislamiento de parametros y mensajes, con politica de simultaneidad | Prueba de parametros propios, dos pruebas de reserva frente a simulaciones, rechazo HTTP 409 y suscripcion estable al contexto anterior al crear otro |
| Contrato comprobado para el punto 2 | `OperacionHttpTest` cubre inicio, alta, validacion, consulta de comandos, paginacion, metricas, averias, parametros, detencion y conflictos; ejemplos en `OPERACIONES_DIARIAS.md` |
| Regresion de simulacion | Bateria completa existente y dos pruebas controladas de 5D y colapso usando el motor compartido |

## Pruebas nuevas

| Clase | Pruebas |
| --- | --- |
| `core.operacion.MotorOperacionTest` | 22 |
| `core.operacion.RegresionEscenariosCompartidosTest` | 2 |
| `service.operacion.OperacionHttpTest` | 8 |
| `service.operacion.OperacionWebSocketTest` | 1 |
| `service.web.ManejadorWebSocketOperacionTest` | 2 |

Las dos ultimas clases verifican tanto conexiones reales como la desconexion de un cliente lento sin bloquear a otro cliente. El nucleo usa un reloj controlado para comprobar horas y dias sin esperar ese tiempo de pared.

## Comprobacion con datos publicados

Se inicio `MotorOperacion` con el reloj real y `RepositorioDatos` apuntando a `data_dp1`. El resultado fue `EN_CURSO`, con 37 unidades, cero pedidos iniciales, un bloqueo vigente y dos avisos de carga. Se cerro la operacion al terminar. La comprobacion confirma que el entorno publicado puede cargarse sin importar ventas historicas; no equivale a ejecutar una jornada completa con demanda real.

## Limites de esta verificacion

La hora de servicio se comprobo en casos controlados, incluidas entregas parciales. No sustituye la campana completa de aceptacion de operaciones diarias y 5D del punto 3.

La regresion de 5D comprueba 7200 minutos de horizonte, entregas y metricas en modo libre de prueba. No mide una corrida de 30 a 60 minutos en el servidor del laboratorio.

El frontend existente conserva el recorrido legado hasta la integracion del punto 2. La persistencia y recuperacion tras reinicio, y la ejecucion simultanea de operacion y simulacion, son ampliaciones separadas del alcance base implementado.
