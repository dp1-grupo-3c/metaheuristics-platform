# KindBox - Metaheuristics Platform

Planificador y simulador de rutas de reparto de ultima milla para la empresa PaqRap.

El componente planificador resuelve un problema de ruteo de vehiculos multiatributo que
combina flota heterogenea, ventanas de tiempo duras, multiples almacenes, recarga
intermedia, pausa dentro de la jornada, naturaleza dinamica y reasignacion de carga en
transito. Se implementan **dos metaheuristicas de familias distintas**, que comparten de
forma deliberada el modelo del problema para que la comparacion entre ellas mida mecanismos
de busqueda y no implementaciones distintas del mismo problema.

| Algoritmo | Familia | Referencia |
|---|---|---|
| Busqueda Genetica Hibrida (HGS) | Poblacional | Vidal, Crainic, Gendreau y Prins (2012, 2013, 2014) |
| Busqueda Adaptativa de Vecindad Amplia (ALNS) | Trayectoria | Ropke y Pisinger (2006) |

Ambos consumen los mismos componentes: la matriz de tiempos y distancias, el decodificador
de rutas, la evaluacion en tiempo constante de secuencias de Vidal y otros (2013), la funcion
objetivo jerarquica, el verificador de factibilidad y la heuristica constructiva de ahorros
de Clarke y Wright (1964).

## El problema

- **Ciudad.** Reticula de 70 km por 50 km con nodos cada kilometro, calles de doble sentido,
  sin diagonales.
- **Almacenes.** Uno central de inventario ilimitado y dos intermedios de 1 000 unidades que
  se recargan cada 24 horas.
- **Flota.** 10 autos (24 paquetes, 40 km/h), 15 motos (8 paquetes, 25 km/h) y 12 bicicletas
  (4 paquetes, 12 km/h), con codigos `TTNN`.
- **Jornada.** Turnos de 8 horas con cambios a las 07:00, 15:00 y 23:00, y una hora continua
  de alimentacion separada al menos una hora de cada cambio de turno.
- **Plazos.** 36 horas por defecto y entregas priorizadas de 4, 8, 12 y 18 horas. Las
  ventanas son duras. Se admiten entregas parciales, cada una con una hora de
  acondicionamiento que no cuenta dentro del plazo.
- **Incidencias.** Bloqueos de calles planificados y averias de tres tipos, con reglas
  distintas de reincorporacion.

La funcion objetivo es jerarquica. El nivel dominante minimiza el numero de pedidos sin
asignacion factible dentro de plazo; el subordinado minimiza el costo de operacion, que es la
suma sobre las unidades de la distancia recorrida por el costo por kilometro de su tipo.

## Estructura del proyecto

Proyecto Maven multimodulo sobre **JDK 21**.

| Modulo | Contenido |
|---|---|
| `core` | Modelo del problema, componentes comunes, HGS, ALNS y motor de simulacion. Sin dependencias externas de runtime. |
| `service` | API REST y WebSocket que alimenta el componente visualizador. Spring Boot. |
| `experiments` | Generacion de datos, corridas de escenarios y bancos de medicion. |

Paquetes de `core`, de la capa mas baja a la mas alta:

```
modelo          ciudad, flota, turnos, almacenes, pedidos, bloqueos, averias
io / datagen    lectores de los archivos de entrada y generadores de datos
grafo           registro de bloqueos, caminos minimos y matriz de distancias
problema        fotografia de planificacion, solucion y valor objetivo
evaluacion      decodificador de rutas, objetivo, verificador, secuencias
construccion    heuristica de ahorros de Clarke y Wright
metaheuristica  contrato comun, control de presupuesto y perfil de convergencia
  .hgs          busqueda genetica hibrida
  .alns         busqueda adaptativa de vecindad amplia
simulacion      motor dirigido por eventos y los tres escenarios
```

## Requisitos previos

- **JDK 21** o superior
- **Git**

Maven no hace falta instalarlo: el repositorio incluye el Maven Wrapper.

## Puesta en marcha

```bash
git clone https://github.com/dp1-grupo-3c/metaheuristics-platform.git
cd metaheuristics-platform

# Linux y macOS
./mvnw clean install

# Windows
mvnw.cmd clean install
```

### Generar los juegos de datos

Los archivos de entrada no se versionan porque son voluminosos y reproducibles. Se generan
con un solo comando:

Requiere haber ejecutado antes `./mvnw clean install`, que deja el modulo `core` disponible
para los demas.

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.GenerarDatos \
  -Dexec.args="data 2026-09-01 2026-09-30 ESTABLE"
