export const nombresTipo = { AUTO: 'Auto', MOTO: 'Moto', BICICLETA: 'Bicicleta' };
export const nombresEstado = {
    PREPARADA: 'Preparando', EN_CURSO: 'En curso', CULMINADA: 'Culminada',
    CANCELADA: 'Cancelada', COLAPSADA: 'Colapso logístico', FALLIDA: 'Fallida',
    DISPONIBLE: 'Disponible', EN_RUTA: 'En ruta', AVERIADA: 'Averiada',
    EN_MANTENIMIENTO: 'En mantenimiento', EN_ALIMENTACION: 'En alimentación',
    ENTREGANDO: 'Entregando', ABASTECIENDO: 'Abasteciendo', ACONDICIONANDO: 'Acondicionando', EN_TRASVASE: 'En trasvase',
};
export const nombresEscenario = {
    SIMULACION_5D: 'Simulación de 5 días', COLAPSO: 'Hasta el colapso', DIA_A_DIA: 'Día a día',
};
export const estadosFinales = new Set(['CULMINADA', 'CANCELADA', 'COLAPSADA', 'FALLIDA']);
export const numero = (valor, decimales = 0) => valor == null ? '—' : Number(valor).toLocaleString('en-US', { maximumFractionDigits: decimales, minimumFractionDigits: decimales });
export const soles = valor => `S/ ${numero(valor, 2)}`;
export const escapar = valor => String(valor ?? '').replace(/[&<>"']/g, caracter => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[caracter]);
export function duracion(segundos) {
    if (segundos == null || segundos < 0) return '—';
    const total = Math.floor(segundos);
    const dias = Math.floor(total / 86400);
    const reloj = [Math.floor(total / 3600) % 24, Math.floor(total / 60) % 60, total % 60].map(valor => String(valor).padStart(2, '0')).join(':');
    return `${dias ? `${dias} d ` : ''}${reloj}`;
}
export function fecha(texto, segundos = false) {
    if (!texto) return '—';
    const [dia, hora] = texto.split('T');
    return `${dia.split('-').reverse().join('/')}${hora ? ` ${hora.slice(0, segundos ? 8 : 5)}` : ''}`;
}
export function sumarDias(dia, cantidad) {
    const instante = new Date(`${dia}T00:00:00Z`);
    instante.setUTCDate(instante.getUTCDate() + cantidad);
    return instante.toISOString().slice(0, 10);
}
export function fechaMinuto(dia, minuto) {
    return fecha(new Date(Date.parse(`${dia}T00:00:00Z`) + minuto * 60000).toISOString());
}
export function presupuestoSegundos(saltoMinutos, factorAceleracion) {
    return Math.max(50, Math.round(0.6 * saltoMinutos * 60000 / factorAceleracion)) / 1000;
}
export function etiquetaSemaforo(color) {
    return { VERDE: '✓ Saludable', AMBAR: '△ Alerta', ROJO: '! Crítico' }[color] || 'Sin datos';
}
