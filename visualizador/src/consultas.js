import { solicitar as solicitarHttp } from './api.js';

export function crearSolicitante(cliente) {
    return async (ruta, opciones = {}) => {
        if (!opciones.metodo || opciones.metodo === 'GET') {
            return cliente.fetchQuery({
                queryKey: ['api', ruta],
                queryFn: () => solicitarHttp(ruta, opciones),
                staleTime: 0,
            });
        }
        const mutacion = cliente.getMutationCache().build(cliente, {
            mutationKey: [opciones.metodo, ruta],
            mutationFn: () => solicitarHttp(ruta, opciones),
            retry: false,
            onSuccess: () => cliente.invalidateQueries({ queryKey: ['api'], refetchType: 'none' }),
        });
        return mutacion.execute();
    };
}

export function guardarMensaje(cliente, mensaje) {
    const clave = ['simulacion', mensaje.corrida, mensaje.tipo];
    cliente.setQueryData(clave, anterior => {
        if (mensaje.tipo === 'instantanea' && anterior?.minutoSimulado > mensaje.carga.minutoSimulado) return anterior;
        return mensaje.carga;
    });
}
