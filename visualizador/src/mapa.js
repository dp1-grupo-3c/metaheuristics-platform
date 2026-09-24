import * as plano from 'leaflet';
import { escapar, nombresTipo, nombresEstado, numero, duracion, fechaMinuto, etiquetaSemaforo } from './formato.js';
import { icono } from './iconos.js';

export class MapaCiudad {
    constructor(contenedor, seleccionar) {
        this.contenedor = contenedor;
        this.seleccionar = seleccionar;
        this.capas = { unidades: true, rutas: true, pedidos: true, bloqueos: true };
        this.datos = { almacenes: [], unidades: [], bloqueosVigentes: [] };
        this.pedidos = [];
        this.marcadores = new Map();
        contenedor.innerHTML = `<div class="lienzoLeaflet"></div>
            <div class="mapaTitulo"><span class="sobreTitulo">PaqRap · Red de reparto</span><h1>Mapa de distribución</h1><span>70 × 50 km · Retícula de distribución</span></div>
            <div class="herramientasMapa"><button data-zoom="2" aria-label="Acercar mapa">+</button><button data-zoom="0.5" aria-label="Alejar mapa">−</button><button data-zoom="0" aria-label="Ver toda la ciudad">⌖</button></div>
            <div class="escalaMapa"><span id="escalaGrafica"></span><span id="escalaTexto">5 km</span></div>
            <output class="coordenadas" aria-label="Coordenadas del mapa">(0,0)</output>
            <section class="tarjetaMapa" hidden aria-label="Detalle del elemento"><button class="cerrarTarjeta" aria-label="Cerrar detalle">×</button><div class="detalleMapa"></div></section>`;
        this.tarjeta = contenedor.querySelector('.tarjetaMapa');
        this.limites = plano.latLngBounds([[0, 0], [50, 70]]);
        this.mapa = plano.map(contenedor.querySelector('.lienzoLeaflet'), {
            crs: plano.CRS.Simple, minZoom: -2, maxZoom: 6, zoomSnap: 0,
            zoomControl: false, attributionControl: false, keyboard: false,
            zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false,
        });
        this.cuadricula = plano.layerGroup().addTo(this.mapa);
        this.trazos = plano.layerGroup().addTo(this.mapa);
        this.crearCuadricula();
        this.encuadrar();
        contenedor.querySelector('.cerrarTarjeta').onclick = () => this.elegir(null);
        contenedor.querySelectorAll('[data-zoom]').forEach(boton => boton.onclick = () => this.zoom(Number(boton.dataset.zoom)));
        this.mapa.on('click', () => this.elegir(null));
        this.mapa.on('mousemove', evento => {
            const x = Math.max(0, Math.min(70, Math.round(evento.latlng.lng)));
            const y = Math.max(0, Math.min(50, Math.round(evento.latlng.lat)));
            contenedor.querySelector('.coordenadas').textContent = `(${x},${y})`;
        });
        this.mapa.on('move zoom', () => this.actualizarSuperposiciones());
        this.mapa.on('moveend', () => {
            const centro = this.mapa.getCenter();
            const x = Math.max(0, Math.min(70, centro.lng));
            const y = Math.max(0, Math.min(50, centro.lat));
            if (centro.lng !== x || centro.lat !== y) this.mapa.panTo([y, x], { animate: false });
        });
        this.eventos = new AbortController();
        contenedor.addEventListener('keydown', evento => {
            if (evento.target !== contenedor && evento.target !== this.mapa.getContainer()) return;
            const teclas = { ArrowLeft: [-80, 0], ArrowRight: [80, 0], ArrowUp: [0, -80], ArrowDown: [0, 80] };
            if (teclas[evento.key]) { evento.preventDefault(); this.mapa.panBy(teclas[evento.key], { animate: false }); }
            else if (['+', '=', '-', 'Home'].includes(evento.key)) {
                evento.preventDefault();
                this.zoom(evento.key === 'Home' ? 0 : evento.key === '-' ? 0.5 : 2);
            }
        }, { signal: this.eventos.signal });
        this.observadorTamano = new ResizeObserver(() => {
            const encuadrado = this.mapa.getZoom() <= this.mapa.getMinZoom() + 0.01;
            this.mapa.invalidateSize({ pan: false });
            if (encuadrado) this.encuadrar();
            this.actualizarSuperposiciones();
        });
        this.observadorTamano.observe(contenedor);
    }
    crearCuadricula() {
        const opciones = { color: '#616161', opacity: 1, weight: 0.5, interactive: false };
        for (let x = 0; x <= 70; x++) {
            plano.polyline([[0, x], [50, x]], { ...opciones, weight: x % 10 ? 0.5 : 1 }).addTo(this.cuadricula);
            if (x % 10 === 0) this.rotulo([0, x], x, 'ejeHorizontal');
        }
        for (let y = 0; y <= 50; y++) {
            plano.polyline([[y, 0], [y, 70]], { ...opciones, weight: y % 10 ? 0.5 : 1 }).addTo(this.cuadricula);
            if (y % 10 === 0) this.rotulo([y, 0], y, 'ejeVertical');
        }
    }
    rotulo(coordenada, texto, clase) {
        plano.marker(coordenada, { interactive: false, keyboard: false, icon: plano.divIcon({ className: `rotuloEje ${clase}`, html: String(texto), iconSize: [30, 20], iconAnchor: clase === 'ejeHorizontal' ? [15, -8] : [36, 10] }) }).addTo(this.cuadricula);
    }
    encuadrar() {
        this.mapa.setMinZoom(-5);
        this.mapa.fitBounds(this.limites, { paddingTopLeft: [50, 135], paddingBottomRight: [50, 50], animate: false });
        this.mapa.setMinZoom(this.mapa.getZoom());
    }
    zoom(factor) {
        if (!factor) this.encuadrar();
        else this.mapa.setZoom(this.mapa.getZoom() + Math.log2(factor));
    }
    posicion(x, y) {
        const punto = this.mapa.latLngToContainerPoint([y, x]);
        return [punto.x, punto.y];
    }
    actualizar(datos, pedidos, parametros, corrida) {
        this.datos = datos;
        this.pedidos = pedidos;
        this.parametros = parametros;
        this.corrida = corrida;
        this.dibujar();
    }
    elegir(clave) {
        this.elegida = clave;
        this.seleccionar(clave);
        this.dibujar();
    }
    centrarPedido(pedido) {
        this.mapa.panTo([pedido.y, pedido.x], { animate: false });
        this.elegir(`pedido-${pedido.id}`);
    }
    camino(nodos, clase, color, opacidad = 1) {
        if (!nodos || nodos.length < 4) return;
        const puntos = [];
        for (let indice = 0; indice < nodos.length; indice += 2) puntos.push([nodos[indice + 1], nodos[indice]]);
        plano.polyline(puntos, {
            color: `var(--${color})`, opacity: opacidad, weight: clase === 'bloqueo' ? 8 : 6,
            dashArray: clase.includes('pendiente') ? '14 10' : null,
            className: clase, interactive: false,
        }).addTo(this.trazos);
    }
    dibujar() {
        this.trazos.clearLayers();
        const unidades = this.datos.unidades || [];
        if (this.capas.bloqueos) for (const bloqueo of this.datos.bloqueosVigentes || []) this.camino(bloqueo.nodos, 'bloqueo', 'rojo');
        if (this.capas.rutas) for (const unidad of unidades) {
            const opacidad = !this.elegida || this.elegida === unidad.codigo ? 1 : 0.5;
            this.camino(unidad.caminoPendiente, 'ruta pendiente', unidad.tipo.toLowerCase(), opacidad);
            this.camino(unidad.caminoRecorrido, 'ruta recorrida', unidad.tipo.toLowerCase(), opacidad);
            if (this.elegida === unidad.codigo && unidad.destinoX >= 0) plano.circleMarker([unidad.destinoY, unidad.destinoX], { radius: 10, color: `var(--${unidad.tipo.toLowerCase()})`, fillOpacity: 1, interactive: false }).addTo(this.trazos);
        }
        const elementos = [];
        if (this.capas.pedidos) for (const pedido of this.pedidos) elementos.push({ clave: `pedido-${pedido.id}`, x: pedido.x, y: pedido.y, color: pedido.semaforo.toLowerCase(), simbolo: 'pedidos', etiqueta: `Pedido ${pedido.id} en (${pedido.x},${pedido.y})`, dato: pedido, clase: 'pedido', texto: String(pedido.id) });
        for (const almacen of this.datos.almacenes || []) elementos.push({ clave: `almacen-${almacen.id}`, ...almacen, color: almacen.central ? 'marca' : almacen.color.toLowerCase(), simbolo: almacen.central ? 'casa' : 'caja', etiqueta: `${almacen.nombre} en (${almacen.x},${almacen.y})`, dato: almacen, clase: 'almacen', texto: almacen.central ? 'Central' : `A${almacen.id}` });
        if (this.capas.unidades) for (const unidad of unidades) elementos.push({ clave: unidad.codigo, ...unidad, color: unidad.tipoAveria ? 'rojo' : unidad.tipo.toLowerCase(), simbolo: unidad.tipo.toLowerCase(), etiqueta: `${nombresTipo[unidad.tipo]} ${unidad.codigo} en (${unidad.x},${unidad.y})`, dato: unidad, clase: 'unidad', texto: unidad.codigo });
        if (this.capas.bloqueos) (this.datos.bloqueosVigentes || []).forEach((bloqueo, indice) => elementos.push({ clave: `bloqueo-${indice}`, x: bloqueo.nodos[0], y: bloqueo.nodos[1], color: 'rojo', simbolo: 'bloqueo', etiqueta: `Vía bloqueada hasta el minuto ${bloqueo.minutoFin}`, dato: bloqueo, clase: 'incidencia', texto: '×' }));
        const presentes = new Set();
        for (const elemento of elementos) {
            presentes.add(elemento.clave);
            let registro = this.marcadores.get(elemento.clave);
            if (!registro) {
                const boton = document.createElement('button');
                boton.type = 'button';
                boton.onclick = evento => { evento.stopPropagation(); this.elegir(elemento.clave); };
                const capa = plano.marker([elemento.y, elemento.x], { keyboard: false, icon: plano.divIcon({ html: boton, className: 'envolturaMarcador', iconSize: [0, 0], iconAnchor: [0, 0] }) }).addTo(this.mapa);
                registro = { boton, capa };
                this.marcadores.set(elemento.clave, registro);
            }
            const { boton, capa } = registro;
            capa.setLatLng([elemento.y, elemento.x]);
            capa.setZIndexOffset(this.elegida === elemento.clave ? 10000 : elemento.clase === 'unidad' ? 1000 : 0);
            boton.className = `marcador ${elemento.clase}${this.elegida === elemento.clave ? ' elegido' : ''}`;
            boton.style.setProperty('--colorMarcador', `var(--${elemento.color})`);
            boton.setAttribute('aria-label', elemento.etiqueta);
            boton.title = elemento.etiqueta;
            const contenido = `${icono(elemento.simbolo)}<span class="rotulo">${escapar(elemento.texto)}</span>${elemento.tipoAveria ? '<b class="insignia">!</b>' : ''}${elemento.clase === 'almacen' && !elemento.central ? `<small class="nivel">${{VERDE:'▰▰▰',AMBAR:'▰▰',ROJO:'!'}[elemento.dato.color]}</small>` : ''}`;
            if (boton.innerHTML !== contenido) boton.innerHTML = contenido;
        }
        for (const [clave, registro] of this.marcadores) if (!presentes.has(clave)) { registro.capa.remove(); this.marcadores.delete(clave); }
        this.elementos = elementos;
        this.actualizarSuperposiciones();
    }
    actualizarSuperposiciones() {
        this.ancho = this.contenedor.clientWidth;
        this.alto = this.contenedor.clientHeight;
        const elegido = this.elementos?.find(elemento => elemento.clave === this.elegida);
        this.tarjeta.hidden = !elegido;
        if (elegido) this.mostrarDetalle(elegido);
        const longitud = 5 * 2 ** this.mapa.getZoom();
        this.contenedor.querySelector('#escalaGrafica').style.width = `${longitud}px`;
    }
    destruir() {
        this.observadorTamano.disconnect();
        this.eventos.abort();
        this.mapa.remove();
    }
    mostrarDetalle(elemento) {
        const dato = elemento.dato;
        let contenido = `<h3>${escapar(elemento.etiqueta)}</h3>`;
        if (elemento.clase === 'unidad') {
            contenido += `<p>${escapar(nombresEstado[dato.estado] || dato.estado)}${dato.tipoAveria ? ` · Avería tipo ${dato.tipoAveria}` : ''}</p>
                <dl><dt>Destino</dt><dd>${dato.destinoX < 0 ? 'Sin ruta asignada' : `(${dato.destinoX},${dato.destinoY})`}</dd><dt>Llegada estimada</dt><dd>${dato.destinoX < 0 ? '—' : `${fechaMinuto(this.corrida.primerDia, this.datos.minutoSimulado + dato.minutosHastaDestino)} (en ${duracion(dato.minutosHastaDestino * 60)})`}</dd><dt>Carga a bordo</dt><dd>${dato.cargaABordo} / ${dato.capacidad} paquetes</dd></dl>
                <h4>Pedidos a bordo</h4><table><thead><tr><th>Pedido</th><th>A bordo</th><th>Total</th></tr></thead><tbody>${dato.pedidosABordo.map(pedido => `<tr><td>${pedido.idPedido}</td><td>${pedido.enLaUnidad}</td><td>${pedido.totalDelPedido}</td></tr>`).join('')}</tbody></table>${dato.pedidosABordo.length ? '' : '<p>Sin pedidos a bordo.</p>'}`;
        } else if (elemento.clase === 'almacen') {
            contenido += `<p>${dato.central ? 'Inventario ilimitado' : `${numero(dato.disponible)} / ${numero(dato.capacidad)} unidades · ${numero(dato.disponible / dato.capacidad * 100)} %`}</p>${dato.central ? '' : `<p>${etiquetaSemaforo(dato.color)}</p><p>Crítico &lt; ${this.parametros?.umbralSemaforoAmbar ?? '—'} · Saludable ≥ ${this.parametros?.umbralSemaforoVerde ?? '—'} unidades</p>`}`;
        } else if (elemento.clase === 'pedido') {
            contenido += `<p>Cliente ${escapar(dato.cliente)}</p><p>${dato.pendientes} de ${dato.paquetes} paquetes pendientes</p><p>Plazo: ${dato.plazoHoras} h · Holgura: ${dato.holguraMinutos} min</p><p>${dato.holguraMinutos < 0 ? 'Vencido' : etiquetaSemaforo(dato.semaforo)}</p>`;
        } else contenido += `<p>Desde ${fechaMinuto(this.corrida.primerDia, dato.minutoInicio)}</p><p>Hasta ${fechaMinuto(this.corrida.primerDia, dato.minutoFin)} (hora simulada)</p>`;
        this.tarjeta.querySelector('.detalleMapa').innerHTML = contenido;
        const [x, y] = this.posicion(elemento.x, elemento.y);
        this.tarjeta.style.left = `${Math.max(8, Math.min(this.ancho - 264, x + 32))}px`;
        this.tarjeta.style.top = `${Math.max(8, Math.min(this.alto - this.tarjeta.offsetHeight - 8, y - 100))}px`;
    }
}
