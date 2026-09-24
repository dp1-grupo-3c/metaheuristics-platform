'use client';

import { useEffect as usarEfecto, useRef as usarReferencia } from 'react';
import { useQueryClient as usarClienteConsultas } from '@tanstack/react-query';

export default function Visualizador({ vista = 'configuracion' }) {
    const contenedor = usarReferencia(null);
    const cliente = usarClienteConsultas();
    usarEfecto(() => {
        let desmontado = false;
        let liberar;
        import('../src/aplicacion.js').then(({ montarVisualizador }) => {
            if (!desmontado) liberar = montarVisualizador(contenedor.current, cliente, vista);
        }).catch(() => {
            if (contenedor.current) contenedor.current.textContent = 'No se pudo cargar el visualizador. Recargue la página para reintentar.';
        });
        return () => { desmontado = true; liberar?.(); };
    }, [cliente, vista]);
    return <div id="aplicacion" ref={contenedor}><p role="status">Cargando el centro de reparto…</p></div>;
}