```

Produce, bajo `data/`:

```
data/
├── flota.txt                          composicion de la flota
├── ventas/            ventasAAAAMM    pedidos, ##d##h##m:x,y,cliente,cantidad,plazo
├── bloqueos/          AAAAMM.bloqueadas
├── mantenimiento/     mant.preventivo.MM.MM
└── prueba/                            juego reducido y determinista
```

Los perfiles de demanda disponibles son `ESTABLE`, `CRECIENTE` y `LIGERO`.

### Ejecutar un escenario

```bash
# Simulacion de cinco dias, modo libre
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.CorrerEscenario \
  -Dexec.args="data 5D 2026-09-01 ALNS 30"

# Hasta el colapso logistico
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.CorrerEscenario \
  -Dexec.args="data COLAPSO 2026-09-01 HGS 30"
```

Argumentos: `<raizDatos> <5D|COLAPSO|DIA> <primerDia> <algoritmo> <salto> [semilla] [duracion]`.
El algoritmo es `HGS` o `ALNS`, o uno de sus alias (`GENETICA`, `VECINDAD`), sin distinguir
mayusculas. La semilla, 20260901 por defecto, fija la corrida entera: llega al algoritmo y de
ella se deriva la de cada replanificacion.

El ultimo argumento decide el factor de aceleracion K y, con el, el presupuesto de cada
llamada al planificador, que es el 60 por ciento de `salto / K`:

| `duracion` | Modo | K | Presupuesto por llamada con salto 30 |
|---|---|---|---|
| ausente o `LIBRE` | libre | 240, la corrida de 30 minutos | 4 500 ms |
| `LIBRE:60` | libre | 120, la corrida de 60 minutos | 9 000 ms |
| `RAPIDO` | libre | 7 200, la corrida de 1 minuto | 150 ms |
| un numero de minutos | acompasado al reloj de pared en 5D | 7 200 / minutos | segun K |

El modo libre corre tan rapido como puede y K solo fija el presupuesto; se aplica igual a los
tres escenarios. El valor por defecto es el del apartado 2.3 del ISA, que situa el presupuesto
efectivo entre 2 y 18 segundos. `RAPIDO` queda para pruebas de humo: sus resultados no miden
la calidad del planificador en operacion. Un numero a secas acompasa la simulacion 5D al reloj
de pared durante esos minutos reales, que es el modo de las presentaciones. Al arrancar, el
ejecutable imprime K, el presupuesto por llamada en milisegundos, el modo de arranque y los
parametros completos del algoritmo, y avisa si el presupuesto cae fuera del rango del ISA.

### Arrancar cada replanificacion desde el plan vigente

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.CorrerEscenario \
  -Dexec.args="data 5D 2026-09-01 ALNS 30" \
  -DarranqueDesdePlanVigente=true
```

Por defecto cada replanificacion construye su solucion de partida con la heuristica de ahorros.
Con `-DarranqueDesdePlanVigente=true` parte del plan vigente de la replanificacion anterior,
recortado a la fotografia: solo los pedidos que siguen pendientes y las unidades que siguen
disponibles, y cada ruta recortada por su cola hasta que vuelve a ser factible. Es el segundo
modo de arranque del apartado 7.3.5 del ISA y la hipotesis experimental del apartado 11.4:
partir del plan vigente deberia rebajar la tasa de reasignacion de pedidos entre
replanificaciones. Vale para `CorrerEscenario`, `MedirEstabilidad` y la API, con el mismo
nombre de propiedad; solo lo aprovecha ALNS, y con HGS el ejecutable avisa de que el indicador
no tiene efecto. Un valor que no sea `true` ni `false` detiene el ejecutable.

### Medir la estabilidad del plan

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.MedirEstabilidad \
  -Dexec.args="data ALNS 30 20260901"
```

Argumentos: `<raizDatos> <algoritmo> <salto> [semilla] [pedidoSeguido] [duracion]`. Corre la
simulacion 5D en modo libre y publica la tasa de reasignacion de pedidos entre
replanificaciones. `pedidoSeguido` imprime la unidad asignada a ese pedido en cada
replanificacion; con `-1` no se sigue ninguno. La duracion admite `RAPIDO`, `LIBRE`,
`LIBRE:<minutos>` o un numero de minutos, que aqui tambien es modo libre; por defecto, 30.

### Comparar los dos algoritmos sobre una misma fotografia

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.BancoDePruebas \
  -Dexec.args="data 2026-09-01 2026-09-07 5700 2000 15000"
```

Argumentos: `<raizDatos> <primerDia> <ultimoDia> <minuto> [msPresupuesto...]`. La semilla de
los dos algoritmos se fija con la propiedad de sistema `semilla`, por ejemplo `-Dsemilla=7`;
por defecto es 20260901.

