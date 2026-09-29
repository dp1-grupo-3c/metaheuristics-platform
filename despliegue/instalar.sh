#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Ejecute con sudo desde el paquete extraido.'; exit 1; }
paquete=$(cd "$(dirname "$0")/.." && pwd)
cd "$paquete"
for herramienta in java nginx openssl curl systemctl sha256sum; do
    command -v "$herramienta" >/dev/null || { echo "Falta $herramienta; validar su instalacion con el profesor."; exit 1; }
done
java -version 2>&1 | head -1 | grep -Eq 'version "(21|2[2-9]|[3-9][0-9])([."])' || { echo 'Se requiere OpenJDK 21 o superior.'; exit 1; }
sha256sum --quiet -c SHA256SUMS
version=$(cat version)
[[ "$version" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]+$ ]] || exit 1
nginx -t
id kindbox >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin kindbox
install -d -m 755 /opt/kindbox/releases /etc/kindbox
if [[ ! -f /etc/kindbox/usuarios ]]; then
    read -r -p 'Usuario web del equipo: ' usuario
    [[ "$usuario" =~ ^[a-zA-Z0-9._-]+$ ]] || exit 1
    read -r -s -p 'Nueva clave web (distinta de SSH): ' clave
    printf '\n'
    [[ ${#clave} -ge 12 ]] || { echo 'Use al menos 12 caracteres.'; exit 1; }
    resumen=$(printf '%s' "$clave" | openssl passwd -6 -stdin)
    unset clave
    printf '%s:%s\n' "$usuario" "$resumen" > /etc/kindbox/usuarios
    chown root:www-data /etc/kindbox/usuarios
    chmod 640 /etc/kindbox/usuarios
fi
if [[ ! -f /etc/kindbox/certificado.pem ]]; then
    openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
        -keyout /etc/kindbox/clave.pem -out /etc/kindbox/certificado.pem \
        -subj '/CN=1inf54-983-3c.inf.pucp.edu.pe' \
        -addext 'subjectAltName=DNS:1inf54-983-3c.inf.pucp.edu.pe,IP:200.16.7.160'
    chmod 600 /etc/kindbox/clave.pem
fi
[[ ! -e "/opt/kindbox/releases/$version" ]] || { echo 'Esta version ya existe.'; exit 1; }
cp -R "$paquete" "/opt/kindbox/releases/$version"
chown -R root:root "/opt/kindbox/releases/$version"
chmod -R a+rX "/opt/kindbox/releases/$version"
if [[ ! -f /etc/kindbox/entorno ]]; then
    cat > /etc/kindbox/entorno <<'CONFIGURACION'
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
KINDBOX_DIRECTORIO_DATOS=/opt/kindbox/actual/datos
KINDBOX_CORRIDAS_EN_MEMORIA=3
SERVER_TOMCAT_THREADS_MAX=24
CONFIGURACION
fi
anterior=$(readlink /opt/kindbox/actual || true)
respaldo=$(mktemp -d)
for archivo in /etc/nginx/sites-enabled/kindbox /etc/systemd/system/kindbox.service; do
    [[ ! -e "$archivo" ]] || cp -L "$archivo" "$respaldo/$(basename "$archivo")"
done
restaurar() {
    echo 'Fallo de instalacion: restaurando configuracion anterior.'
    systemctl stop kindbox || true
    for archivo in /etc/nginx/sites-enabled/kindbox /etc/systemd/system/kindbox.service; do
        if [[ -f "$respaldo/$(basename "$archivo")" ]]; then cp "$respaldo/$(basename "$archivo")" "$archivo"; else rm -f "$archivo"; fi
    done
    if [[ -n "$anterior" ]]; then ln -sfn "$anterior" /opt/kindbox/actual; fi
    systemctl daemon-reload
    if [[ -n "$anterior" ]]; then systemctl start kindbox || true; fi
    nginx -t && systemctl reload nginx || true
}
trap restaurar ERR
ln -sfn "/opt/kindbox/releases/$version" /opt/kindbox/actual
install -m 644 despliegue/kindbox.service /etc/systemd/system/kindbox.service
install -m 644 despliegue/nginx.conf /etc/nginx/sites-enabled/kindbox
nginx -t
systemctl daemon-reload
systemctl restart kindbox
salud=0
for intento in {1..45}; do
    if curl --fail --silent http://127.0.0.1:8080/api/salud >/dev/null; then salud=1; break; fi
    sleep 1
done
[[ $salud -eq 1 ]]
systemctl reload nginx
systemctl enable kindbox
trap - ERR
rm -rf "$respaldo"
echo 'KindBox instalado. Abra https://1inf54-983-3c.inf.pucp.edu.pe e identifiquese con el usuario web.'
echo 'Certificado autofirmado: verifique su huella al confiar en el certificado.'
openssl x509 -in /etc/kindbox/certificado.pem -noout -fingerprint -sha256
