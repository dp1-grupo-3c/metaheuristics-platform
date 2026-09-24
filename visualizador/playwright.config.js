import { defineConfig as definirConfiguracion } from '@playwright/test';

export default definirConfiguracion({
    testDir: './pruebas/navegador',
    timeout: 90000,
    workers: 1,
    projects: [
        { name: 'servidor-real', testMatch: '**/servidor.spec.js' },
        { name: 'contingencias', testMatch: '**/fallida.spec.js', dependencies: ['servidor-real'] },
    ],
    use: {
        actionTimeout: 15000,
        baseURL: process.env.KINDBOX_VISUALIZADOR || 'http://127.0.0.1:5173',
        viewport: { width: 1440, height: 960 },
        locale: 'es-PE',
        launchOptions: process.env.KINDBOX_NAVEGADOR ? { executablePath: process.env.KINDBOX_NAVEGADOR } : {},
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure',
    },
    reporter: [['list']],
});