Publica, para la heuristica constructiva y para cada algoritmo y presupuesto, el numero de
pedidos no atendidos, el costo, los kilometros, las iteraciones, el resultado del verificador
de factibilidad, el perfil de convergencia y, en ALNS, los pesos finales de los operadores de
la capa adaptativa del apartado 7.3.3.

Al final de cada corrida se imprime una conclusion legible. Indica si todos los pedidos fueron
entregados, identifica explicitamente los pendientes o incumplidos y resume el rendimiento de la
ejecucion. En la comparacion tambien se muestra el rendimiento de cada solucion y solo se llama
solucion completa a una que tenga `H=0`; si ninguna cumple, se reporta como mejor aproximacion,
no como resultado operativo valido.

Antes de medir corre una fase de calentamiento de la maquina virtual, que el apartado 13 del
ISA exige: cada algoritmo se ejecuta 500 ms sobre la misma fotografia y su resultado se
descarta, para que el primero de la lista no mida en frio. Se ajusta con
`-DcalentamientoMs=<ms>` y se omite con `-DcalentamientoMs=0`.

### Ajustar los parametros de los algoritmos

Cada parametro de `ParametrosHgs` y de `ParametrosAlns` se ajusta sin recompilar con una
propiedad de sistema `hgs.<parametro>` o `alns.<parametro>`, con el mismo nombre que su
accesor. Vale para los tres ejecutables y para la API. Por ejemplo, para subir el peso del
termino de estabilidad de ALNS y ampliar el rango de destruccion:

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.CorrerEscenario \
  -Dexec.args="data 5D 2026-09-01 ALNS 30" \
  -Dalns.factorPenalizacionEstabilidad=0.5 \
  -Dalns.fraccionMinimaDestruccion=0.10 -Dalns.fraccionMaximaDestruccion=0.50
```

Y para la busqueda genetica hibrida, `-Dhgs.pesoEstabilidad=50 -Dhgs.tamanoGeneracion=30`.
Con `java -cp` las propiedades van antes de la clase:
`java -Dhgs.pesoEstabilidad=50 -cp core/target/classes:experiments/target/classes
org.kindbox.experiments.BancoDePruebas data`.

Los valores usan punto decimal. Una clave con prefijo `hgs.` o `alns.` que no exista, o un
valor mal escrito o fuera de rango, detiene el ejecutable con un mensaje que indica la clave,
el valor y la lista de claves validas, de modo que un error de tipeo no invalida un barrido en
silencio. La lista completa de claves sale de `FabricaAlgoritmos.clavesHgs()` y
`clavesAlns()`, y coincide con la traza `Algoritmo: ...` que cada ejecutable imprime al
arrancar.

Los dos algoritmos pueden acotar cada movimiento con la concatenacion de resumenes de
`DatosSecuencia` antes de llamar al decodificador y descartar lo que la cota ya condena. El
filtro no cambia el resultado, solo lo que cuesta alcanzarlo. En ALNS viene activo, porque
cada posicion de insercion se acota en tiempo constante y ahorra llamadas al decodificador;
se apaga con `-Dalns.filtroCotaInferior=false`. En HGS viene apagado, porque la cota de
kilometros ya descarta casi todos los movimientos de la educacion y la concatenacion cuesta
mas de lo que ahorra; se enciende con `-Dhgs.filtroCotaInferior=true`.

### Diagnostico independiente del conjunto de pedidos

`VerificarFactibilidad` ejecuta una politica de viajes individuales desde el almacen
central, con entregas parciales, distancias Manhattan y retorno para recargar. No ejecuta
HGS ni ALNS. Respeta las velocidades y el tiempo de acondicionamiento configurados; el
ultimo viaje cuenta cuando llega al cliente, sin exigir su retorno antes del plazo.

```bash
./mvnw -pl experiments -am compile
java -cp core/target/classes:experiments/target/classes \
  org.kindbox.experiments.VerificarFactibilidad data 2026-09-01 5
