import { test as prueba, expect as esperar } from '@playwright/test';

prueba('FALLIDA sin resultado ni evento final: recuperación por REST con respuesta controlada', async ({ page: pagina, request: peticiones }) => {
    const corridas = await (await peticiones.get('/api/simulaciones')).json();
    esperar(corridas.length, 'Ejecute primero las pruebas contra el servidor real').toBeGreaterThan(0);
    const base = await (await peticiones.get(`/api/simulaciones/${corridas[0].id}`)).json();
    const cabecera = { ...base.corrida, estado: 'EN_CURSO', error: null };
    let consultas = 0;
    await pagina.routeWebSocket('**/ws/simulacion', () => {});
    await pagina.route('**/api/simulaciones', ruta => ruta.fulfill({ json: [cabecera] }));
    await pagina.route(`**/api/simulaciones/${cabecera.id}`, ruta => {
        consultas++;
        return ruta.fulfill({ json: { ...base, corrida: { ...cabecera, estado: consultas > 1 ? 'FALLIDA' : 'EN_CURSO', error: consultas > 1 ? 'No se pudo completar la búsqueda de prueba.' : null }, instantanea: { ...base.instantanea, estado: 'EN_CURSO' }, resultado: null } });
    });
    await pagina.goto('/');
    await esperar(pagina.getByRole('heading', { name: 'Simulación fallida' })).toBeVisible();
    await esperar(pagina.locator('.desenlace')).toContainText('No se pudo completar la búsqueda de prueba.');
    await esperar(pagina.locator('#actividad')).toHaveText('Corrida terminada');
    await pagina.keyboard.press('Escape');
    await esperar(pagina.getByRole('button', { name: 'Cancelar', exact: true })).toBeDisabled();
    await esperar(pagina.getByRole('button', { name: 'Comenzar', exact: true })).toBeEnabled();
    await pagina.screenshot({ path: 'test-results/fallida-controlada.png' });
});

prueba('Culminación con incumplimientos: el reporte no declara éxito operativo', async ({ page: pagina, request: peticiones }) => {
    const corridas = await (await peticiones.get('/api/simulaciones')).json();
    const base = await (await peticiones.get(`/api/simulaciones/${corridas[0].id}`)).json();
    const corrida = { ...base.corrida, estado: 'CULMINADA' };
    const resultado = { ...base.resultado, estado: 'CULMINADA', metricas: { ...base.resultado.metricas, pedidosIncumplidos: 2 } };
    await pagina.routeWebSocket('**/ws/simulacion', () => {});
    await pagina.route('**/api/simulaciones', ruta => ruta.fulfill({ json: [corrida] }));
    await pagina.route(`**/api/simulaciones/${corrida.id}`, ruta => ruta.fulfill({ json: { ...base, corrida, resultado } }));
    await pagina.goto('/');
    await esperar(pagina.getByRole('heading', { name: 'Simulación culminada' })).toBeVisible();
    await esperar(pagina.locator('.desenlace')).toContainText('incumplidos');
    await esperar(pagina.locator('.desenlace')).not.toContainText('éxito');
});
