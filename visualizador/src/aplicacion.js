import './estilos.css';
import { solicitar, CanalSimulacion, tituloError } from './api.js';
import { escapar, numero, soles, duracion, fecha, sumarDias, fechaMinuto, presupuestoSegundos, nombresTipo, nombresEstado, nombresEscenario, estadosFinales, etiquetaSemaforo } from './formato.js';
import { icono } from './iconos.js';
import { MapaCiudad } from './mapa.js';

const estado = {
    corrida: null, instantanea: null, resultado: null, parametros: null, almacenes: [],
    pedidosMapa: [], pagina: null, paginaNumero: 0, averias: [], panel: null,
    conexion: 'Reconectando...', ultimaRecepcion: null, ultimoAvance: Date.now(),
    ocupado: false, historico: false, cerrados: new Set(), consulta: window.innerWidth < 1024,
};
const porId = identificador => document.getElementById(identificador);
const aplicacion = porId('aplicacion');
const navegacion = [['configuracion', 'menu', 'Configuración'], ['metricas', 'metricas', 'Métricas'], ['pedidos', 'pedidos', 'Pedidos'], ['averias', 'averias', 'Averías'], ['leyenda', 'leyenda', 'Leyenda'], ['sesion', 'perfil', 'Sesión']];
aplicacion.innerHTML = `
    <header class="cabecera">
        <a class="marca" href="#" aria-label="KindBox, abrir configuración"><img src="/marca.svg" alt=""><span>KindBox<small>Centro de reparto</small></span></a>
        <div class="identidadCorrida"><strong id="nombreEscenario">Planifique su próxima ruta</strong><span id="identidad">Simulación y operaciones · PaqRap</span></div>
        <div class="conexion"><span id="estadoConexion" role="status">↻ Reconectando...</span><button id="reconectar" hidden>Reintentar conexión</button></div>
    </header>
    <section class="barraMonitoreo" aria-label="Estado de la simulación">
        <div class="controlesCorrida"><span id="estadoCorrida" class="estadoCorrida">Sin corrida</span><button id="cancelar" class="peligro" disabled>Cancelar</button></div>
        <div class="indicador"><span>Fecha y hora simuladas</span><strong id="fechaSimulada">—</strong></div>
        <div class="indicador"><span>Tiempo simulado</span><strong id="tiempoSimulado">00:00:00</strong></div>
        <div class="indicador"><span>Tiempo real / objetivo</span><strong id="tiempoReal">—</strong></div>
        <div class="indicador"><span>Unidades activas</span><strong id="unidadesActivas" class="conteoFlota">—</strong></div>
        <div class="indicador"><span>Pedidos entregados</span><strong id="pedidosEntregados">—</strong></div>
        <div class="indicador"><span>Paquetes entregados</span><strong id="paquetesEntregados">—</strong></div>
    </section>
    <div class="franjaAvance"><progress id="avance" max="100" value="0" aria-label="Avance del horizonte simulado"></progress><span id="avanceTexto">Listo para configurar</span><span id="actividad"></span></div>
    <div class="espacioTrabajo">
        <main id="mapa" class="mapa" tabindex="0" aria-label="Mapa de reparto. Use flechas para desplazarse, más y menos para zoom, Inicio para ver la ciudad."></main>
        <nav class="barraLateral" aria-label="Paneles de monitoreo">
            <img class="marcaLateral" src="/marca.svg" alt="KindBox">
            ${navegacion.map(([clave, simbolo, texto]) => `<button data-panel="${clave}" aria-label="${texto}" title="${texto}" aria-expanded="false">${icono(simbolo)}<span>${texto}</span></button>`).join('')}
        </nav>
        <aside id="panel" class="panel" hidden><div class="tituloPanel"><h2 id="tituloPanel"></h2><button id="cerrarPanel" aria-label="Cerrar panel">${icono('cerrar')}</button></div><div id="contenidoPanel" class="contenidoPanel"></div></aside>
        <div id="frescura" class="frescura" hidden></div>
    </div>
    <footer><span>KindBox / PaqRap</span><span id="pieEstado">Conectando con el centro de reparto…</span><span>Ciudad cartesiana · 1 km por nodo</span></footer>
    <div id="notificacion" class="notificacion" hidden><div id="mensajeNotificacion" role="status"></div><button id="cerrarNotificacion" aria-label="Cerrar notificación">×</button></div>
    <dialog id="dialogo" aria-labelledby="tituloDialogo"><div class="tituloPanel"><h2 id="tituloDialogo"></h2><button id="cerrarDialogo" aria-label="Cerrar diálogo">${icono('cerrar')}</button></div><div id="contenidoDialogo" class="contenidoDialogo"></div></dialog>`;
