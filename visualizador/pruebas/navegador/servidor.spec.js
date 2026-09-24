import { test as prueba, expect as esperar } from '@playwright/test';

let parametrosIniciales;
prueba.beforeEach(async ({ request: peticiones }) => {
    parametrosIniciales = await (await peticiones.get('/api/parametros')).json();
});

prueba.afterEach(async ({ request: peticiones }) => {
    if (parametrosIniciales) {
        await peticiones.put('/api/parametros/semaforo', { data: { ambar: parametrosIniciales.umbralSemaforoAmbar, verde: parametrosIniciales.umbralSemaforoVerde } });
        await peticiones.put('/api/parametros/velocidad', { data: { tipo: 'AUTO', kmPorHora: parametrosIniciales.velocidades.AUTO } });
    }
    const respuesta = await peticiones.get('/api/simulaciones');
    for (const corrida of await respuesta.json()) {
        if (corrida.semilla === 20260923 && ['PREPARADA', 'EN_CURSO'].includes(corrida.estado)) await peticiones.delete(`/api/simulaciones/${corrida.id}`);
    }
});

prueba('Servidor real: configuración, mapa, averías, pedidos, reconexión y cancelación', async ({ page: pagina, request: peticiones, context: contexto }) => {
    const errores = [];
    pagina.on('pageerror', error => errores.push(error.message));
    const salud = await peticiones.get('/api/salud');
    esperar(salud.ok()).toBeTruthy();
    const datosSalud = await salud.json();
    esperar(datosSalud.corridaActiva, 'La prueba necesita el servidor sin corrida activa').toBeNull();
    await pagina.goto('/');
    await esperar(pagina.getByText('● Conexión Estable')).toBeVisible();
    await esperar(pagina.getByRole('button', { name: /Almacen central|Almacén central/i })).toBeVisible();
    if (await pagina.locator('#dialogo').isVisible()) await pagina.keyboard.press('Escape');
    await pagina.screenshot({ path: 'test-results/inicio.png' });
    await pagina.getByRole('combobox', { name: 'Algoritmo *', exact: true }).selectOption('ALNS');
    await pagina.getByLabel('Semilla de la corrida').fill('20260923');
    await pagina.getByRole('button', { name: 'Comenzar', exact: true }).click();
    await esperar(pagina.locator('#identidad')).toContainText('20260923');
    await esperar(pagina.locator('.marcador.unidad')).toHaveCount(37);
    await pagina.getByRole('button', { name: 'Averías', exact: true }).click();
    await pagina.locator('#archivoAverias').setInputFiles({ name: 'averias-invalidas.txt', mimeType: 'text/plain', buffer: Buffer.from('contenido mal formado\n') });
    await pagina.getByRole('button', { name: 'Cargar averías', exact: true }).click();
    await esperar(pagina.locator('#resultadoCarga')).toContainText('400');
    await esperar(pagina.locator('#resultadoCarga')).toContainText('linea');
    await pagina.getByRole('button', { name: 'Cerrar notificación' }).click();
    await pagina.locator('#archivoAverias').setInputFiles({ name: 'averias.txt', mimeType: 'text/plain', buffer: Buffer.from('05d23h00m:TA01:1\n') });
    await pagina.getByRole('button', { name: 'Cargar averías', exact: true }).click();
    await esperar(pagina.locator('#resultadoCarga')).toContainText('1 programadas');
    await pagina.getByLabel('Placa *').selectOption('TA02');
    await pagina.getByLabel('Tipo de avería *').selectOption('1');
    await pagina.getByRole('button', { name: 'Registrar avería', exact: true }).click();
    await esperar(pagina.locator('#mensajeNotificacion')).toContainText('TA02');
    await pagina.getByRole('button', { name: 'Pedidos', exact: true }).click();
    await pagina.getByLabel('Buscar pedido').fill('no-existe');
    await esperar(pagina.locator('#tablaPedidos')).toContainText('No hay pedidos');
    await pagina.getByLabel('Buscar pedido').fill('');
    await pagina.getByLabel('Filas por página').selectOption('5');
    await esperar(pagina.locator('#tamanoPagina')).toHaveValue('5');
    await pagina.locator('[data-unidad="TA02"]').click();
    await pagina.getByRole('button', { name: 'Cerrar panel' }).click();
    await pagina.getByRole('button', { name: 'Acercar mapa' }).click();
    await pagina.getByRole('button', { name: 'Ver toda la ciudad' }).click();
    await esperar(pagina.locator('.tarjetaMapa')).toContainText('TA02');
    await pagina.screenshot({ path: 'test-results/monitoreo.png' });
    await contexto.setOffline(true);
    await esperar(pagina.locator('#estadoConexion')).toContainText('Reconectando', { timeout: 15000 });
    await contexto.setOffline(false);
    await esperar(pagina.locator('#estadoConexion')).toContainText('Conexión Estable', { timeout: 30000 });
    await pagina.getByRole('button', { name: 'Cancelar', exact: true }).click();
    await esperar(pagina.getByRole('button', { name: 'Seguir simulando' })).toBeFocused();
    await pagina.getByRole('button', { name: 'Cancelar simulación', exact: true }).click();
    await esperar(pagina.getByRole('heading', { name: 'Simulación cancelada' })).toBeVisible({ timeout: 30000 });
    await pagina.screenshot({ path: 'test-results/resultado.png' });
    await pagina.getByRole('button', { name: 'Configurar nueva corrida' }).click();
    await esperar(pagina.getByRole('button', { name: 'Comenzar', exact: true })).toBeEnabled();
    await pagina.getByRole('combobox', { name: 'Algoritmo *', exact: true }).selectOption('HGS');
    await pagina.getByLabel('Escenario *').selectOption('COLAPSO');
    await pagina.getByRole('button', { name: 'Comenzar', exact: true }).click();
    await esperar(pagina.locator('#identidad')).toContainText('HGS');
    await pagina.getByRole('button', { name: 'Cancelar', exact: true }).click();
    await pagina.getByRole('button', { name: 'Cancelar simulación', exact: true }).click();
    await esperar(pagina.getByRole('heading', { name: 'Simulación cancelada' })).toBeVisible({ timeout: 30000 });
    esperar(errores).toEqual([]);
});

