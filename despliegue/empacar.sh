#!/usr/bin/env bash
set -euo pipefail
raiz=$(cd "$(dirname "$0")/.." && pwd)
datos=$(realpath "${1:?Indique el directorio de datos preparados}")
salida=$(realpath -m "${2:-$raiz/dist}")
cd "$raiz"
[[ -z "$(git status --porcelain)" ]] || { echo 'Confirme los cambios antes de empaquetar una version.'; exit 1; }
[[ -d "$datos/ventas" && -d "$datos/bloqueos" && -d "$datos/mantenimiento" ]]
./mvnw -q verify
(cd visualizador && npm ci && npm test && npm run build)
version=$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD)
paquete=$(mktemp -d)
trap 'rm -rf "$paquete"' EXIT
mkdir -p "$paquete/kindbox" "$salida"
cp service/target/service-1.0-SNAPSHOT.jar "$paquete/kindbox/servicio.jar"
cp -R visualizador/out "$paquete/kindbox/visualizador"
cp -R "$datos" "$paquete/kindbox/datos"
cp -R despliegue "$paquete/kindbox/despliegue"
printf '%s\n' "$version" > "$paquete/kindbox/version"
(cd "$paquete/kindbox" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
tar -czf "$salida/kindbox-$version.tar.gz" -C "$paquete" kindbox
(cd "$salida" && sha256sum "kindbox-$version.tar.gz" > "kindbox-$version.tar.gz.sha256")
printf 'Paquete: %s/kindbox-%s.tar.gz\n' "$salida" "$version"