const mapa = new MapaCiudad(porId('mapa'), () => {});
let temporizadorNotificacion;
function notificar(mensaje, esError = false) {
    clearTimeout(temporizadorNotificacion);
    porId('notificacion').hidden = false;
    porId('notificacion').classList.toggle('esError', esError);
    porId('mensajeNotificacion').setAttribute('role', esError ? 'alert' : 'status');
    porId('mensajeNotificacion').textContent = mensaje;
    if (!esError) temporizadorNotificacion = setTimeout(() => { porId('notificacion').hidden = true; }, 5000);
}
function mostrarError(error) {
    notificar(`${tituloError(error.codigo)}${error.codigo ? ` (${error.codigo})` : ''}: ${error.message}`, true);
}
porId('cerrarNotificacion').onclick = () => { porId('notificacion').hidden = true; };
const enCurso = () => estado.corrida && !estadosFinales.has(estado.corrida.estado);
const metricas = () => estado.resultado?.metricas || estado.instantanea?.metricas;
function pintarMapa() {
    mapa.actualizar(estado.instantanea || { almacenes: estado.almacenes, unidades: [], bloqueosVigentes: [] }, estado.pedidosMapa, estado.parametros, estado.corrida);
}
function cambiarConexion(conexion) {
    estado.conexion = conexion;
    porId('estadoConexion').textContent = `${conexion === 'Conexión Estable' ? '●' : conexion === 'Desconectado' ? '⊘' : '↻'} ${conexion}`;
    porId('estadoConexion').className = conexion === 'Conexión Estable' ? 'saludable' : conexion === 'Desconectado' ? 'critico' : 'alerta';
    porId('reconectar').hidden = conexion !== 'Desconectado';
    porId('mapa').classList.toggle('desactualizado', conexion !== 'Conexión Estable');
    pintarFrescura();
}
function pintarFrescura() {
    porId('frescura').hidden = estado.conexion === 'Conexión Estable';
    porId('frescura').textContent = `Última consulta recibida: ${estado.ultimaRecepcion ? estado.ultimaRecepcion.toLocaleString('es-PE', { hour12: false }) : 'sin datos'}. Se conserva el último estado conocido.`;
}
function recibirCabecera(corrida) {
    if (estado.corrida?.id !== corrida.id) {
        estado.instantanea = null;
        estado.resultado = null;
        estado.pedidosMapa = [];
        estado.pagina = null;
        estado.paginaNumero = 0;
        estado.averias = [];
        mapa.elegida = null;
        estado.ultimoAvance = Date.now();
        if (porId('dialogo').open) porId('dialogo').close();
    }
    if (estado.corrida?.id === corrida.id && estadosFinales.has(estado.corrida.estado) && !estadosFinales.has(corrida.estado)) return;
    estado.corrida = corrida;
    if (corrida.estado === 'FALLIDA') finalizar();
    pintarCabecera();
}
function recibirInstantanea(instantanea) {
    if (estado.instantanea && instantanea.minutoSimulado < estado.instantanea.minutoSimulado) return;
    if (estado.instantanea?.minutoSimulado !== instantanea.minutoSimulado) estado.ultimoAvance = Date.now();
    estado.instantanea = instantanea;
    if (estado.corrida && !estadosFinales.has(estado.corrida.estado)) estado.corrida.estado = instantanea.estado;
    pintarCabecera();
    pintarMapa();
    actualizarPanelVivo();
}
const canal = new CanalSimulacion(mensaje => {
    if (estado.historico) return;
    estado.ultimaRecepcion = new Date();
    if (mensaje.tipo === 'corrida') {
        if (estado.corrida?.id === mensaje.corrida && estadosFinales.has(estado.corrida.estado)) return;
        recibirCabecera(mensaje.carga);
        refrescar();
    } else if (mensaje.corrida === estado.corrida?.id) {
        if (mensaje.tipo === 'instantanea' && !estado.resultado && estado.corrida.estado !== 'FALLIDA') recibirInstantanea(mensaje.carga);
        if (mensaje.tipo === 'resultado') {
            estado.resultado = mensaje.carga;
            estado.corrida.estado = mensaje.carga.estado;
            finalizar();
            pintarCabecera();
        }
    }
}, cambiarConexion);
porId('reconectar').onclick = () => canal.reintentar();

