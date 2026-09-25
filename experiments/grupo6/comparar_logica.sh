#!/usr/bin/env bash
# Experimento factorial que separa por que el prototipo del grupo 6 colapsa mas tarde que KindBox.
#
#   experiments/grupo6/comparar_logica.sh [presupuestoMs=150] [replicas=3] [paralelos=4]
#
# Factores:
#   datos      G6 (sus ventas y bloqueos, desde 2026-01-01, hasta el colapso o 31 dias)
#              KB-N3 (nuestra base maestra al 75 %, 5 dias) y KB-N4 (nuestra rampa de colapso)
#   credito    regla de alimentacion del grupo 6 (-Dkindbox.creditoAlimentacion=true|false)
#   variante   ALNS-C, HGS, TS-HOLGURA (busqueda tabu portada, con su objetivo) y
#              TS-JERARQUICO (la misma busqueda con nuestro objetivo H, U, S)
# Solo bloqueos; sin averias ni mantenimiento. Requiere data_g6 (importar_datos_g6.sh) y
# data_campana (experiments/campana/correr_campana.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."
PRESUPUESTO=${1:-150}; REPLICAS=${2:-3}; PARALELOS=${3:-4}
SALIDA=${SALIDA:-experiments/grupo6/resultados}
mkdir -p "$SALIDA/logs"
CLASES=$(mktemp -d); cp -r core/target/classes "$CLASES/core"; cp -r experiments/target/classes "$CLASES/exp"
export CLASES SALIDA PRESUPUESTO
trabajos() {
  for r in $(seq 1 "$REPLICAS"); do
    for cred in false true; do
      for v in ALNS-C HGS TS-HOLGURA TS-JERARQUICO; do
        echo "G6 $v $cred $r"
        [ "$cred" = true ] && echo "KB-N3 $v $cred $r" && echo "KB-N4 $v $cred $r"
      done
    done
  done
}
correr() {
  read -r datos var cred rep <<< "$1"
  base=${var%%-HOLGURA}; base=${base%%-JERARQUICO}
  obj=HOLGURA; [ "$var" = TS-JERARQUICO ] && obj=JERARQUICO
  etiqueta="_${datos}_${var}_cred${cred}"
  case $datos in
    G6)    args="datos=data_g6 escenario=COLAPSO nivel=COMPLETO primerDia=2026-01-01 dias=31" ;;
    KB-N3) args="datos=data_campana escenario=5D nivel=N3" ;;
    KB-N4) args="datos=data_campana escenario=COLAPSO nivel=N4" ;;
  esac
  java -Dkindbox.creditoAlimentacion="$cred" -Dts.objetivo="$obj" -cp "$CLASES/core:$CLASES/exp" \
    org.kindbox.experiments.CorridaCampana $args salida="$SALIDA" variante="$base" replica="$rep" \
    presupuestoMs="$PRESUPUESTO" etiqueta="$etiqueta" > "$SALIDA/logs/${datos}_${var}_${cred}_r${rep}.log" 2>&1 \
    || echo "FALLO $1" >&2
  echo "$(date -u +%H:%M:%S) $1"
}
export -f correr
trabajos | xargs -P "$PARALELOS" -I{} bash -c 'correr "$@"' _ {}
echo "Listo: $(ls "$SALIDA/filas" | wc -l) corridas"
