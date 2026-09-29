# Despliegue académico de KindBox

Destino: `1inf54-983-3c.inf.pucp.edu.pe` (`200.16.7.160`), Ubuntu 24.04,
2 vCPU y 2 GB. Se conserva el repositorio multimódulo: API y visualizador se
versionan y verifican juntos. No se requiere un repositorio adicional.

Nginx sirve la exportación estática de Next.js por HTTPS, con certificado
autofirmado, y reenvía `/api/` y `/ws/` al proceso Java en `127.0.0.1:8080`.
El mapa usa Canvas 2D y botones accesibles, sin Leaflet, teselas ni servidores de mapas.
Node.js y Maven se usan para construir en el equipo de desarrollo, no en la VM.
El JAR incorpora Tomcat; no debe instalarse como WAR en el Tomcat independiente.
MySQL permanece disponible para una futura persistencia, pero esta entrega no lo usa.

## Construir el paquete

Requisitos locales: Java 21, Node >=22.12, Python 3, Git y acceso a las dependencias.
Desde la raíz del repositorio:

```bash
python3 despliegue/preparar_datos.py /ruta/c.1inf54.26-2.data_publicada /ruta/datos-kindbox
bash despliegue/empacar.sh /ruta/datos-kindbox
```

El preparador exige un destino nuevo. Copia las 36 ventas mensuales, expande los
segmentos de bloqueos y genera 666 registros de mantenimiento en 18 bimestres
(2026–2028), repitiendo días y unidades del archivo publicado de septiembre/octubre.
Conserva las huellas de los originales y la regla de generación. No modifica la fuente.
La flota inicial tiene 10 autos, 15 motos y 12 bicicletas; se cambia en `flota.txt`
antes de una corrida. Cada corrida captura su flota al arrancar.

El empaquetador ejecuta las pruebas Java y JavaScript, compila la interfaz y produce
`dist/kindbox-<fecha>-<commit>.tar.gz` y su SHA-256. Contiene JAR, interfaz, datos,
configuración e instalador. No contiene credenciales. Compile desde un árbol limpio
para que el identificador del paquete corresponda exactamente al commit publicado.

## Instalar en la VM

Compruebe primero el acceso SSH. Si el puerto 22 no responde, confirme con el profesor
si requiere VPN o habilitación de la IP de origen. No cambie el cortafuegos a ciegas.

```bash
scp dist/kindbox-*.tar.gz* 1inf54.983.3c@200.16.7.160:~/
ssh 1inf54.983.3c@200.16.7.160
sha256sum -c kindbox-<fecha>-<commit>.tar.gz.sha256
tar -xzf kindbox-<fecha>-<commit>.tar.gz
sudo bash kindbox/despliegue/instalar.sh
```

Antes de ejecutar el instalador, revise `sudo nginx -T`: el dominio y los puertos
80/443 deben poder asignarse a este sitio, y 8080 debe estar libre. No quite otros sitios
del equipo. El instalador no instala software; si falta Java 21, Nginx, OpenSSL o curl,
se detiene para que se valide con el profesor. La unidad requiere systemd y usuario
de Nginx `www-data`, propios de Ubuntu.

El instalador verifica las huellas, crea un usuario de servicio sin inicio de sesión,
solicita un usuario y una clave web (distinta de SSH), genera el certificado autofirmado,
instala la versión y comprueba `/api/salud`. Preserva versiones anteriores y restaura
el enlace y las configuraciones anteriores si falla el arranque o la validación de Nginx.
El proceso Java dispone de 640 MB de heap y un máximo de 1100 MB para memoria total.
Se conservan tres corridas en memoria para limitar consumo; falta medir la VM real bajo carga.

Abrir **https://1inf54-983-3c.inf.pucp.edu.pe/** desde computadora o celular.
El navegador advertirá que el certificado es autofirmado: compare la huella SHA-256
impresa por el instalador antes de confiar en él. En el celular se ofrece monitoreo,
mapa con arrastre y pellizco, pedidos y reportes; los controles operativos requieren
pantalla de al menos 1024 px, conforme al modo de consulta del visualizador.

Se protege todo el sitio con la autenticación de Nginx, incluida la API y el WebSocket.
Es acceso compartido para el equipo, no roles de Administrador/Operador/Cliente ni
seguimiento privado de clientes. El proxy rechaza orígenes ajenos; Java solo escucha
localmente. Configuración basada en las referencias oficiales de
[WebSocket](https://nginx.org/en/docs/http/websocket.html) y
[autenticación](https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html).

## Operación y reversión

```bash
sudo systemctl status kindbox
sudo journalctl -u kindbox -n 100
curl --fail http://127.0.0.1:8080/api/salud
sudo nginx -t
```

`/etc/kindbox/entorno` contiene configuración, sin claves de usuarios de aplicación.
Los registros están en journald. Los certificados y usuarios web están en `/etc/kindbox`.
Respaldar ese directorio con acceso restringido y los datos/versiones de `/opt/kindbox`.
No se recuperan corridas tras un reinicio: exporte sus reportes antes de actualizar.
Para volver a una versión anterior, sin una corrida activa:

```bash
sudo systemctl stop kindbox
sudo ln -sfn /opt/kindbox/releases/<version-anterior> /opt/kindbox/actual
sudo systemctl start kindbox
curl --fail http://127.0.0.1:8080/api/salud
```

## Alcance y pendientes explícitos

Esta es una entrega desplegable para demostración académica, no conformidad completa
con el diagrama de arquitectura. Quedan roles/sesiones/CSRF propios de la aplicación,
MySQL/JPA/Flyway, diario y recuperación de corridas, respaldos automáticos, pausa y
aceleración en caliente. No se actualizó Spring Boot únicamente para coincidir con el
diagrama. La seguridad de acceso inicial la cubre Nginx.

El relevo ya no limita la ruta ni anticipa el plazo de llegada. El servicio de una hora
por entrega parcial sigue fuera del plazo. El planificador reserva retorno al central
cuando hay mantenimiento en las siguientes 24 horas, y el motor devuelve unidades
sin entregas asignadas. Los bloqueos futuros y las averías aún pueden invalidar esa
previsión; persiste el retorno inmediato al central al comenzar mantenimiento como
contingencia del motor. Ese retorno excepcional todavía se representa instantáneamente.
Las pausas se validan cuando están presentes, pero falta certificar la pausa obligatoria
por cada conductor en rutas de varios turnos. No se afirma cumplimiento total de esa regla.

Las experimentaciones anteriores permanecen como evidencia de la versión anterior;
no son resultados del modelo corregido ni justifican declarar un algoritmo ganador.

## Entrega al DevOps para levantarlo localmente

No es necesario instalar Nginx ni systemd para revisar el proyecto en el equipo local.
Después de preparar los datos con el comando anterior, desde la raíz:

```bash
./mvnw -q install
java -Xmx640m -XX:+UseSerialGC -jar service/target/service-1.0-SNAPSHOT.jar \
  --kindbox.directorio-datos=/ruta/datos-kindbox
```

En otra terminal:

```bash
cd visualizador
npm ci
npm run build
npm run preview
```

Abrir `http://localhost:4173`. Para consultar desde un celular conectado a la misma
red, usar `http://IP-DEL-EQUIPO:4173` y permitir ese puerto en el cortafuegos local.
El proxy del visualizador conecta con Java en 8080; no se necesita exponer ese puerto
al celular. Este modo local no tiene la protección de acceso de Nginx. Para la VM
pública utilice la instalación HTTPS descrita arriba.
