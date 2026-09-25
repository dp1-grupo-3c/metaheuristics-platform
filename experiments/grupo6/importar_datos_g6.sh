#!/usr/bin/env bash
# Importa el juego de datos del grupo 6 (DP1-G6F-Prototipo) al formato de este repositorio,
# sin tocar el contenido de los archivos: solo cambia nombres y carpetas.
#
#   experiments/grupo6/importar_datos_g6.sh <DP1-G6F-Prototipo-main.zip | carpeta> [destino=data_g6]
#
#   algoritmos/alns/data/ventas.v20260909/ventas.AAAAMM.txt   -> destino/ventas/ventasAAAAMM
#   algoritmos/alns/data/bloqueos.v20260909/bloqueo.AAMM.txt  -> destino/bloqueos/20AAMM.bloqueadas
#
# La flota (10 autos, 15 motos y 12 bicicletas) y los almacenes (central 27,14; Nor-Oeste 12,38;
# Este 57,27) son los mismos en ambos repositorios, de modo que se reutiliza nuestra flota.txt.
# No se importa el mantenimiento: sus campanas lo excluyen, igual que la nuestra.
set -euo pipefail
cd "$(dirname "$0")/../.."
ORIGEN=${1:?Indica el zip o la carpeta del repositorio del grupo 6}
DESTINO=${2:-data_g6}
if [ -f "$ORIGEN" ]; then
  TMP=$(mktemp -d)
  unzip -q "$ORIGEN" -d "$TMP"
  ORIGEN=$(find "$TMP" -maxdepth 1 -mindepth 1 -type d | head -1)
fi
DATOS="$ORIGEN/algoritmos/alns/data"
mkdir -p "$DESTINO/ventas" "$DESTINO/bloqueos"
for f in "$DATOS"/ventas.v20260909/ventas.*.txt; do
  m=$(basename "$f" .txt); m=${m#ventas.}
  cp "$f" "$DESTINO/ventas/ventas$m"
done
# Sus poligonales se escriben por vertices (tramos rectos de varios km); nuestro lector exige un
# nodo por km. Cada tramo, siempre horizontal o vertical, se expande en sus nodos intermedios:
# la geometria bloqueada es exactamente la misma.
for f in "$DATOS"/bloqueos.v20260909/bloqueo.*.txt; do
  m=$(basename "$f" .txt); m=${m#bloqueo.}
  python3 - "$f" "$DESTINO/bloqueos/20$m.bloqueadas" <<'PY'
import sys
entrada, salida = sys.argv[1], sys.argv[2]
with open(entrada) as fin, open(salida, "w") as fout:
    for linea in fin:
        linea = linea.strip()
        if not linea:
            continue
        tiempo, coords = linea.split(":")
        c = [int(v) for v in coords.split(",")]
        vertices = list(zip(c[::2], c[1::2]))
        nodos = [vertices[0]]
        for (x0, y0), (x1, y1) in zip(vertices, vertices[1:]):
            if x0 != x1 and y0 != y1:
                raise SystemExit(f"Tramo diagonal en {entrada}: {linea}")
            pasos = max(abs(x1 - x0), abs(y1 - y0))
            for k in range(1, pasos + 1):
                nodos.append((x0 + k * ((x1 > x0) - (x1 < x0)),
                              y0 + k * ((y1 > y0) - (y1 < y0))))
        fout.write(tiempo + ":" + ",".join(f"{x},{y}" for x, y in nodos) + "\n")
PY
done
cp data_campana/flota.txt "$DESTINO/flota.txt" 2>/dev/null || \
  printf 'TA,10,24,40.0,8.00\nTM,15,8,25.0,6.00\nTB,12,4,12.0,3.00\n' > "$DESTINO/flota.txt"
echo "Importados: $(ls "$DESTINO/ventas" | wc -l) meses de ventas y $(ls "$DESTINO/bloqueos" | wc -l) de bloqueos en $DESTINO"
(cd "$DESTINO" && find . -type f | sort | xargs sha256sum) > "$DESTINO/hashes.txt"
