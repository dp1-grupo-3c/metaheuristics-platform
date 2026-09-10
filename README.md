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

Con un septimo argumento, la corrida se acompasa al reloj de pared y dura esos minutos
reales, que es el modo de las presentaciones.

### Comparar los dos algoritmos sobre una misma fotografia

```bash
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.BancoDePruebas \
  -Dexec.args="data 2026-09-01 2026-09-07 5700 2000 15000"
```

Publica, para la heuristica constructiva y para cada algoritmo y presupuesto, el numero de
pedidos no atendidos, el costo, los kilometros, las iteraciones, el resultado del verificador
de factibilidad y el perfil de convergencia.

### Levantar la API

```bash
./mvnw -q -pl service spring-boot:run
```

Expone en el puerto 8080 los extremos REST de configuracion, arranque y monitoreo de
simulaciones, registro de averias y cambio en caliente de parametros, y un canal WebSocket en
`/ws/simulacion` que difunde el estado completo de la simulacion en curso.

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
./mvnw -pl core test
```

## Licencia

Proyecto academico del curso 1INF54, Pontificia Universidad Catolica del Peru.
