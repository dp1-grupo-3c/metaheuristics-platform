import { defineConfig as definirConfiguracion } from 'vite';

const destino = process.env.KINDBOX_SERVIDOR || 'http://127.0.0.1:8080';
const intermediarios = {
    '/api': { target: destino },
    '/ws': { target: destino, ws: true },
};

export default definirConfiguracion({
    server: { port: 5173, strictPort: true, proxy: intermediarios },
    preview: { port: 4173, strictPort: true, proxy: intermediarios },
});
