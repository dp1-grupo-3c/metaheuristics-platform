#!/usr/bin/env sh
# Regenera las imagenes PNG y SVG de los diagramas. Requiere Java y Graphviz (dot).
# Uso: PLANTUML_JAR=/ruta/plantuml.jar ./render.sh
cd "$(dirname "$0")" || exit 1
java -DPLANTUML_LIMIT_SIZE=16384 -jar "${PLANTUML_JAR:-plantuml.jar}" -tpng [0-9]-*.puml
java -DPLANTUML_LIMIT_SIZE=16384 -jar "${PLANTUML_JAR:-plantuml.jar}" -tsvg [0-9]-*.puml
