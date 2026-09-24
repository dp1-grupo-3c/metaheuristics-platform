# Visualizador KindBox

Interfaz web de planificación y simulación para PaqRap. Vive junto al servidor en este repositorio, sin módulo Maven ni cambios en `core`, `service` o `experiments`.

## Levantar la aplicación

Requisitos: JDK 21 y Node.js 22.12 o posterior. Desde la raíz del repositorio:

```bash
./mvnw -q -pl core -am install -DskipTests
./mvnw -q -pl service spring-boot:run
```

En otra terminal:

```bash
cd visualizador
npm ci
npm run dev
```

Abra **http://localhost:5173**. El intermediario de desarrollo inicia Next.js en el puerto interno 5174 y remite `/api` y `/ws` al servidor en `127.0.0.1:8080`; funciona también desde otro dispositivo usando la dirección del equipo anfitrión. Para otro servidor:

```bash
KINDBOX_SERVIDOR=http://127.0.0.1:8081 npm run dev
```

El directorio de datos debe contener `ventas/`, `bloqueos/`, `mantenimiento/` y `flota.txt`, según el README principal. No se cargan pedidos desde el navegador. Para elegir datos ya preparados sin modificar archivos Java:

```bash
./mvnw -q -pl service spring-boot:run \
  -Dspring-boot.run.arguments="--kindbox.directorio-datos=/ruta/absoluta/datos"
```

Los archivos docentes originales de bloqueos pueden contener segmentos largos. El lector del backend exige nodos adyacentes: se necesita el directorio previamente normalizado, conservando coordenadas y horarios. La interfaz muestra los avisos de carga que devuelva el servicio.

## Compilar y comprobar

```bash
npm run build
npm run preview
npm test
npx playwright install chromium
npm run verificar
```

`preview` sirve la compilación en http://localhost:4173 con el mismo intermediario REST/WebSocket. Es una comprobación local, no un servidor de producción. `out/` se despliega como archivos estáticos; el servidor de despliegue debe remitir `/api/` a Spring y `/ws/` con actualización de protocolo WebSocket. No se necesitan cambios de CORS en Java: el servicio ya permite los orígenes y el intermediario mantiene el mismo origen en el navegador. La VM sirve los archivos con Nginx; no necesita instalar Node.js. Véase la [exportación estática de Next.js](https://nextjs.org/docs/app/guides/static-exports).

