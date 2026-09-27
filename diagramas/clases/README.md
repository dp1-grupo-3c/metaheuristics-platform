# Diagrama de clases del backend de KindBox

Diagramas de clases UML del backend tal como corre una vez desplegado: el modulo `service`
(API REST y WebSocket sobre Spring Boot) y las partes del modulo `core` que ese servicio usa
en ejecucion. Cada diagrama tiene su fuente PlantUML (`.puml`) y sus imagenes `.png` y `.svg`.

| Diagrama | Contenido |
|---|---|
| [01 - Vista general](01-vista-general.png) | Todos los paquetes y las relaciones principales entre ellos, sin atributos |
| [02 - service](02-service.png) | Controladores, servicio de simulacion, corridas, difusion por WebSocket y configuracion |
| [03 - service.dto y service.error](03-service-dto-errores.png) | Contratos de la API (solicitudes y respuestas) y excepciones |
| [04 - Modelo y datos](04-modelo-datos.png) | `core.modelo`, `core.problema`, `core.io`, `core.grafo` y `core.util` |
| [05 - Simulacion](05-simulacion.png) | Motor dirigido por eventos, estado del mundo, fotografias y metricas |
| [06 - Planificacion](06-planificacion.png) | Contrato `Algoritmo`, presupuesto de computo, evaluacion y heuristica constructiva |
| [07 - HGS](07-hgs.png) | Busqueda Genetica Hibrida |
| [08 - ALNS](08-alns.png) | Busqueda Adaptativa de Vecindad Amplia y sus operadores |

## Alcance

Se omiten las partes que no intervienen en el funcionamiento del sistema desplegado:

- el modulo `experiments` completo (bancos de prueba, comparaciones, corridas de escenarios,
  scripts de graficos, resultados e imagenes de los experimentos);
- el paquete `core.datagen` (generadores de datos de entrada para los experimentos);
- `PruebaFactibilidadIndependiente`, `VerificadorFactibilidad`, `VerificadorRestricciones`,
  `ResultadoVerificacion` e `Infraccion`, que solo usan las pruebas y los experimentos;
- el codigo de pruebas (`src/test`).

En cada diagrama, las clases de otros paquetes aparecen en gris y sin detalle; su detalle
esta en el diagrama que indica el titulo del recuadro. Los atributos que son arreglos de
trabajo internos de los algoritmos se omiten o se resumen.

## Regenerar las imagenes

Hace falta Java, Graphviz (`dot`) y el jar de PlantUML:

```bash
PLANTUML_JAR=/ruta/plantuml.jar ./render.sh
```
