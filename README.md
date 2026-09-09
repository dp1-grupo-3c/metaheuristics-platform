# Metaheuristics Platform

Plataforma modular para la implementación y experimentación numérica de algoritmos metaheurísticos en Java. Este proyecto está estructurado para separar la lógica algorítmica, la exposición como servicio y la batería de pruebas experimentales.

## Estructura del Proyecto (Multi-módulo Maven)

El proyecto está dividido en 3 módulos independientes para mantener una alta cohesión y bajo acoplamiento:

- **`core`**: Contiene las interfaces y clases base (`Problem`, `Solution`), así como las implementaciones de los algoritmos (GA, PSO, SA) y utilidades numéricas.
- **`service`**: Encargado de la lógica de orquestación y/o exposición de los algoritmos (API REST, fachadas de servicio).
- **`experiments`**: Batería de pruebas, ejecución de benchmarks, generación de reportes CSV y análisis estadístico de resultados.

## Requisitos Previos

Antes de empezar, asegúrate de tener instalado:

- **JDK 21** (LTS)
- **IntelliJ IDEA** (Community o Ultimate)
- **Git** 
- **Maven** (incluido en IntelliJ o instalado globalmente)

## Guía de Configuración para el Equipo (Windows)

Sigue estos pasos para clonar el proyecto y configurar tu entorno de desarrollo.

### 1. Clonar el repositorio

Abre **Git Bash** y ejecuta:

```bash
git clone git@github.com:dp1-grupo-3c/metaheuristics-platform.git
cd metaheuristics-platform