`npm run verificar` necesita el servidor real y `npm run dev` encendidos, datos de septiembre de 2026 y ninguna corrida activa. **Crea y cancela corridas de prueba, registra averías y modifica parámetros temporalmente.** No se debe ejecutar contra una presentación en curso. `KINDBOX_VISUALIZADOR` permite otra URL de interfaz; `KINDBOX_NAVEGADOR` permite usar un ejecutable Chromium ya instalado. Los resultados y capturas quedan en `test-results/`, excluido de Git. Pruebas implementadas con [Playwright](https://playwright.dev/docs/test-configuration).

## Decisiones de implementación

El visualizador está alineado con las tecnologías del diagrama de arquitectura:

- **Next.js y React:** App Router, páginas exportadas a `out/` y montaje del visualizador exclusivamente en el cliente. Las vistas existentes conservan su renderizador DOM dentro de un componente React con limpieza de eventos, canal y mapa al desmontarse. No se requiere servidor Next.js en producción.
- **TanStack Query:** `QueryClientProvider`, caché por ruta REST, deduplicación de consultas simultáneas, registro de mutaciones e invalidación tras escrituras. Un `QueryObserver` gestiona el sondeo periódico. Los mensajes WebSocket se guardan por corrida y tipo; se descartan instantáneas atrasadas. Las mutaciones no se reintentan automáticamente para evitar duplicar corridas o averías.
- **Leaflet con CRS.Simple:** administra la proyección, desplazamiento, zoom, marcadores y capas vectoriales de rutas y bloqueos. Se convierten los pares del servidor `(x,y)` al orden Leaflet `[y,x]`, con origen abajo a la izquierda. No se usan teselas geográficas, servicios externos ni claves.

Las rutas estáticas `/`, `/acceso/`, `/panel/`, `/pedidos/`, `/simulacion/`, `/reportes/`, `/configuracion/` y `/seguimiento/` abren el panel correspondiente. `/acceso/` informa que no hay autenticación; `/seguimiento/` permite consultar los pedidos disponibles, sin presentarse como una vista privada de cliente. Ambas requieren ampliar el backend para aplicar roles y privacidad.

La compilación incorpora las dependencias del navegador. Node.js se usa en desarrollo y construcción; `servidor.mjs` es solo la herramienta local para servir Next.js o `out/` y reenviar REST/WebSocket. No forma parte del despliegue propuesto en la VM. [TanStack Query](https://tanstack.com/query/latest/docs/reference/QueryClient) · [Leaflet CRS.Simple](https://leafletjs.com/examples/crs-simple/crs-simple.html).

Los identificadores propios y los textos están en español. Se conservan las claves obligatorias de HTML, CSS, JavaScript, herramientas y protocolos. La paleta proviene de `61.std.gui.v01.docx`; la organización reproduce los paneles y reportes de `01.definicion.prototipo.v02.pdf` y `prototype-shots`.

## Contrato utilizado

- REST: salud, almacenes y parámetros al iniciar; listado y detalle de corridas; pedidos paginados con filtros; arranque, cancelación, registro individual y carga masiva; velocidades y umbrales con `PUT`.
- WebSocket nativo, sin STOMP: sobres `{tipo, corrida, carga}` de tipo `corrida`, `instantanea` o `resultado`. Las cargas reemplazan instantáneas completas. Se descartan fotografías de otra corrida y minutos anteriores a la fotografía actual. Al cambiar de corrida se limpia el mapa anterior. La consulta histórica no es reemplazada por mensajes en vivo.
- Reconexión con esperas crecientes (1, 2, 4, 8 y 15 segundos), seis intentos y reintento manual. El último mapa permanece atenuado. El indicador de conexión representa disponibilidad del canal; el texto de espera distingue el tiempo sin avance simulado.
- Se consulta el detalle por REST cada segundo, sin solapar peticiones. Es indispensable: el servicio puede marcar `FALLIDA` sin publicar un mensaje final. `FALLIDA` detiene los indicadores de actividad y presenta el error funcional de la cabecera, aunque `resultado` sea nulo.
- Los errores HTTP se clasifican por `codigo`; se presenta `mensaje`. Nunca se decide por el texto de `error`. El campo `error` de **ResumenCorrida** sí es el detalle documentado de una corrida fallida, distinto del sobre HTTP.
- La carga masiva usa `multipart/form-data`, parte `archivo`, sin forzar su cabecera. Un error 400 conserva el mensaje y la línea indicada por Java. Las averías válidas muestran leídas, aplicadas, programadas y avisos.
- Las rutas y bloqueos son vectores planos de pares `[x,y,x,y,…]`. Se dibujan sin interpolar diagonales. Los pedidos no viajan en la instantánea: se consultan por separado todas las páginas activas, cada tres ciclos, para el mapa. La tabla conserva sus filtros y página y usa el semáforo calculado por Java.
- La semilla es explícita en el formulario, cabecera y exportación. La edición admite enteros representables exactamente por JavaScript (±9,007,199,254,740,991). No se promete reproducibilidad bit a bit de una búsqueda limitada por reloj.
- El presupuesto mostrado replica `PresupuestoComputo.deSimulacion`: máximo entre 50 ms y el 60 % de salto/K, redondeado a milisegundos. No existe un campo de presupuesto en la solicitud. Se informa si la combinación queda fuera de 2–18 s; no se modifica silenciosamente. Día a día usa K=1 y puede tener presupuestos mucho mayores con los valores del backend.
- El avance porcentual es del horizonte de datos, no de la búsqueda. La API no expone avance interno del algoritmo. Se muestra actividad indeterminada y segundos desde el último avance; no se inventa un porcentaje de optimización.
- Fechas sin zona son calendario simulado y no se convierten a la zona del navegador. La hora inicial es 00:00; el contrato admite fechas, no hora de inicio.
- `entregados + pendientes + incumplidos` es la partición de pedidos registrados. Los parciales no se suman de nuevo. Una corrida culminada con incumplimientos no se anuncia como operación sin incidencias.

## Diferencias del estándar que requieren backend

No se cambió el contrato para cubrir estos puntos:

1. **Pausa y cambio de aceleración en vivo (H.13):** faltan extremos REST y estado de pausa del motor. Se muestra el factor vigente, pero no controles ficticios.
2. **Roles, vista privada de Cliente y conductores (I.3/I.4):** no hay identidad, autorización ni datos de conductor. Se entrega el centro de monitoreo académico, con acceso de consulta en pantallas menores de 1024 px. Para restringir umbrales a administradores hace falta autorización en el servidor, no un selector de rol local.
3. **Semáforos:** el servicio guarda umbrales globales, no separados por corrida. El semáforo de pedidos usa el cálculo de Java, no los umbrales independientes 60/180 min del estándar. Se necesitarían parámetros y alcance por corrida para implementar esas normas sin discrepancias.
4. **Frescura estricta inferior a 2 s:** no hay sello temporal de emisión ni eventos de progreso durante una búsqueda. Se recibe por WebSocket y se sondea a un segundo, pero no se certifica latencia de extremo a extremo ni fotografías nuevas durante los 2–18 s de cómputo. La carga de pedidos puede prolongar el ciclo de consultas.
5. **Detalles de averías:** `VistaUnidad` expone tipo de avería, no fecha de registro/reincorporación ni conductor. Se muestra lo disponible y el mensaje de confirmación individual.
6. **Históricos:** los parámetros son globales y mutables, sin instantánea histórica de umbrales. El reporte etiqueta sus límites como vigentes, sin atribuirlos falsamente a toda la corrida.

Estos puntos impiden afirmar conformidad completa con los ítems V05, V06, V13 y V16 del estándar. El cierre del reporte conserva el último mapa para inspección; «Configurar nueva corrida» recupera el formulario con los valores anteriores.


## Pendiente del resto de la arquitectura

Esta migración se limita al visualizador. Permanecen pendientes la seguridad con sesión/CSRF/roles, MySQL/JPA/Flyway, ingesta idempotente, diario de comandos y puntos de control, latido y numeración de instantáneas, actualización a Spring Boot 4.1, instalación de Nginx/systemd en la VM, despliegue automatizado, respaldos y validación de límites de CPU/memoria. El backend continúa con Spring Boot 3.3.5 y Java 21. Estos elementos no se dan por implementados ni se modificaron en esta entrega.
