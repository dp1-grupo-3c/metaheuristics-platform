import { test as prueba } from 'node:test';
import { strict as verificar } from 'node:assert';
import { QueryClient as ClienteConsultas } from '@tanstack/react-query';
import { crearSolicitante, guardarMensaje } from '../src/consultas.js';

prueba('TanStack Query comparte consultas simultáneas y registra mutaciones sin reintentar', async () => {
    const original = globalThis.fetch;
    const cliente = new ClienteConsultas({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    let consultas = 0;
    globalThis.fetch = async (_ruta, opciones) => {
        consultas++;
        await new Promise(resolver => setTimeout(resolver, 10));
        return new Response(JSON.stringify({ metodo: opciones.method }));
    };
    try {
        const solicitar = crearSolicitante(cliente);
        const resultados = await Promise.all([solicitar('/salud'), solicitar('/salud')]);
        verificar.equal(consultas, 1);
        verificar.deepEqual(resultados[0], resultados[1]);
        await solicitar('/parametros/semaforo', { metodo: 'PUT', cuerpo: { ambar: 250, verde: 500 } });
        verificar.equal(consultas, 2);
        verificar.equal(cliente.getMutationCache().getAll()[0].state.status, 'success');
        verificar.equal(cliente.getQueryState(['api', '/salud']).isInvalidated, true);
    } finally { globalThis.fetch = original; cliente.clear(); }
});

prueba('El almacén de WebSocket separa corridas y rechaza minutos atrasados', () => {
    const cliente = new ClienteConsultas();
    try {
        guardarMensaje(cliente, { corrida: 'uno', tipo: 'instantanea', carga: { minutoSimulado: 20 } });
        guardarMensaje(cliente, { corrida: 'uno', tipo: 'instantanea', carga: { minutoSimulado: 10 } });
        guardarMensaje(cliente, { corrida: 'dos', tipo: 'instantanea', carga: { minutoSimulado: 0 } });
        verificar.equal(cliente.getQueryData(['simulacion', 'uno', 'instantanea']).minutoSimulado, 20);
        verificar.equal(cliente.getQueryData(['simulacion', 'dos', 'instantanea']).minutoSimulado, 0);
    } finally { cliente.clear(); }
});
