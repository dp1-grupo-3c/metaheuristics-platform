#!/usr/bin/env bash
# Campana experimental ALNS vs HGS (informe de diseno de experimentos, apartados 6 a 8).
#
#   experiments/campana/correr_campana.sh [paralelos] [presupuestoMs]
#
# 1. Genera la base maestra congelada en data_campana/ si no existe (ESTABLE a 384
#    pedidos/dia, la capacidad diaria de la flota) y registra su hash en el manifiesto.
# 2. Recorre el diseno en orden aleatorio con semilla fija:
#      5D      N1, N2, N3  x  ALNS-C, ALNS-F, HGS  x  replicas 1..10
#      COLAPSO N4          x  ALNS-C, ALNS-F, HGS  x  replicas 1..10
# 3. Cada corrida es una JVM independiente que deja resultados/filas/<id>.csv y
#    resultados/series/<id>.csv. Una corrida ya presente se salta, de modo que la campana
#    se puede interrumpir y reanudar sin repetir nada.
set -euo pipefail
cd "$(dirname "$0")/../.."
PARALELOS=${1:-3}
PRESUPUESTO=${2:-2000}
DATOS=data_campana
SALIDA=${SALIDA:-experiments/campana/resultados}
REPLICAS=${REPLICAS:-10}
mkdir -p "$SALIDA/filas" "$SALIDA/series" "$SALIDA/logs"

if [ ! -f "$DATOS/flota.txt" ]; then
  java -cp core/target/classes:experiments/target/classes org.kindbox.experiments.GenerarDatos \
    "$DATOS" 2026-09-01 2026-10-31 ESTABLE 384
fi

COMMIT=$(git rev-parse --short HEAD)
# Copia congelada de las clases: recompilar durante la campana no la altera (S-06).
CLASES=$(mktemp -d)
cp -r core/target/classes "$CLASES/core"
cp -r experiments/target/classes "$CLASES/exp"
{
  echo "commit=$COMMIT"
  echo "fecha=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "presupuesto_ms=$PRESUPUESTO"
  echo "paralelos=$PARALELOS"
  echo "java=$(java -version 2>&1 | grep -v JAVA_TOOL | head -1)"
  echo "cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs) x $(nproc)"
  echo "memoria=$(grep MemTotal /proc/meminfo | awk '{print $2 " kB"}')"
  echo "datos=GenerarDatos $DATOS 2026-09-01 2026-10-31 ESTABLE 384"
  (cd "$DATOS" && find . -type f -not -path './prueba/*' | sort | xargs sha256sum)
} > "$SALIDA/manifiesto.txt"

TRABAJOS=$(mktemp)
for r in $(seq 1 "$REPLICAS"); do
  for v in ALNS-C ALNS-F HGS; do
    for n in N1 N2 N3; do echo "5D $n $v $r"; done
    echo "COLAPSO N4 $v $r"
  done
done | python3 -c "import random,sys; l=sys.stdin.read().split('\n')[:-1]; random.Random(20260924).shuffle(l); print('\n'.join(l))" > "$TRABAJOS"

correr() {
  read -r esc niv var rep <<< "$1"
  id=$(printf "%s_%s_%s_r%02d" "$esc" "$niv" "$var" "$rep")
  [ -f "$SALIDA/filas/$id.csv" ] && return 0
  java -Dcampana.commit="$COMMIT" -cp "$CLASES/core:$CLASES/exp" \
    org.kindbox.experiments.CorridaCampana datos="$DATOS" salida="$SALIDA" escenario="$esc" \
    nivel="$niv" variante="$var" replica="$rep" presupuestoMs="$PRESUPUESTO" \
    > "$SALIDA/logs/$id.log" 2>&1 || echo "FALLO $id" >&2
  echo "$(date -u +%H:%M:%S) $id"
}
export -f correr
export SALIDA DATOS COMMIT PRESUPUESTO CLASES
xargs -P "$PARALELOS" -I{} bash -c 'correr "$@"' _ {} < "$TRABAJOS"
rm -f "$TRABAJOS"
echo "Campana completa: $(ls "$SALIDA/filas" | wc -l) corridas"
