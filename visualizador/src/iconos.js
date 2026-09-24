const trazos = {
    caja: '<path d="m3 7 9-5 9 5v10l-9 5-9-5Z M3 7l9 5 9-5 M12 12v10 M7 5l10 5v5"/>',
    casa: '<path d="m3 11 9-8 9 8v10h-7v-7h-4v7H3Z"/>',
    auto: '<path d="M2 5h12v13H2Z M14 10h4l4 5v3h-8"/><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="18" r="2.5"/>',
    moto: '<circle cx="5" cy="17" r="4"/><circle cx="19" cy="17" r="4"/><path d="m5 17 5-8 6 3 3 5 M10 9l-3-3 M14 4h3l2 8 M9 14h5"/>',
    bicicleta: '<circle cx="5" cy="17" r="4"/><circle cx="19" cy="17" r="4"/><path d="m5 17 5-9 5 9H5 M10 8h6l3 9 M8 5h4 M15 3h3l-2 5"/>',
    menu: '<path d="M4 6h16 M4 12h16 M4 18h16"/>',
    metricas: '<path d="M4 3v18h17 M8 16v-5 M13 16V6 M18 16V9"/>',
    pedidos: '<rect x="5" y="3" width="14" height="19" rx="2"/><path d="M9 8h6 M9 12h6 M9 16h4"/>',
    averias: '<path d="m12 3 10 18H2Z M12 9v5 M12 17v1"/>',
    leyenda: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6 M12 7v1"/>',
    perfil: '<circle cx="12" cy="7" r="4"/><path d="M3 22v-3a9 9 0 0 1 18 0v3"/>',
    cerrar: '<path d="m5 5 14 14 M19 5 5 19"/>',
    bloqueo: '<circle cx="12" cy="12" r="9"/><path d="m8 8 8 8 M16 8l-8 8"/>',
};
export function icono(nombre) {
    return `<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${trazos[nombre] || trazos.caja}</svg>`;
}
