export class ErrorServicio extends Error {
    constructor(codigo, mensaje, ruta = '') {
        super(mensaje);
        this.codigo = codigo;
        this.ruta = ruta;
    }
}
export function tituloError(codigo) {
    return ({ 400: 'Solicitud inválida', 404: 'No encontrado', 409: 'Conflicto de estado', 413: 'Archivo demasiado grande', 415: 'Formato no admitido', 500: 'Error del servidor' })[codigo] || 'No se pudo completar la operación';
}
export async function solicitar(ruta, { metodo = 'GET', cuerpo, archivo } = {}) {
    const opciones = { method: metodo, signal: AbortSignal.timeout(120000) };
    if (archivo) {
        opciones.body = new FormData();
        opciones.body.append('archivo', archivo);
    } else if (cuerpo !== undefined) {
        opciones.headers = { 'Content-Type': 'application/json' };
        opciones.body = JSON.stringify(cuerpo);
    }
    let respuesta;
    try {
        respuesta = await fetch(`/api${ruta}`, opciones);
    } catch {
        throw new ErrorServicio(0, 'No se pudo contactar con el servidor. Compruebe la conexión y consulte el estado de la corrida antes de repetir la operación.', ruta);
    }
    const contenido = await respuesta.json().catch(() => null);
    if (!respuesta.ok) throw new ErrorServicio(contenido?.codigo ?? respuesta.status, contenido?.mensaje || 'El servidor no devolvió un mensaje legible. Reintente la consulta.', contenido?.ruta || ruta);
    return contenido;
}

export class CanalSimulacion {
    constructor(recibir, cambiarEstado) {
        this.recibir = recibir;
        this.cambiarEstado = cambiarEstado;
        this.intentos = 0;
        this.version = 0;
    }
    conectar() {
        clearTimeout(this.espera);
        const version = ++this.version;
        this.canal?.close();
        this.cambiarEstado('Reconectando...');
        const destino = new URL('/ws/simulacion', location.href);
        destino.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const canal = new WebSocket(destino);
        this.canal = canal;
        const limite = setTimeout(() => canal.close(), 10000);
        canal.onopen = () => {
            clearTimeout(limite);
            if (version !== this.version) return;
            this.intentos = 0;
            this.cambiarEstado('Conexión Estable');
        };
        canal.onmessage = evento => {
            if (version !== this.version) return;
            try {
                const mensaje = JSON.parse(evento.data);
                if (['corrida', 'instantanea', 'resultado'].includes(mensaje.tipo)) this.recibir(mensaje);
            } catch {
                this.cambiarEstado('Reconectando...');
                canal.close();
            }
        };
        canal.onerror = () => canal.close();
        canal.onclose = () => {
            clearTimeout(limite);
            if (version !== this.version) return;
            this.intentos++;
            this.cambiarEstado(this.intentos >= 6 ? 'Desconectado' : 'Reconectando...');
            if (this.intentos < 6) this.espera = setTimeout(() => this.conectar(), Math.min(1000 * 2 ** (this.intentos - 1), 15000));
        };
    }
    reintentar() {
        this.intentos = 0;
        this.conectar();
    }
}
