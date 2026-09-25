# ¿Por qué el prototipo del grupo 6 colapsa el día 11 y KindBox el día 2?

Rama `feature/prueba_grupoN6`. Se tomaron el código y los datos del repositorio
`DP1-G6F-Prototipo` y se ejecutaron en el mismo simulador, con el mismo decodificador de rutas y
la misma verificación que KindBox. El objetivo era separar **qué parte de la diferencia viene de
los datos, qué parte de las reglas de simulación y qué parte del algoritmo**.

## Respuesta corta

Las dos cifras no miden lo mismo. La diferencia se explica por tres factores, en este orden de
importancia:

1. **Los datos son otros.** El colapso del grupo 6 en el día 11,8 ocurre con sus ventas de
   enero de 2026: 641 pedidos en el mes, **≈ 21 pedidos/día (≈ 111 paquetes/día, el 7 % de la
   capacidad de la flota)**. El colapso de KindBox en el día ~2–3 ocurre en nuestra rampa N4, que
   arranca en 188 pedidos/día y sube hasta 377 (**del 50 % al 100 % de la capacidad**). Es entre
   9 y 18 veces más carga diaria.
2. **KindBox tenía un defecto que el grupo 6 no tiene: la pausa de alimentación se volvía a
   insertar en cada replanificación.** Con sus mismos datos, nuestro motor colapsaba el **día
   3,8**, antes que ellos. Con su regla de alimentación implementada en nuestra base, nuestro ALNS
   **no colapsa en todo el mes** (más de 31 días, frente a sus 11,8).
3. **Su objetivo maximiza la holgura (entregar lo antes posible) y no mira el costo.** Eso
   reduce los incumplidos cuando la carga es alta, pero cuesta entre un 45 % y un 70 % más por
   pedido entregado.

Además, **su "colapso" se define de otra manera**. Para ellos es `COLAPSO_PLANIFICACION`: un
ciclo en que el planificador no encuentra plan completo. Para nosotros es un pedido que vence
sin entregarse. Su colapso del 12 de enero a las 19:40 es un único pedido (`70,08`, plazo 4 h,
registrado a las 19:40), el mismo en todas sus semillas y en sus dos algoritmos. No mide la
capacidad de la flota.

## Lo que se implementó en la rama

| Pieza | Qué hace |
|---|---|
| `experiments/grupo6/importar_datos_g6.sh` | Lleva sus ventas y bloqueos al formato de KindBox. El contenido no cambia; solo cambia la escritura de los bloqueos: ellos escriben las poligonales por sus vértices y nosotros por nodo de 1 km, así que se expanden los tramos rectos (0 tramos diagonales en 21 725 bloqueos). La flota y los almacenes son idénticos en los dos repositorios. |
| **Crédito de alimentación** (`MotorSimulacion`, `UnidadEnCurso`, `InstanciaPlanificacion`, `ProgramadorRuta`) | Su regla: una pausa cumplida, o 60 minutos sin ruta dentro de la ventana de alimentación, cuentan como la pausa del turno, y el decodificador no inserta otra. Se desactiva con `-Dkindbox.creditoAlimentacion=false`. |
| **`BusquedaTabu`** (`core/.../metaheuristica/tabu`) | Port de su `TabuSearchPlanner` sobre nuestro `ProgramadorRuta`: vecindarios de asignación y de ruteo alternados, tabú de movimientos inversos (tenencia 7), aspiración, diversificación de 1/8 de las partes y solución inicial por plazo. Con `-Dts.objetivo=HOLGURA` usa su objetivo (paquetes pendientes y luego mayor holgura media); con `JERARQUICO`, el nuestro (H, urgencia, costo). |
| `experiments/grupo6/comparar_logica.sh` | El experimento factorial de este informe. |

## El defecto de la alimentación, paso a paso

Pedido 51 de sus datos: plazo de 4 h, registrado el día 4 a las 15:44, límite a las 19:40.
Lo tenía asignado la moto TM14 desde el primer ciclo. La traza del plan en cada
replanificación muestra lo siguiente:

```
t=5280  P@5281-5341  E51 llega 5380      <- almuerzo, luego la entrega
t=5310  P@5341-5401  E51 llega 5440      <- otro almuerzo
t=5370  P@5401-5461  E51 llega 5500      <- un tercer almuerzo; llega justo en el límite
t=5490  E51 ya no llega a tiempo (5502 > 5500): pedido incumplido, colapso
```

`ProgramadorRuta.ubicarPausa` inserta la pausa en toda ruta donde la holgura la admita, y no sabía
que la unidad ya había almorzado. Con poca demanda las unidades tienen mucha holgura, así que
cada replanificación les vuelve a dar un almuerzo antes de salir. El simulador del grupo 6
acredita la pausa por turno y no tiene este problema.

