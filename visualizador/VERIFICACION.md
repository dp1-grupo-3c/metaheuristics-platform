# Verificación del visualizador

Fecha local: 23/09/2026. Backend base: `7ce6a6a`. No se modificaron los módulos Java.

Entorno: JDK 21.0.12, Node.js 26.8.2, Vite 8.3.0, Playwright 1.63.0 y Chromium 153. Servidor iniciado con `./mvnw -q -pl service spring-boot:run`, puerto 8080; interfaz en 5173, compilación en 4173. Datos reales del repositorio en `data/`, septiembre de 2026 (juego sintético generado anteriormente, no la carpeta docente original).

## Comprobaciones automatizadas

- `npm test`: cuatro pruebas de formato, presupuesto, escape de contenido y errores por código 400/404/409/500.
- `npm run build`: compilación de producción sin errores, sin dependencias de ejecución.
- `npm run verificar`: cinco pruebas de navegador. Tres usan el servidor real; dos controlan respuestas para estados difíciles de provocar de forma segura.
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