```

`PROGRAMABLE_EN_MODELO_SIMPLIFICADO` significa que esta politica encuentra un programa;
queda pendiente verificar turnos, alimentacion, bloqueos, mantenimiento y averias.
`CAPACIDAD_CENTRAL_INSUFICIENTE` se refiere exclusivamente a viajes desde el central:
no demuestra imposibilidad fisica, pues el modelo completo permite otros almacenes y
posiciones iniciales. `NO_PROGRAMABLE` indica que la politica voraz no encontro viajes
suficientes al compartir la flota. Ninguno de estos resultados certifica ni descarta la
factibilidad del problema completo. Tampoco permite elegir entre HGS y ALNS.

### Levantar la API

```bash
./mvnw -q -pl service spring-boot:run
```

Expone en el puerto 8080 los extremos REST de configuracion, arranque y monitoreo de
simulaciones, registro de averias y cambio en caliente de parametros, y un canal WebSocket en
`/ws/simulacion` que difunde el estado completo de la simulacion en curso.

Toda respuesta de error, la levante el servicio o el contenedor, trae el mismo cuerpo JSON con
`codigo`, `codigoError`, `tipo`, `error`, `mensaje` en espanol, `ruta` e `instante`.
`codigoError` y `tipo` son estables para que el visualizador no tenga que interpretar texto:
`VALIDACION` para entradas inadmisibles, `ESTADO` para operaciones incompatibles con el estado
actual, `RECURSO` para recursos o rutas inexistentes, `DATOS` para fallos de carga y `PROTOCOLO`
para errores HTTP de metodo o contenido. Los codigos son 400 para una peticion mal formada o
una carga que no es multipart, 404 para una corrida o una ruta que no existen, 405 para un
metodo no mapeado en esa ruta, 409 para una operacion que choca con el estado (arrancar con una
corrida ya en curso, o averiar una unidad ya averiada o en mantenimiento), 413 para un archivo
de averias por encima del limite y 415 para un tipo de contenido que el extremo no sabe leer.

Al conectarse al canal, un cliente recibe de inmediato la cabecera `corrida` y la ultima
`instantanea` (o el `resultado` final) de la corrida activa o, si no hay ninguna activa, de la
ultima que se ejecuto. Al arrancar una corrida nueva se descarta lo que quedaba de la anterior,
de modo que nadie recibe como estado del momento la fotografia de una corrida vieja.

## Escenarios

| Escenario | Descripcion |
|---|---|
| Dia a dia | Operacion en tiempo real. |
| Simulacion 5D | Cinco dias simulados, ejecutados entre 30 y 60 minutos de reloj real. |
| Colapso | Corre hasta el primer instante en que un pedido no puede entregarse en plazo. |

Los tres los resuelve el mismo motor y el mismo planificador; lo unico que cambia entre ellos
es la configuracion del reloj y la condicion de parada.

## Pruebas

```bash
./mvnw test
```

Corre las pruebas de los tres modulos: las del nucleo y las del servicio, que cubren los
codigos de error de la API y el estado inicial del canal WebSocket. Para pasar solo las del
nucleo, `./mvnw -pl core test`.

Las pruebas de los dos algoritmos y del motor de simulacion no dependen de `data` ni del
reloj: las fotografias se arman en memoria y las corridas usan
`PresupuestoComputo.deIteraciones`, de modo que el resultado no cambia con la maquina ni con
su carga. Las verificaciones de validez del apartado 12.4 del ISA (factibilidad del plan,
equivalencia del valor objetivo declarado con el recalculado y monotonia del perfil de
convergencia) estan en `ValidezDeAlgoritmosTest`.

`PresupuestoComputo` tiene dos modos. En operacion manda el reloj: cada llamada al
planificador gasta los milisegundos que le concede el factor K, y es el modo de los tres
ejecutables y de la API. El modo por iteraciones corta en un numero exacto de generaciones o
de iteraciones y no lo expone ninguna linea de comandos: existe para que dos corridas con la
misma semilla y el mismo numero devuelvan el mismo plan parada a parada, que es lo que
comprueba `ReproducibilidadTest`. La reproduccion bit a bit esta garantizada sobre la misma
maquina virtual y plataforma.

### Cobertura

```bash
./mvnw verify
```

Deja el informe de JaCoCo en `<modulo>/target/site/jacoco/index.html`, con las variantes
`jacoco.xml` y `jacoco.csv` en el mismo directorio para procesarlo desde un script. No hay
ninguna regla que haga fallar la construccion por cobertura insuficiente.

## Licencia

Proyecto academico del curso 1INF54, Pontificia Universidad Catolica del Peru.

## Visualizador web

La interfaz KindBox vive en [`visualizador/`](visualizador/README.md), fuera del reactor
Maven. Con el servicio encendido, ejecute `npm ci` y `npm run dev` en esa carpeta y abra
http://localhost:5173. Incluye mapa cartesiano interactivo, monitoreo WebSocket, configuración,
pedidos, métricas, averías y reportes. Su README documenta las pruebas contra el servicio,
el despliegue y las funciones del estándar GUI que necesitan ampliar el contrato del backend.