## Resultados del experimento factorial

150 ms por llamada, 3 réplicas por celda, solo bloqueos, sin averías ni mantenimiento. 48
corridas; 0 planes inválidos y 0 llamadas fuera de presupuesto. Medianas sobre las 3 réplicas.

### Con los datos del grupo 6 (enero de 2026, hasta el colapso o 31 días)

| Variante | Sin crédito de alimentación | Con crédito de alimentación |
|---|---|---|
| ALNS (arranque cálido) | colapso día 3,8 (3/3) | **sin colapso en 31 días (3/3)**, 638 de 641 entregados |
| HGS | colapso día 3,8 (3/3) | 1/3 sin colapso; mediana del colapso, día 21,0 |
| TS con su objetivo (holgura) | **sin colapso en 31 días (3/3)** | sin colapso en 31 días (3/3) |
| TS con nuestro objetivo (H, U, S) | colapso día 3,8 (3/3) | colapso día 5,9 (3/3) |
| *Referencia: su TS y su ALNS en su prototipo* | *colapso día 11,8 (todas las semillas)* | |

Costo por pedido entregado con crédito: ALNS 198 S/, HGS 192 S/, TS‑holgura 289 S/.

### Con nuestros datos (con crédito de alimentación)

| Variante | N3, 5 días: incumplidos (total de 3 réplicas) | N3: costo por pedido | N4: día de colapso (mediana) |
|---|---|---|---|
| ALNS (arranque cálido) | 15 | **85,6 S/** | 2,95 |
| HGS | 18 | 100,8 S/ | 0,58 |
| TS con su objetivo (holgura) | 6 | 146,1 S/ | 2,95 |
| TS con nuestro objetivo | **3** | 107,6 S/ | **7,34** |

## Lectura

1. **Con los mismos datos, el motor de KindBox corregido dura más que el prototipo del grupo 6**:
   más de 31 días frente a 11,8 con nuestro ALNS. La cifra "día 11 contra día 2" no compara
   algoritmos. Compara una demanda del 7 % de la capacidad con una del 50–100 %, y además un
   motor con el defecto de los almuerzos repetidos con otro sin él.
2. **La regla de alimentación era el defecto dominante a baja demanda.** Por sí sola lleva a
   ALNS del día 3,8 a más de 31 días. A alta demanda pesa menos: en una prueba aparte con 3 réplicas de ALNS en N3,
   los incumplidos bajaron de 16 a 12 (con 150 ms y 3 réplicas, la variación entre corridas es de ese mismo orden), porque con carga alta las unidades casi nunca están ociosas.
3. **Maximizar la holgura hace robusta la búsqueda frente a ese defecto.** Entrega pronto,
   antes de que se acumulen las pausas, y reduce los incumplidos a alta carga (6 contra 15 de
   ALNS en N3). A cambio encarece la operación: +71 % por pedido en N3 frente a ALNS.
4. **La búsqueda tabú con nuestro objetivo** es la variante más resistente en nuestros datos
   (7,3 días en N4, 3 incumplidos en N3) con un costo intermedio. En sus datos, en cambio,
   colapsa el día 5,9. Con 3 réplicas a 150 ms estas diferencias son indicativas y hay que
   confirmarlas con 2 s y 10 réplicas antes de decidir nada.

## Recomendaciones para KindBox

- **Integrar el crédito de alimentación en la rama principal.** Es la corrección de un defecto,
  no una preferencia de diseño, y al volver a correr la campaña cambiará los resultados del
  informe ALNS contra HGS.
- **Evaluar un término de holgura en el objetivo común**, entre la urgencia y el costo, o como
  desempate. Sería una versión intermedia entre "solo costo" (nosotros) y "solo holgura"
  (grupo 6).
- **Confirmar con presupuesto real** (2 s, 10 réplicas) las diferencias entre ALNS, HGS y TS
  con nuestro objetivo, antes de sumar TS a la comparación oficial.
- **Al comparar con otros grupos, usar sus mismos datos y la misma definición de colapso.**
  Tal como están, "día 11" y "día 2" no son comparables.

## Reproducir

```bash
./mvnw -q -DskipTests install
experiments/grupo6/importar_datos_g6.sh DP1-G6F-Prototipo-main.zip     # crea data_g6/
experiments/campana/correr_campana.sh 3 2000                            # solo si falta data_campana/
experiments/grupo6/comparar_logica.sh 150 3 4                           # 48 corridas, ~15 min
```

Los resultados están en `experiments/grupo6/resultados/`: una fila por corrida en `filas/` y la
tabla consolidada en `resumen.csv`.
