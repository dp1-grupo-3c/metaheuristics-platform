# Mejora de cumplimiento

Base: main `babdc19`. Rama evaluada: `origin/claude/bold-albattani-rq4506`, punta `530aa17`.

## Criterios

Prioridad: disminuir incumplidos y retrasar el primer incumplimiento; despues comparar
pendientes dentro de plazo, costo, kilometros y presupuesto. Completar el horizonte 5D
no equivale a satisfacer la politica de entrega. H.3 exige ausencia de asignacion factible;
el detector actual espera al vencimiento. No se modifica ese detector para ocultar fallos.

## Revision de la rama

Se adaptan los cambios de `core`, sin integrar su campaña ni sus conclusiones como resultados
propios. La rama aporta urgencia H/U/S, reserva de cierre, arranque vigente para ALNS y una
correccion de redondeo en redireccion. Sus propios resultados todavia incluyen pedidos que
estuvieron asignados y luego quedaron fuera del plan.

Problemas encontrados al revisar:

- El comparador de urgencia con tolerancia no garantiza transitividad. Se compara por celdas
  de resolucion fija; asi tambien se absorbe el error de suma incremental de ALNS.
- La cota de HGS omitia la urgencia de los pedidos en rutas infactibles.
- El compromiso de ALNS comparaba cantidades, permitiendo intercambiar identidades. Se
  protege cada pedido urgente que el mejor plan conserva.
- HGS no admitia un plan vigente. Se reprograma con la fotografia actual, se reparan visitas
  que ya no caben y se conserva como respaldo factible e individuo de la poblacion. La
  poblacion, cruce y educacion siguen siendo HGS; no se ejecuta ALNS dentro de HGS.
- La pausa ya iniciada no se transmitia a la fotografia. Una replanificacion podia insertar
  otra pausa en la misma jornada. Se conserva el turno de la pausa y el servicio en curso.
- La urgencia ignoraba la ultima hora del turno. Se usa min(plazo, finTurno(plazo) − servicio)
  para priorizar; el plazo contractual y el verificador no se relajan.

## Plan experimental

1. Pruebas unitarias de factibilidad, monotonía, pausa por jornada, plazo efectivo y
   recuperacion de planes. Contratos REST sin modificaciones.
2. Diagnostico exploratorio con 200 ms por llamada (fuera del rango operativo), semilla
   20260924 y septiembre 1–5. Comparar rama original y adaptacion.
3. Validacion operativa con 2000 ms por llamada, 5D, semilla 20260924, mismos 795 pedidos,
   98 bloqueos y 37 unidades que la base anterior. Ejecucion secuencial, sin incidencias.
4. Sensibilidad con otras semillas y otro horizonte de la data publicada; separar el
   presupuesto corto del operativo. Ablacion del arranque vigente.
5. Verificar el reactor Maven completo y publicar resultados, incluidos fallos y limites.

## Datos y controles

Origen: `c.1inf54.26-2.data_publicada-20260922T020815Z-1-001`.
Las ventas se copian sin cambiar sus bytes. Los bloqueos se expanden a segmentos unitarios,
sin cambiar ventanas ni geometria. Flota del caso: 10 autos, 15 motos y 12 bicicletas.
Datos temporales fuera del repositorio. Se conservan capacidades, velocidades, costos,
abastecimientos, turnos y bloqueos. El modo libre elimina la espera de presentacion, no
el presupuesto del algoritmo. Una semilla con limite de reloj no garantiza planes identicos.

La correccion de pausas evita repetirlas; no certifica por si sola que todos los conductores
cumplan exactamente una pausa en cada jornada. El modelo existente permite omitir una pausa
cuando ninguna posicion cabe. La auditoria de cumplimiento de jornada completa sigue siendo
una limitacion separada y no se oculta tras la factibilidad de una ruta individual.

## Reproduccion

```bash
python3 experiments/revision-cumplimiento-20260924/preparar_datos.py \
  ../c.1inf54.26-2.data_publicada-20260922T020815Z-1-001 /tmp/kindbox-datos-publicados
./mvnw -q -DskipTests install
./mvnw -q -pl experiments exec:java \
  -Dexec.mainClass=org.kindbox.experiments.CompararCumplimiento \
  -Dexec.args="/tmp/kindbox-datos-publicados HGS 2026-09-01 5 2000 20260924 false true"
```

Sustituir HGS por ALNS para la segunda corrida. El penultimo argumento habilita incidencias
(averias generadas y mantenimientos presentes en el directorio); el ultimo controla el
arranque vigente. El preparador no copia mantenimientos, porque esta comparacion los excluye.
El CSV va a la salida estandar y el progreso a la salida de errores. El arnes aborta ante un
plan infactible o un objetivo inconsistente. Con limite de reloj, repetir la semilla no
garantiza la misma secuencia de iteraciones ni el mismo resultado.
