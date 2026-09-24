'use client';

import { useState as usarEstado } from 'react';
import { QueryClient as ClienteConsultas, QueryClientProvider as Proveedor } from '@tanstack/react-query';

export default function ProveedorConsultas({ children: contenido }) {
    const [cliente] = usarEstado(() => new ClienteConsultas({
        defaultOptions: {
            queries: { retry: false, staleTime: 0, gcTime: 60000, networkMode: 'always' },
            mutations: { retry: false, networkMode: 'always' },
        },
    }));
    return <Proveedor client={cliente}>{contenido}</Proveedor>;
}