function pintarCabecera() {
    const corrida = estado.corrida;
    const foto = estado.instantanea;
    const indicadores = metricas();
    porId('nombreEscenario').textContent = corrida ? nombresEscenario[corrida.tipo] : 'Planifique su próxima ruta';
    porId('identidad').textContent = corrida ? `${corrida.id} · ${corrida.algoritmo} · Semilla ${corrida.semilla}${estado.historico ? ' · Consulta histórica' : ''}` : 'Simulación y operaciones · PaqRap';
    porId('estadoCorrida').textContent = corrida ? nombresEstado[corrida.estado] : 'Sin corrida';
    porId('cancelar').disabled = !enCurso() || estado.ocupado || estado.consulta || estado.historico;
    porId('fechaSimulada').textContent = fecha(estado.resultado?.fechaHoraFinal || foto?.fechaHoraSimulada || corrida?.fechaHoraSimulada);
    const minuto = estado.resultado?.minutoFinal ?? foto?.minutoSimulado ?? corrida?.minutoSimulado ?? 0;
    porId('tiempoSimulado').textContent = duracion(minuto * 60);
    porId('tiempoReal').textContent = corrida ? `${duracion((estado.resultado?.milisegundosReales ?? foto?.milisegundosReales ?? corrida.milisegundosReales) / 1000)} / ${corrida.tipo === 'DIA_A_DIA' ? '24 h' : `${corrida.duracionMinutosReales} min`}` : '—';
    porId('unidadesActivas').innerHTML = corrida ? Object.entries(nombresTipo).map(([tipo, nombre]) => {
        const cantidad = foto ? foto.unidades.filter(unidad => unidad.tipo === tipo && !['DISPONIBLE', 'AVERIADA', 'EN_MANTENIMIENTO'].includes(unidad.estado) && unidad.tipoAveria === 0).length : corrida.unidadesActivasPorTipo[tipo];
        return `<span title="${nombre}" aria-label="${cantidad} ${nombre}">${icono(tipo.toLowerCase())}${cantidad}</span>`;
    }).join('') : '—';
    porId('pedidosEntregados').textContent = numero(indicadores?.pedidosEntregados);
    porId('paquetesEntregados').textContent = numero(indicadores?.unidadesEntregadas);
    const porcentaje = corrida ? Math.min(100, minuto / corrida.minutosHorizonte * 100) : 0;
    porId('avance').value = porcentaje;
    porId('avanceTexto').textContent = corrida ? `${numero(porcentaje, 1)} % del horizonte · x${numero(corrida.factorAceleracion, 1)}` : 'Listo para configurar';
    const esperaSegundos = Math.floor((Date.now() - estado.ultimoAvance) / 1000);
    porId('actividad').textContent = estado.ocupado ? 'Procesando solicitud…' : enCurso() ? `Planificador: ${numero(presupuestoSegundos(corrida.saltoMinutos, corrida.factorAceleracion), 1)} s por llamada${esperaSegundos > 2 ? ` · Esperando avance (${esperaSegundos} s)` : ' · Monitoreo en vivo'}` : corrida ? 'Corrida terminada' : 'Elija un escenario para comenzar';
    porId('actividad').classList.toggle('esperando', Boolean(estado.ocupado || enCurso()));
    porId('pieEstado').textContent = estado.consulta ? 'Modo de consulta · controles operativos desde 1024 px' : estado.historico ? 'Consulta de resultados anteriores' : 'Monitoreo de la red';
    const campos = porId('camposCorrida');
    if (campos) campos.disabled = Boolean(enCurso() || estado.ocupado || estado.consulta);
    const comenzar = porId('comenzar');
    if (comenzar) comenzar.textContent = estado.ocupado ? 'Preparando corrida…' : enCurso() ? 'Corrida en curso' : 'Comenzar';
    document.querySelectorAll('[data-parametro]').forEach(elemento => { elemento.disabled = estado.consulta || elemento.dataset.guardando === 'true'; });
    document.querySelectorAll('[data-operativo]').forEach(elemento => { elemento.disabled = Boolean(estado.ocupado || estado.consulta || estado.historico || !enCurso() || elemento.dataset.guardando === 'true'); });
}
function tablaMetricas(indicadores) {
    if (!indicadores) return '<div class="vacio">Los indicadores aparecerán al iniciar la simulación.</div>';
    return `<section class="seccion"><span class="sobreTitulo">Desempeño acumulado</span><h3>Entregas y operación</h3><div class="resumenNumeros">
        <div><strong>${numero(indicadores.pedidosEntregados)}</strong><span>Entregados</span></div><div><strong>${numero(indicadores.pedidosPendientes)}</strong><span>Pendientes</span></div><div><strong>${numero(indicadores.pedidosIncumplidos)}</strong><span>Incumplidos</span></div><div><strong>${numero(indicadores.pedidosRegistrados)}</strong><span>Registrados</span></div></div><p>${indicadores.pedidosConEntregaParcial} con entrega parcial (incluidos en pendientes o incumplidos).</p></section>
        <section class="seccion"><h3>Tiempo de entrega por prioridad</h3>${[36, 18, 12, 8, 4].map((plazo, indice) => `<div class="filaDato"><span>${indice ? `Prioritario ${5 - indice}` : 'Normal'} (${plazo} h)</span><strong>${indicadores.entregasPorPlazo[plazo] ? duracion(indicadores.minutosEntregaPorPlazo[plazo] * 60) : 'Sin entregas'}</strong></div>`).join('')}</section>
        <section class="seccion"><h3>Costo acumulado</h3><strong class="cifraDestacada">${soles(indicadores.costoAcumulado)}</strong>${Object.entries(nombresTipo).map(([tipo, nombre]) => `<div class="filaDato"><span>${nombre}</span><strong>${numero(indicadores.kilometrosPorTipo[tipo])} km</strong></div>`).join('')}</section>
        <section class="seccion"><h3>Planificador</h3><div class="filaDato"><span>Ejecuciones</span><strong>${numero(indicadores.ejecucionesPlanificador)}</strong></div><div class="filaDato"><span>Tiempo total</span><strong>${duracion(indicadores.milisegundosPlanificador / 1000)}</strong></div></section>`;
}
function formularioConfiguracion() {
    const corrida = estado.corrida;
    return `<form id="formularioCorrida" class="seccion"><span class="sobreTitulo">Nueva planificación</span><h3>Configurar corrida</h3>
        <fieldset id="camposCorrida"><label>Escenario *<select name="tipo"><option value="SIMULACION_5D">Simulación de 5 días</option><option value="COLAPSO">Hasta el colapso</option><option value="DIA_A_DIA">Día a día</option></select></label>
        <label>Fecha de inicio *<input name="primerDia" type="date" required value="${corrida?.primerDia || '2026-09-01'}"></label>
        <label id="etiquetaFin">Fecha de fin *<input name="ultimoDia" type="date" required value="${corrida?.ultimoDia || '2026-09-05'}"></label>
        <p>Inicio a las 00:00 (hora simulada).</p>
        <div class="dosColumnas"><label>Algoritmo *<select name="algoritmo"><option value="ALNS">ALNS</option><option value="HGS">HGS</option></select></label><label>Duración real (min) *<select name="duracionMinutosReales"><option value="30">30 min</option><option value="45">45 min</option><option value="60">60 min</option></select></label></div>
        <label>Semilla de la corrida *<input name="semilla" type="number" min="-9007199254740991" max="9007199254740991" step="1" required value="${corrida?.semilla ?? 20260901}"></label>
        <div class="dosColumnas"><label>Salto (min simulados) *<input name="saltoMinutos" type="number" min="1" step="1" required value="${corrida?.saltoMinutos || 30}"></label><label>Fotografías (min simulados) *<input name="minutosEntreFotografias" type="number" min="1" step="1" required value="${corrida?.minutosEntreFotografias || 1}"></label></div>
        ${enCurso() ? '<p>Generación automática de averías: no informada en la cabecera de la corrida.</p>' : ''}<label class="casilla" ${enCurso() ? 'hidden' : ''}><input name="generarAverias" type="checkbox" checked> Generar averías durante la corrida</label>
        <output id="presupuesto" class="ayuda"></output><p id="errorCorrida" role="alert" class="errorCampo"></p>
        <button id="comenzar" class="primario" type="submit">Comenzar</button></fieldset>
        <p>Una corrida a la vez. La preparación y cada búsqueda pueden tardar varios segundos.</p></form>
        <section class="seccion"><h3>Semáforo de almacenes</h3><p>Umbrales compartidos del servicio, en unidades.</p><div id="rangos"></div>
        <form id="formularioSemaforo"><div class="dosColumnas"><label>Umbral crítico *<input name="ambar" type="number" min="0" max="999" required value="${estado.parametros?.umbralSemaforoAmbar ?? 250}"></label><label>Umbral de alerta *<input name="verde" type="number" min="1" max="1000" required value="${estado.parametros?.umbralSemaforoVerde ?? 500}"></label></div><p>0 ≤ crítico &lt; alerta ≤ 1,000 unidades.</p><button data-parametro type="submit" ${estado.consulta ? 'disabled' : ''}>Guardar umbrales</button></form></section>
        <section class="seccion"><h3>Velocidad de la flota</h3><p>Los cambios se aplican en la siguiente planificación.</p>${Object.entries(nombresTipo).map(([tipo, nombre]) => `<form class="formularioVelocidad"><input type="hidden" name="tipo" value="${tipo}"><label>${nombre} (km/h) *<input name="kmPorHora" type="number" min="0.1" step="0.1" required value="${estado.parametros?.velocidades[tipo] ?? ''}"></label><button data-parametro type="submit" ${estado.consulta ? 'disabled' : ''}>Guardar ${nombre.toLowerCase()}</button></form>`).join('')}</section>
        ${corrida?.avisos?.length ? `<section class="seccion"><h3>Avisos de los datos</h3><ul>${corrida.avisos.map(aviso => `<li>${escapar(aviso)}</li>`).join('')}</ul></section>` : ''}`;
}
function pintarRangos() {
    if (!porId('rangos') || !estado.parametros) return;
    const { umbralSemaforoAmbar: ambar, umbralSemaforoVerde: verde } = estado.parametros;
    porId('rangos').innerHTML = `<div class="barraInventario"><span style="flex:${ambar}" class="rojo"></span><span style="flex:${verde - ambar}" class="ambar"></span><span style="flex:${1000 - verde}" class="verde"></span></div><p>! Crítico: &lt; ${ambar} (${ambar / 10} %)<br>△ Alerta: ${ambar}–${verde - 1}<br>✓ Saludable: ${verde}–1,000 (${verde / 10} % o más)</p>`;
}
function conectarConfiguracion() {
    const formulario = porId('formularioCorrida');
    const campos = formulario.elements;
    if (estado.corrida) {
        campos.tipo.value = estado.corrida.tipo;
        campos.algoritmo.value = estado.corrida.algoritmo;
        if (![30,45,60].includes(estado.corrida.duracionMinutosReales)) {
            const opcion = new Option(`${estado.corrida.duracionMinutosReales} min`, String(estado.corrida.duracionMinutosReales));
            campos.duracionMinutosReales.add(opcion);
        }
        campos.duracionMinutosReales.value = estado.corrida.duracionMinutosReales;
    }
    const calcular = () => {
        const tipo = campos.tipo.value;
        if (!campos.primerDia.value) return;
        if (tipo !== 'COLAPSO') campos.ultimoDia.value = sumarDias(campos.primerDia.value, tipo === 'DIA_A_DIA' ? 0 : 4);
        campos.ultimoDia.readOnly = tipo !== 'COLAPSO';
        campos.duracionMinutosReales.disabled = tipo === 'DIA_A_DIA';
        const dias = (Date.parse(campos.ultimoDia.value) - Date.parse(campos.primerDia.value)) / 86400000 + 1;
        const factor = tipo === 'DIA_A_DIA' ? 1 : dias * 1440 / Number(campos.duracionMinutosReales.value);
        const segundos = presupuestoSegundos(Number(campos.saltoMinutos.value), factor);
        porId('presupuesto').textContent = `Aceleración x${numero(factor, 1)} · Presupuesto: ${numero(segundos, 2)} s por búsqueda.${segundos < 2 || segundos > 18 ? ' Fuera del rango recomendado de 2 a 18 s.' : ''}${tipo === 'COLAPSO' ? ' El colapso corre sin acompasar el reloj; la fecha de fin delimita los datos cargados.' : ''}`;
    };
    formulario.oninput = calcular;
    calcular();
    formulario.onsubmit = async evento => {
        evento.preventDefault();
        const solicitud = Object.fromEntries(new FormData(formulario));
        solicitud.duracionMinutosReales = Number(campos.duracionMinutosReales.value);
        for (const clave of ['semilla', 'saltoMinutos', 'minutosEntreFotografias']) solicitud[clave] = Number(solicitud[clave]);
        solicitud.generarAverias = campos.generarAverias.checked;
        if (solicitud.ultimoDia < solicitud.primerDia) {
            porId('errorCorrida').textContent = 'La fecha de fin debe ser igual o posterior a la de inicio.';
            campos.ultimoDia.focus();
            return;
        }
        porId('errorCorrida').textContent = '';
        estado.ocupado = true;
        pintarCabecera();
        try {
            const corrida = await solicitar('/simulaciones', { metodo: 'POST', cuerpo: solicitud });
            estado.historico = false;
            recibirCabecera(corrida);
            notificar(`Corrida ${corrida.id} iniciada con ${corrida.algoritmo} y semilla ${corrida.semilla}.`);
            await refrescar();
        } catch (error) { mostrarError(error); }
        finally { estado.ocupado = false; pintarCabecera(); }
    };
    porId('formularioSemaforo').onsubmit = async evento => {
        evento.preventDefault();
        const formulario = evento.currentTarget;
        const ambar = Number(formulario.elements.ambar.value);
        const verde = Number(formulario.elements.verde.value);
        if (ambar >= verde) {
            notificar(`El umbral crítico (${ambar} unidades) debe ser menor que el umbral de alerta (${verde} unidades). Ingrese un valor entre 0 y ${verde - 1}.`, true);
            formulario.elements.ambar.focus();
            return;
        }
        await guardarParametros(formulario, '/parametros/semaforo', { ambar, verde });
    };
    document.querySelectorAll('.formularioVelocidad').forEach(formulario => formulario.onsubmit = async evento => {
        evento.preventDefault();
        await guardarParametros(formulario, '/parametros/velocidad', { tipo: formulario.elements.tipo.value, kmPorHora: Number(formulario.elements.kmPorHora.value) });
    });
    pintarRangos();
}
async function guardarParametros(formulario, ruta, cuerpo) {
    const boton = formulario.querySelector('button');
    boton.disabled = true;
    boton.dataset.guardando = 'true';
    try {
        estado.parametros = await solicitar(ruta, { metodo: 'PUT', cuerpo });
        pintarRangos();
        pintarMapa();
        notificar('Parámetros guardados. Se aplicarán en la siguiente planificación.');
    } catch (error) { mostrarError(error); }
    finally { boton.dataset.guardando = 'false'; boton.disabled = estado.consulta; }
}
function panelPedidos() {
    return `<section class="seccion"><h3>Pedidos de la corrida</h3><label>Buscar pedido<input id="busqueda" type="search" placeholder="Identificador, cliente o dirección"></label><label class="casilla"><input id="soloActivos" type="checkbox" checked> Solo pedidos activos</label><div id="tablaPedidos"></div><div class="paginacion"><label>Filas por página<select id="tamanoPagina"><option>5</option><option>10</option><option selected>25</option><option>50</option></select></label><button id="anterior" aria-label="Página anterior">‹</button><button id="siguiente" aria-label="Página siguiente">›</button></div><p id="rangoPedidos"></p></section><section class="seccion"><h3>Unidades de la flota</h3><p>Seleccione una unidad para ver su ruta y carga.</p><div id="listaUnidades"></div></section>`;
}
let versionPedidos = 0;
async function cargarPagina() {
    if (!estado.corrida || !porId('busqueda')) return;
    const version = ++versionPedidos;
    const id = estado.corrida.id;
    const consulta = new URLSearchParams({ busqueda: porId('busqueda').value, pagina: estado.paginaNumero, tamano: porId('tamanoPagina').value, soloActivos: porId('soloActivos').checked });
    try {
        const pagina = await solicitar(`/simulaciones/${id}/pedidos?${consulta}`);
        if (version !== versionPedidos || estado.corrida?.id !== id || !porId('tablaPedidos')) return;
        estado.pagina = pagina;
        pintarPedidos();
    } catch (error) { mostrarError(error); }
}
function pintarPedidos() {
    const pagina = estado.pagina;
    if (!porId('tablaPedidos')) return;
    if (porId('tablaPedidos').contains(document.activeElement)) return;
    porId('tablaPedidos').innerHTML = !pagina?.filas.length ? '<p class="vacio">No hay pedidos que coincidan con la consulta.</p>' : `<div class="tablaDesplazable"><table><thead><tr><th>Pedido</th><th>Paquetes</th><th>Dirección</th><th>Plazo</th><th>Holgura</th></tr></thead><tbody>${pagina.filas.map(pedido => `<tr><td><button class="enlace" data-pedido="${pedido.id}">${pedido.id}</button></td><td>${pedido.paquetes}<small>${pedido.pendientes} pendientes</small></td><td>(${pedido.x},${pedido.y})</td><td>${pedido.plazoHoras} h</td><td><span class="${pedido.semaforo.toLowerCase()}">${pedido.entregado ? 'Entregado' : pedido.holguraMinutos < 0 ? 'Vencido' : etiquetaSemaforo(pedido.semaforo)}</span><small>${pedido.holguraMinutos} min</small></td></tr>`).join('')}</tbody></table></div>`;
    porId('rangoPedidos').textContent = pagina ? `${pagina.totalFilas ? pagina.pagina * pagina.tamano + 1 : 0}–${Math.min((pagina.pagina + 1) * pagina.tamano, pagina.totalFilas)} de ${pagina.totalFilas}` : 'Sin corrida';
    porId('anterior').disabled = !pagina || pagina.pagina === 0;
    porId('siguiente').disabled = !pagina || pagina.pagina + 1 >= pagina.totalPaginas;
    porId('tablaPedidos').querySelectorAll('[data-pedido]').forEach(boton => boton.onclick = () => {
        const pedido = pagina.filas.find(pedido => pedido.id === Number(boton.dataset.pedido));
        if (!estado.pedidosMapa.some(actual => actual.id === pedido.id)) estado.pedidosMapa.push(pedido);
        pintarMapa();
        mapa.centrarPedido(pedido);
        if (window.innerWidth < 768) abrirPanel(null);
    });
}
function conectarPedidos() {
    let demora;
    porId('busqueda').oninput = () => { clearTimeout(demora); demora = setTimeout(() => { estado.paginaNumero = 0; cargarPagina(); }, 300); };
    for (const identificador of ['soloActivos', 'tamanoPagina']) porId(identificador).onchange = () => { estado.paginaNumero = 0; cargarPagina(); };
    porId('anterior').onclick = () => { estado.paginaNumero--; cargarPagina(); };
    porId('siguiente').onclick = () => { estado.paginaNumero++; cargarPagina(); };
    cargarPagina();
    pintarPedidos();
    actualizarPanelVivo();
}
function panelAverias() {
    return `<section class="seccion"><h3>Registro masivo</h3><p>Archivo de texto UTF-8, hasta 1 MB. Una avería por línea, en el mes de inicio de la corrida.</p><code>01d10h30m:TA01:1</code><form id="formularioMasivo"><label>Archivo de averías *<input id="archivoAverias" name="archivo" type="file" accept=".txt,text/plain" required></label><button class="primario" data-operativo type="submit">Cargar averías</button></form><div id="resultadoCarga" role="status"></div></section>
        <section class="seccion"><h3>Registrar avería</h3><form id="formularioAveria"><label>Placa *<select name="placa" id="placaAveria" required><option value="">Seleccione una unidad</option></select></label><label>Tipo de avería *<select name="tipo" required><option value="">Seleccione un tipo</option><option value="1">Tipo 1 · Menor</option><option value="2">Tipo 2 · Intermedia</option><option value="3">Tipo 3 · Mayor</option></select></label><button class="primario" data-operativo type="submit">Registrar avería</button></form></section><section class="seccion"><h3>Registro de averías</h3><div id="registroAverias"></div></section>`;
}
function conectarAverias() {
    porId('formularioMasivo').onsubmit = async evento => {
        evento.preventDefault();
        const archivo = porId('archivoAverias').files[0];
        if (!archivo || !estado.corrida) return;
        const boton = evento.currentTarget.querySelector('button');
        boton.disabled = true;
        boton.dataset.guardando = 'true';
        boton.textContent = 'Cargando averías…';
        try {
            const resultado = await solicitar(`/simulaciones/${estado.corrida.id}/averias/masivo`, { metodo: 'POST', archivo });
            if (porId('resultadoCarga')) porId('resultadoCarga').innerHTML = `<p>${resultado.leidas} averías leídas; ${resultado.aplicadas} aplicadas y ${resultado.programadas} programadas.</p><ul>${resultado.avisos.map(aviso => `<li>${escapar(aviso)}</li>`).join('')}</ul>`;
            notificar('Archivo procesado. Revise el resumen de carga.');
        } catch (error) {
            if (porId('resultadoCarga')) porId('resultadoCarga').textContent = `${tituloError(error.codigo)} (${error.codigo}): ${error.message}`;
            mostrarError(error);
        } finally { boton.dataset.guardando = 'false'; boton.textContent = 'Cargar averías'; boton.disabled = !enCurso() || estado.consulta; }
    };
    porId('formularioAveria').onsubmit = async evento => {
        evento.preventDefault();
        const formulario = evento.currentTarget;
        const boton = formulario.querySelector('button');
        boton.disabled = true;
        boton.dataset.guardando = 'true';
        try {
            const respuesta = await solicitar(`/simulaciones/${estado.corrida.id}/averias`, { metodo: 'POST', cuerpo: { placa: formulario.elements.placa.value, tipo: Number(formulario.elements.tipo.value) } });
            notificar(respuesta.mensaje);
            await refrescar();
        } catch (error) { mostrarError(error); }
        finally { boton.dataset.guardando = 'false'; boton.disabled = !enCurso() || estado.consulta; }
    };
    actualizarPanelVivo();
}
function panelLeyenda() {
    return `<section class="seccion"><h3>Elementos del mapa</h3>${[['casa','marca','Almacén central · ilimitado'],['caja','verde','Intermedio · ✓ Saludable'],['caja','ambar','Intermedio · △ Alerta'],['caja','rojo','Intermedio · ! Crítico'],['auto','auto','Auto'],['moto','moto','Moto'],['bicicleta','bicicleta','Bicicleta'],['auto','rojo','! Auto averiado'],['moto','rojo','! Moto averiada'],['bicicleta','rojo','! Bicicleta averiada'],['pedidos','marca','Pedido'],['bloqueo','rojo','Vía bloqueada']].map(([simbolo,color,texto]) => `<div class="itemLeyenda"><span style="color:var(--${color})">${icono(simbolo)}</span>${texto}</div>`).join('')}<div class="itemLeyenda"><span class="muestraRuta"></span>Camino recorrido</div><div class="itemLeyenda"><span class="muestraRuta discontinua"></span>Camino pendiente</div><div class="itemLeyenda"><span>●</span>Destino de la unidad seleccionada</div></section><section class="seccion"><h3>Capas visibles</h3>${Object.entries({ unidades:'Unidades', rutas:'Rutas', pedidos:'Pedidos activos', bloqueos:'Bloqueos' }).map(([clave,texto]) => `<label class="casilla"><input type="checkbox" data-capa="${clave}" ${mapa.capas[clave] ? 'checked' : ''}> ${texto}</label>`).join('')}<p>Arrastre para desplazar. Use la rueda, pellizco, + y − para ampliar. Con el mapa enfocado, las flechas desplazan y la tecla Inicio encuadra la ciudad.</p></section>`;
}
function panelSesion() {
    return `<section class="seccion"><h3>Centro de reparto</h3><p>Sesión de operación académica. El servicio no dispone de inicio de sesión ni permisos por usuario.</p><p>La pausa y el cambio de aceleración durante una corrida no están disponibles. La aceleración se define al comenzar.</p><button id="verActual">Volver al monitoreo actual</button></section><section class="seccion"><h3>Corridas recientes</h3><div id="historial">Consultando…</div></section>`;
}
async function cargarHistorial() {
    try {
        const corridas = await solicitar('/simulaciones');
        if (!porId('historial')) return;
        porId('historial').innerHTML = corridas.length ? corridas.map(corrida => `<button class="filaHistorial" data-corrida="${escapar(corrida.id)}"><strong>${escapar(corrida.id)} · ${corrida.algoritmo}</strong><span>${nombresEstado[corrida.estado]} · ${fecha(corrida.primerDia)}</span><small>Semilla ${corrida.semilla}</small></button>`).join('') : '<p>No hay corridas registradas.</p>';
        porId('historial').querySelectorAll('[data-corrida]').forEach(boton => boton.onclick = async () => {
            estado.historico = true;
            recibirCabecera(corridas.find(corrida => corrida.id === boton.dataset.corrida));
            await refrescar();
            if (estadosFinales.has(estado.corrida.estado)) mostrarResultado();
        });
    } catch (error) { mostrarError(error); }
}
function abrirPanel(clave) {
    estado.panel = clave;
    porId('panel').hidden = !clave;
    aplicacion.classList.toggle('conPanel', Boolean(clave));
    document.querySelectorAll('[data-panel]').forEach(boton => {
        boton.classList.toggle('activo', boton.dataset.panel === clave);
        boton.setAttribute('aria-expanded', String(boton.dataset.panel === clave));
    });
    if (!clave) return;
    porId('tituloPanel').textContent = { configuracion: 'KindBox Sim', metricas: 'Métricas', pedidos: 'Pedidos', averias: 'Averías', leyenda: 'Leyenda', sesion: 'Sesión' }[clave];
    porId('contenidoPanel').innerHTML = { configuracion: formularioConfiguracion, metricas: () => `<div id="metricasVivas">${tablaMetricas(metricas())}</div><button id="verResultado" ${estadosFinales.has(estado.corrida?.estado) ? '' : 'hidden'}>Ver reporte final</button>`, pedidos: panelPedidos, averias: panelAverias, leyenda: panelLeyenda, sesion: panelSesion }[clave]();
    if (clave === 'configuracion') conectarConfiguracion();
    if (clave === 'pedidos') conectarPedidos();
    if (clave === 'averias') conectarAverias();
    if (clave === 'metricas') porId('verResultado').onclick = mostrarResultado;
    if (clave === 'leyenda') document.querySelectorAll('[data-capa]').forEach(casilla => casilla.onchange = () => { mapa.capas[casilla.dataset.capa] = casilla.checked; pintarMapa(); });
    if (clave === 'sesion') {
        cargarHistorial();
        porId('verActual').onclick = async () => { estado.historico = false; await descubrir(); };
    }
    pintarCabecera();
}
document.querySelectorAll('[data-panel]').forEach(boton => boton.onclick = () => abrirPanel(estado.panel === boton.dataset.panel ? null : boton.dataset.panel));
porId('cerrarPanel').onclick = () => {
    const clave = estado.panel;
    abrirPanel(null);
    document.querySelector(`[data-panel="${clave}"]`)?.focus();
};
document.querySelector('.marca').onclick = evento => { evento.preventDefault(); abrirPanel('configuracion'); };
function actualizarPanelVivo() {
    if (porId('metricasVivas')) porId('metricasVivas').innerHTML = tablaMetricas(metricas());
    if (porId('verResultado')) porId('verResultado').hidden = !estadosFinales.has(estado.corrida?.estado);
    const unidades = estado.instantanea?.unidades || [];
    if (porId('listaUnidades')) {
        const lista = porId('listaUnidades');
        if (!lista.contains(document.activeElement)) {
            lista.innerHTML = unidades.map(unidad => `<button class="filaUnidad" data-unidad="${unidad.codigo}">${icono(unidad.tipo.toLowerCase())}<strong>${unidad.codigo}</strong><span>${escapar(nombresEstado[unidad.estado] || unidad.estado)}</span></button>`).join('') || '<p>Sin unidades para mostrar.</p>';
            lista.querySelectorAll('[data-unidad]').forEach(boton => boton.onclick = () => { mapa.elegir(boton.dataset.unidad); if (window.innerWidth < 768) abrirPanel(null); });
        }
    }
    if (porId('placaAveria') && porId('placaAveria').options.length !== unidades.length + 1) {
        const valor = porId('placaAveria').value;
        porId('placaAveria').innerHTML = '<option value="">Seleccione una unidad</option>' + unidades.map(unidad => `<option>${unidad.codigo}</option>`).join('');
        porId('placaAveria').value = valor;
    }
    if (porId('registroAverias')) porId('registroAverias').innerHTML = estado.averias.length ? `<ul>${estado.averias.map(averia => `<li>${escapar(averia)}</li>`).join('')}</ul>` : '<p>No hay averías registradas.</p>';
}
let focoAnterior;
function abrirDialogo(titulo, contenido) {
    const dialogo = porId('dialogo');
    if (!dialogo.open) focoAnterior = document.activeElement;
    porId('tituloDialogo').textContent = titulo;
    porId('contenidoDialogo').innerHTML = contenido;
    if (!dialogo.open) dialogo.showModal();
}
porId('cerrarDialogo').onclick = () => porId('dialogo').close();
porId('dialogo').addEventListener('close', () => focoAnterior?.isConnected && focoAnterior.focus());
porId('cancelar').onclick = () => {
    abrirDialogo('¿Cancelar la simulación en curso?', '<p>La simulación se detendrá y se mostrará el reporte con los resultados acumulados hasta este momento. Esta acción no se puede deshacer.</p><div class="accionesDialogo"><button id="seguir">Seguir simulando</button><button id="confirmarCancelacion" class="peligro">Cancelar simulación</button></div>');
    porId('seguir').focus();
    porId('seguir').onclick = () => porId('dialogo').close();
    porId('confirmarCancelacion').onclick = async () => {
        porId('confirmarCancelacion').disabled = true;
        porId('dialogo').close();
        try {
            await solicitar(`/simulaciones/${estado.corrida.id}`, { metodo: 'DELETE' });
            notificar('Cancelación solicitada. Esperando el cierre de la búsqueda en curso.');
            await refrescar();
        } catch (error) { mostrarError(error); }
    };
};
function finalizar() {
    if (!estado.corrida || estado.cerrados.has(estado.corrida.id)) return;
    estado.cerrados.add(estado.corrida.id);
    mostrarResultado();
    actualizarPanelVivo();
}
function mostrarResultado() {
    const corrida = estado.corrida;
    if (!corrida || !estadosFinales.has(corrida.estado)) return;
    const resultado = estado.resultado;
    const indicadores = metricas();
    const frases = {
        CULMINADA: `La simulación del ${fecha(corrida.primerDia)} al ${fecha(corrida.ultimoDia)} completó su horizonte.${indicadores?.pedidosIncumplidos ? ' Se registraron pedidos incumplidos; revise los indicadores.' : ' Culminó con éxito.'}`,
        CANCELADA: `La simulación del ${fecha(corrida.primerDia)} al ${fecha(corrida.ultimoDia)} fue cancelada el ${fecha(resultado?.fechaHoraFinal)} (hora simulada).`,
        COLAPSADA: `La simulación iniciada el ${fecha(corrida.primerDia)} se detuvo por colapso logístico el ${fecha(resultado?.fechaHoraFinal)} (hora simulada). Pedido que originó el colapso: ${resultado?.pedidoDelPrimerIncumplimiento ?? '—'}.`,
        FALLIDA: corrida.error || 'La corrida terminó con un error. Revise los registros del servidor antes de iniciar una nueva.',
    };
    abrirDialogo(`Simulación ${nombresEstado[corrida.estado].toLowerCase()}`, `<p class="desenlace" role="status">${escapar(frases[corrida.estado])}</p><p>${corrida.algoritmo} · Semilla ${corrida.semilla} · ${escapar(corrida.id)}</p><div class="resumenFinal"><div class="seccion"><span>Tiempo simulado</span><strong>${duracion((resultado?.minutoFinal ?? estado.instantanea?.minutoSimulado ?? 0) * 60)}</strong></div><div class="seccion"><span>Duración real</span><strong>${duracion((resultado?.milisegundosReales ?? corrida.milisegundosReales) / 1000)}</strong></div></div>${tablaMetricas(indicadores)}
        <section class="seccion"><h3>Límites de inventario vigentes</h3><div class="barraInventario"><span class="rojo"></span><span class="ambar"></span><span class="verde"></span></div><p>0 · ${estado.parametros?.umbralSemaforoAmbar ?? '—'} · ${estado.parametros?.umbralSemaforoVerde ?? '—'} · 1,000 unidades</p></section>
        <section class="seccion"><h3>Activaciones del semáforo</h3><ul>${indicadores?.activacionesSemaforo.length ? indicadores.activacionesSemaforo.map(activacion => `<li>${fechaMinuto(corrida.primerDia, activacion.minuto)} · ${escapar(activacion.nombreAlmacen)}: ${etiquetaSemaforo(activacion.color)} (${activacion.disponible} unidades)</li>`).join('') : '<li>No se registraron activaciones.</li>'}</ul></section><div class="accionesDialogo"><button id="exportar">Descargar resultados</button><button id="nuevaCorrida" class="primario">Configurar nueva corrida</button></div>`);
    porId('exportar').onclick = () => {
        const enlace = document.createElement('a');
        const direccion = URL.createObjectURL(new Blob([JSON.stringify({ corrida, resultado, instantanea: estado.instantanea, parametrosVigentes: estado.parametros }, null, 2)], { type: 'application/json' }));
        enlace.href = direccion;
        enlace.download = `kindbox-${corrida.id}.json`;
        enlace.click();
        setTimeout(() => URL.revokeObjectURL(direccion), 1000);
    };
    porId('nuevaCorrida').onclick = () => { porId('dialogo').close(); abrirPanel('configuracion'); };
}
document.addEventListener('keydown', evento => {
    if (evento.key !== 'Escape' || porId('dialogo').open) return;
    if (mapa.elegida) mapa.elegir(null);
    else if (estado.panel) porId('cerrarPanel').click();
});
window.addEventListener('resize', () => { estado.consulta = window.innerWidth < 1024; pintarCabecera(); });
let consultando = false;
let ciclo = 0;
async function descubrir() {
    const idAnterior = estado.corrida?.id;
    const corridas = await solicitar('/simulaciones');
    if (estado.corrida?.id !== idAnterior) return;
    if (corridas.length && !estado.historico) recibirCabecera(corridas[0]);
}
async function refrescar() {
    if (consultando) return;
    consultando = true;
    try {
        if (ciclo % 5 === 0 || !estado.parametros) {
            const [parametros, almacenes] = await Promise.all([solicitar('/parametros'), solicitar('/almacenes')]);
            estado.parametros = parametros;
            estado.almacenes = almacenes;
            pintarRangos();
            if (!estado.corrida) pintarMapa();
        }
        if (!estado.historico && (!estado.corrida || ciclo % 5 === 0)) await descubrir();
        const id = estado.corrida?.id;
        if (id) {
            const detalle = await solicitar(`/simulaciones/${id}`);
            if (estado.corrida?.id !== id) return;
            estado.averias = detalle.averias;
            recibirCabecera(detalle.corrida);
            if (detalle.instantanea) recibirInstantanea(detalle.instantanea);
            if (detalle.resultado) { estado.resultado = detalle.resultado; finalizar(); }
            pintarCabecera();
            if (estado.panel === 'pedidos') await cargarPagina();
            if (ciclo % 3 === 0) {
                const primera = await solicitar(`/simulaciones/${id}/pedidos?tamano=500&soloActivos=true`);
                const filas = [...primera.filas];
                for (let pagina = 1; pagina < primera.totalPaginas; pagina++) {
                    const siguiente = await solicitar(`/simulaciones/${id}/pedidos?tamano=500&soloActivos=true&pagina=${pagina}`);
                    filas.push(...siguiente.filas);
                }
                if (estado.corrida?.id === id) { estado.pedidosMapa = filas; pintarMapa(); }
            }
        } else await solicitar('/salud');
        estado.ultimaRecepcion = new Date();
        if (canal.canal?.readyState === WebSocket.OPEN && estado.conexion !== 'Conexión Estable') cambiarConexion('Conexión Estable');
        pintarFrescura();
    } catch (error) {
        if (error.codigo === 404) {
            estado.corrida = null;
            estado.instantanea = null;
            estado.resultado = null;
            estado.pedidosMapa = [];
            estado.historico = false;
            pintarMapa();
            mostrarError(error);
        }
        if (error.codigo === 0) cambiarConexion('Reconectando...');
    } finally { ciclo++; consultando = false; }
}
async function iniciar() {
    abrirPanel('configuracion');
    try {
        const [salud, parametros, almacenes] = await Promise.all([solicitar('/salud'), solicitar('/parametros'), solicitar('/almacenes')]);
        estado.parametros = parametros;
        estado.almacenes = almacenes;
        pintarMapa();
        if (!salud.datosDisponibles) notificar('El servidor no encuentra el directorio de datos. Configure sus datos antes de comenzar.', true);
        abrirPanel('configuracion');
        await descubrir();
        await refrescar();
    } catch (error) { mostrarError(error); }
    canal.conectar();
    setInterval(() => { refrescar(); pintarCabecera(); }, 1000);
}
iniciar();
