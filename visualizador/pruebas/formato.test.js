import { test as prueba } from 'node:test';
import { strict as verificar } from 'node:assert';
import { duracion, fecha, sumarDias, presupuestoSegundos, escapar } from '../src/formato.js';
import { solicitar, tituloError } from '../src/api.js';

prueba('Las duraciones conservan días y las fechas simuladas no cambian de zona', () => {
    verificar.equal(duracion(5 * 86400 + 65), '5 d 00:01:05');
    verificar.equal(fecha('2026-09-01T14:35:00'), '01/09/2026 14:35');
    verificar.equal(sumarDias('2026-12-30', 4), '2027-01-03');
    verificar.equal(presupuestoSegundos(30, 240), 4.5);
});
prueba('El contenido del servidor se escapa antes de insertarse en HTML', () => {
    verificar.equal(escapar('<img onerror="x">'), '&lt;img onerror=&quot;x&quot;&gt;');
});
prueba('Los errores se deciden por codigo y conservan mensaje aunque error cambie', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ codigo: 409, error: 'texto arbitrario', mensaje: 'La unidad ya está averiada.', ruta: '/api/simulaciones/uno' }), { status: 409 });
    try {
        await verificar.rejects(solicitar('/simulaciones/uno'), error => error.codigo === 409 && error.message === 'La unidad ya está averiada.');
        verificar.equal(tituloError(409), 'Conflicto de estado');
    } finally { globalThis.fetch = original; }
});

prueba('400, 404 y 500 mantienen la categoría numérica del servidor', async () => {
    const original = globalThis.fetch;
    try {
        for (const codigo of [400, 404, 500]) {
            globalThis.fetch = async () => new Response(JSON.stringify({ codigo, error: 'Este texto no es un contrato', mensaje: `Mensaje ${codigo}` }), { status: codigo });
            await verificar.rejects(solicitar('/prueba'), error => error.codigo === codigo && error.message === `Mensaje ${codigo}`);
        }
    } finally { globalThis.fetch = original; }
});
