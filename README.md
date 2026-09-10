# KindBox — Metaheuristics Platform

Planificador y simulador de rutas de reparto para la empresa **PaqRap**.
Proyecto del curso **1INF54 — Proyecto de Diseño y Desarrollo de Software**, semestre 2026-2,
grupo 3C, Pontificia Universidad Católica del Perú.

El componente planificador resuelve un problema de ruteo de vehículos multiatributo que
combina flota heterogénea, ventanas de tiempo duras, múltiples almacenes, recarga
intermedia, pausa dentro de la jornada, naturaleza dinámica y reasignación de carga en
tránsito. Conforme al requisito no funcional (a) del enunciado se implementan **dos
metaheurísticas de familias distintas**, seleccionadas y sustentadas en el informe ISA:

| Algoritmo | Familia | Referencia |
|---|---|---|
| Búsqueda Genética Híbrida (HGS) | Poblacional | Vidal y otros (2012, 2013, 2014) |
| Búsqueda Adaptativa de Vecindad Amplia (ALNS) | Trayectoria | Ropke y Pisinger (2006) |

Ambos comparten de forma deliberada el modelo del problema, la función objetivo, el
verificador de factibilidad, la evaluación en tiempo constante de secuencias y la
heurística constructiva de Clarke y Wright. Esa comunidad es la condición de validez del
experimento numérico (apartado 12 del ISA).

## Documentación

| Documento | Contenido |
|---|---|
| [`docs/CONTEXTO-DOMINIO.md`](docs/CONTEXTO-DOMINIO.md) | Reglas de negocio consolidadas del enunciado, el cuestionario y la hoja de flota. Referencia única. |
| [`docs/PLAN-DESARROLLO.md`](docs/PLAN-DESARROLLO.md) | Etapas del desarrollo y decisiones de diseño propias. |

## Estructura del proyecto

Proyecto Maven multi-módulo sobre **JDK 21**.

| Módulo | Contenido |
|---|---|
| `core` | Modelo del problema, componentes comunes, HGS, ALNS y motor de simulación. Sin dependencias externas de runtime. |
| `service` | API REST y WebSocket que alimenta el componente visualizador. Spring Boot. |
| `experiments` | Generación de datos, corridas de experimentación y análisis estadístico. |

## Requisitos previos

- **JDK 21** (LTS)
- **Git**
- Maven **no** hace falta instalarlo: el repositorio incluye el *Maven Wrapper*.

## Puesta en marcha

```bash
git clone git@github.com:dp1-grupo-3c/metaheuristics-platform.git
cd metaheuristics-platform

# Linux / macOS
./mvnw clean install

# Windows
mvnw.cmd clean install
```

### Generar los juegos de datos

El equipo docente entrega parte de los archivos; el resto debe generarlo el equipo para
cubrir del 01/01/2026 al 31/12/2028.

```bash
./mvnw -q -pl experiments exec:java -Dexec.mainClass=org.kindbox.experiments.GenerarDatos
```

Produce, bajo `data/`:

```
data/
├── flota.txt
├── ventas/            ventasAAAAMM
├── bloqueos/          AAAAMM.bloqueadas
├── mantenimiento/     mant.preventivo.MM.MM
└── prueba/            juego mínimo determinista para pruebas
```

## Escenarios

| Escenario | Descripción |
|---|---|
| Día a día | Operación en tiempo real. |
| Simulación 5D | Cinco días simulados, ejecutados entre 30 y 60 minutos de reloj real. |
| Colapso | Corre hasta el primer instante en que un pedido no puede entregarse en plazo. |

## Equipo

Regina Valeria Sanchez Boza · Mayerli Dina López Carhuapuma · Marlow Brando Ariza Mejia ·
Romina Fernanda Valdivia Acosta

Profesor: Fernández Sanchez, Juan Carlos
