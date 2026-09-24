import { createServer as crearServidor } from 'node:http';
import { spawn as ejecutar } from 'node:child_process';
import intermediarioHttp from 'http-proxy';
import servirArchivos from 'sirv';

const desarrollo = process.argv.includes('--desarrollo');
const puerto = Number(process.env.PORT || (desarrollo ? 5173 : 4173));
const puertoNext = Number(process.env.KINDBOX_PUERTO_NEXT || 5174);
const destino = process.env.KINDBOX_SERVIDOR || 'http://127.0.0.1:8080';
const destinoNext = `http://127.0.0.1:${puertoNext}`;
const intermediario = intermediarioHttp.createProxyServer({});
const archivos = desarrollo ? null : servirArchivos('out', { dev: true, extensions: ['html'] });
const proceso = desarrollo ? ejecutar(process.execPath, ['node_modules/next/dist/bin/next', 'dev', '--webpack', '--hostname', '127.0.0.1', '--port', String(puertoNext)], { stdio: 'inherit' }) : null;
const servidor = crearServidor((peticion, respuesta) => {
    if (peticion.url.startsWith('/api/')) intermediario.web(peticion, respuesta, { target: destino });
    else if (desarrollo) intermediario.web(peticion, respuesta, { target: destinoNext });
    else archivos(peticion, respuesta, () => { respuesta.writeHead(404); respuesta.end('Página no encontrada'); });
});
servidor.on('upgrade', (peticion, conexion, cabecera) => {
    if (peticion.url.startsWith('/ws/')) intermediario.ws(peticion, conexion, cabecera, { target: destino });
    else if (desarrollo) intermediario.ws(peticion, conexion, cabecera, { target: destinoNext });
    else conexion.destroy();
});
intermediario.on('error', (_error, _peticion, respuesta) => {
    if (typeof respuesta.writeHead === 'function') {
        respuesta.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
        respuesta.end(JSON.stringify({ codigo: 502, mensaje: 'El servicio todavía no está disponible. Espere unos segundos y reintente.' }));
    } else respuesta.destroy();
});
function cerrar() { proceso?.kill('SIGTERM'); servidor.close(); }
process.on('SIGINT', cerrar);
process.on('SIGTERM', cerrar);
proceso?.on('exit', () => servidor.close());
servidor.on('error', error => { console.error(error.message); proceso?.kill('SIGTERM'); process.exitCode = 1; });
servidor.listen(puerto, '0.0.0.0', () => console.log(`KindBox: http://localhost:${puerto}`));
