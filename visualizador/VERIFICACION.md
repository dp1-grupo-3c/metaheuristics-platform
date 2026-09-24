# Verificación del visualizador

Fecha local: 24/09/2026. Migración del visualizador sobre `bfe8602`. No se modificaron los módulos Java.

Entorno: JDK 21.0.12, Node.js 26.8.2, Next.js 16.3.6, React 19.3.0, TanStack Query 5.103.2, Leaflet 1.9.4, Playwright 1.63.0 y Chromium 153. Servidor iniciado con `./mvnw -q -pl service spring-boot:run`, puerto 8080; desarrollo en 5173, exportación estática en 4173. Datos reales del repositorio en `data/`, septiembre de 2026 (juego sintético generado anteriormente, no la carpeta docente original).

## Comprobaciones automatizadas

- `npm test`: dos archivos aprobados, con seis casos de formato, presupuesto, escape, errores por código, consultas compartidas, mutaciones e instantáneas separadas por corrida.
- `npm run build`: exportación estática de Next.js en `out/`, sin servidor Node.js necesario en producción.
- `KINDBOX_VISUALIZADOR=http://127.0.0.1:4173 npm run verificar`: seis pruebas de navegador aprobadas sobre la exportación. Cuatro usan el servidor real; dos controlan respuestas para estados difíciles de provocar de forma segura.
- `npm run dev`: arranque comprobado en Chromium, mapa Leaflet visible y conexión estable al backend real, sin errores de página.
- Rutas directas `/acceso`, `/panel`, `/pedidos`, `/simulacion`, `/reportes`, `/configuracion` y `/seguimiento`: respuesta 200 y panel correspondiente. Orientación cartesiana de almacenes, zoom por teclado y encuadre de Leaflet comprobados.
- Archivo inválido: error 400 del lector Java visible, incluyendo línea y nombre de archivo. Archivo válido: una avería programada. Registro individual aplicado a TA02.
- ALNS en 5D y HGS en colapso: arranque con semilla visible, instantáneas de las 37 unidades y cancelación con confirmación. La prueba verifica que el reporte no se cierre por la carrera entre WebSocket y DELETE.
- Interrupción de red y recuperación, navegación por teclado, búsqueda, tamaño de página, selección de vehículo desde la lista, zoom y encuadre.
- Una corrida ALNS de colapso alcanza su desenlace natural con el motor real. Rutas SVG con longitud positiva, bloqueos y pedidos representados conforme a las respuestas; modificación y restauración de umbrales y velocidad del auto.
- Anchuras 1440, 1024, 768, 390 y 360 px, sin desbordamiento horizontal de página; capturas revisadas visualmente.
- `FALLIDA` sin resultado y sin mensaje final: respuesta REST controlada construida a partir de un detalle real; se detiene la espera, aparece el error y se habilita una nueva corrida.
- `CULMINADA` con incumplimientos: respuesta controlada; el reporte muestra los incumplimientos y evita declarar éxito operativo.

La prueba corta de desenlace usa colapso de un día, duración de referencia de 1 minuto y salto de 30 minutos simulados (presupuesto de 750 ms). Es una prueba funcional del visualizador, **no** un experimento de calidad de las metaheurísticas ni una validación de rendimiento en el rango operativo 2–18 s. Los arranques y cancelaciones del flujo normal usan 4.5 s por llamada.

## Alcance pendiente de verificación

No se ejecutó una simulación 5D completa de 30–60 minutos para esta entrega. No se indujo un fallo real del motor: FALLIDA se verificó mediante respuesta controlada. No se certificó el requisito de latencia menor de 2 s durante diez minutos, ni se ensayaron Safari/Firefox, lectores de pantalla o un dispositivo táctil físico. El soporte de teclado y el diseño responsivo sí se comprobaron en Chromium.

Los cambios necesarios de backend para pausa, roles, umbrales por corrida, datos de conductor y medición de latencia se explican en el [README](README.md#diferencias-del-estándar-que-requieren-backend). La aplicación no inventa extremos para suplirlos.

Las capturas de la última verificación se conservan en `evidencias/`; los archivos temporales y trazas de Playwright están excluidos de Git.