prueba('Diseño responsivo y navegación por teclado con el servidor real', async ({ page: pagina }) => {
    await pagina.goto('/');
    await esperar(pagina.locator('#estadoConexion')).toContainText('Conexión Estable');
    if (await pagina.locator('#dialogo').isVisible()) await pagina.keyboard.press('Escape');
    for (const ancho of [1440, 1024, 768, 390, 360]) {
        await pagina.setViewportSize({ width: ancho, height: 900 });
        await pagina.getByRole('button', { name: 'Leyenda', exact: true }).click();
        await esperar(pagina.getByRole('heading', { name: 'Leyenda', exact: true })).toBeVisible();
        esperar(await pagina.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        await pagina.screenshot({ path: `test-results/pantalla-${ancho}.png` });
        await pagina.keyboard.press('Escape');
    }
});

prueba('Servidor real: rutas, bloqueos, parámetros en caliente y desenlace natural', async ({ page: pagina, request: peticiones }) => {
    prueba.setTimeout(120000);
    await pagina.goto('/');
    await esperar(pagina.locator('#estadoConexion')).toContainText('Conexión Estable');
    if (await pagina.locator('#dialogo').isVisible()) await pagina.keyboard.press('Escape');
    await pagina.getByRole('button', { name: 'Cerrar panel' }).click();
    const respuesta = await peticiones.post('/api/simulaciones', { data: {
        tipo: 'COLAPSO', primerDia: '2026-09-01', ultimoDia: '2026-09-01',
        duracionMinutosReales: 1, saltoMinutos: 30, minutosEntreFotografias: 1,
        algoritmo: 'ALNS', semilla: 20260923, generarAverias: false,
    } });
    esperar(respuesta.status()).toBe(201);
    const corrida = await respuesta.json();
    await esperar(pagina.locator('#identidad')).toContainText(corrida.id);
    await esperar.poll(() => pagina.locator('path.ruta').evaluateAll(rutas => rutas.some(ruta => ruta.getTotalLength() > 0)), { timeout: 60000 }).toBeTruthy();
    await pagina.screenshot({ path: 'test-results/rutas-reales.png' });
    const conflicto = await peticiones.post('/api/simulaciones', { data: {} });
    esperar(conflicto.status()).toBe(409);
    const inexistente = await peticiones.get('/api/simulaciones/inexistente');
    esperar(inexistente.status()).toBe(404);
    await pagina.getByRole('button', { name: 'Configuración', exact: true }).click();
    await pagina.getByLabel('Umbral crítico *').fill('260');
    await pagina.getByRole('button', { name: 'Guardar umbrales' }).click();
    await esperar(pagina.locator('#rangos')).toContainText('260');
    await pagina.getByLabel('Umbral crítico *').fill('250');
    await pagina.getByRole('button', { name: 'Guardar umbrales' }).click();
    await pagina.getByLabel('Auto (km/h) *').fill('39');
    await pagina.getByRole('button', { name: 'Guardar auto' }).click();
    await esperar.poll(async () => (await (await peticiones.get('/api/parametros')).json()).velocidades.AUTO).toBe(39);
    await pagina.getByLabel('Auto (km/h) *').fill('40');
    await pagina.getByRole('button', { name: 'Guardar auto' }).click();
    await esperar(pagina.getByRole('heading', { name: /Simulación (colapso logístico|culminada)/ })).toBeVisible({ timeout: 90000 });
    const detalle = await (await peticiones.get(`/api/simulaciones/${corrida.id}`)).json();
    esperar(['COLAPSADA', 'CULMINADA']).toContain(detalle.corrida.estado);
    esperar(detalle.resultado.metricas.ejecucionesPlanificador).toBeGreaterThan(0);
    await esperar(pagina.locator('path.bloqueo')).toHaveCount(detalle.instantanea.bloqueosVigentes.length);
    const cantidadPedidos = await (await peticiones.get(`/api/simulaciones/${corrida.id}/pedidos?soloActivos=true&tamano=500`)).json();
    await esperar(pagina.locator('.marcador.pedido')).toHaveCount(cantidadPedidos.totalFilas, { timeout: 10000 });
    await pagina.screenshot({ path: 'test-results/desenlace-natural.png' });
});

prueba('Next exportado: rutas directas y orientación cartesiana de Leaflet', async ({ page: pagina }) => {
    const paneles = { acceso: 'Sesión', panel: 'Métricas', pedidos: 'Pedidos', simulacion: 'KindBox Sim', reportes: 'Métricas', configuracion: 'KindBox Sim', seguimiento: 'Pedidos' };
    for (const [ruta, titulo] of Object.entries(paneles)) {
        const respuesta = await pagina.goto(`/${ruta}/`);
        esperar(respuesta.status()).toBe(200);
        await esperar(pagina.locator('#estadoConexion')).toContainText('Conexión Estable');
        if (await pagina.locator('#dialogo').isVisible()) await pagina.keyboard.press('Escape');
        await esperar(pagina.locator('#tituloPanel')).toHaveText(titulo);
        await esperar(pagina.locator('.leaflet-container')).toBeVisible();
    }
    await pagina.getByRole('button', { name: 'Cerrar panel' }).click();
    const central = await pagina.locator('.marcador.almacen').filter({ hasText: 'Central' }).boundingBox();
    const noroeste = await pagina.locator('.marcador.almacen').filter({ hasText: 'A1' }).boundingBox();
    esperar(noroeste.x).toBeLessThan(central.x);
    esperar(noroeste.y).toBeLessThan(central.y);
    const escala = await pagina.locator('#escalaGrafica').evaluate(elemento => elemento.getBoundingClientRect().width);
    await pagina.locator('#mapa').focus();
    await pagina.keyboard.press('+');
    await esperar.poll(() => pagina.locator('#escalaGrafica').evaluate(elemento => elemento.getBoundingClientRect().width)).toBeGreaterThan(escala);
    await pagina.keyboard.press('Home');
    await esperar.poll(() => pagina.locator('#escalaGrafica').evaluate(elemento => elemento.getBoundingClientRect().width)).toBeCloseTo(escala, 0);
});
