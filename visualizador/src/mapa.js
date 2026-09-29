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
        contenedor.innerHTML = `<canvas class="lienzoCiudad" aria-label="Plano interactivo de la ciudad"></canvas><div class="marcadoresCiudad"></div>
            <div class="mapaTitulo"><span class="sobreTitulo">PaqRap · Red de reparto</span><h1>Mapa de distribución</h1><span>70 × 50 km · Retícula de distribución</span></div>
            <div class="herramientasMapa"><button data-zoom="2" aria-label="Acercar mapa">+</button><button data-zoom="0.5" aria-label="Alejar mapa">−</button><button data-zoom="0" aria-label="Ver toda la ciudad">⌖</button></div>
            <div class="escalaMapa"><span id="escalaGrafica"></span><span id="escalaTexto">5 km</span></div>
            <output class="coordenadas" aria-label="Coordenadas del mapa">(0,0)</output>
            <section class="tarjetaMapa" hidden aria-label="Detalle del elemento"><button class="cerrarTarjeta" aria-label="Cerrar detalle">×</button><div class="detalleMapa"></div></section>`;
        this.tarjeta = contenedor.querySelector('.tarjetaMapa');
        this.lienzo = contenedor.querySelector('canvas');
        this.contexto = this.lienzo.getContext('2d');
        this.eventos = new AbortController();
        const opciones = { signal: this.eventos.signal };
        this.centroX = 35;
        this.centroY = 25;
        this.factor = 1;
        this.punteros = new Map();
        contenedor.querySelector('.cerrarTarjeta').onclick = () => this.elegir(null);
        contenedor.querySelectorAll('[data-zoom]').forEach(boton => boton.onclick = () => this.zoom(Number(boton.dataset.zoom)));
        contenedor.addEventListener('pointerdown', evento => {
            const marcador = evento.target.closest('.marcador');
            if (evento.target !== this.lienzo && !marcador) return;
            this.seleccionInicio = marcador?.dataset.clave || null;
            contenedor.setPointerCapture(evento.pointerId);
            this.punteros.set(evento.pointerId, [evento.clientX, evento.clientY]);
            this.arrastrado = false;
        }, opciones);
        contenedor.addEventListener('pointermove', evento => {
            const anterior = this.punteros.get(evento.pointerId);
            if (anterior) {
                const otro = [...this.punteros.entries()].find(([clave]) => clave !== evento.pointerId)?.[1];
                if (otro) {
                    const antes = Math.hypot(anterior[0] - otro[0], anterior[1] - otro[1]);
                    const ahora = Math.hypot(evento.clientX - otro[0], evento.clientY - otro[1]);
                    if (antes > 0) this.factor = Math.max(1, Math.min(32, this.factor * ahora / antes));
                } else {
                    this.centroX -= (evento.clientX - anterior[0]) / this.escala;
                    this.centroY += (evento.clientY - anterior[1]) / this.escala;
                }
                if (Math.hypot(evento.clientX - anterior[0], evento.clientY - anterior[1]) > 1) this.arrastrado = true;
                this.punteros.set(evento.pointerId, [evento.clientX, evento.clientY]);
                this.solicitarDibujo();
            }
            const limites = this.lienzo.getBoundingClientRect();
            const x = Math.round(this.centroX + (evento.clientX - limites.left - this.ancho / 2) / this.escala);
            const y = Math.round(this.centroY - (evento.clientY - limites.top - this.alto / 2) / this.escala);
            contenedor.querySelector('.coordenadas').textContent = `(${Math.max(0, Math.min(70, x))},${Math.max(0, Math.min(50, y))})`;
        }, opciones);
        for (const nombre of ['pointerup', 'pointercancel']) contenedor.addEventListener(nombre, evento => {
            if (!this.punteros.has(evento.pointerId)) return;
            this.punteros.delete(evento.pointerId);
            if (nombre === 'pointerup' && !this.arrastrado) this.elegir(this.seleccionInicio);
        }, opciones);
        contenedor.addEventListener('wheel', evento => {
            evento.preventDefault();
            this.zoom(Math.exp(-evento.deltaY * 0.002));
        }, { ...opciones, passive: false });
        contenedor.addEventListener('keydown', evento => {
            if (evento.target !== contenedor) return;
            const teclas = { ArrowLeft: [-80, 0], ArrowRight: [80, 0], ArrowUp: [0, -80], ArrowDown: [0, 80] };
            if (teclas[evento.key]) {
                evento.preventDefault();
                this.centroX += teclas[evento.key][0] / this.escala;
                this.centroY -= teclas[evento.key][1] / this.escala;
                this.solicitarDibujo();
            } else if (['+', '=', '-', 'Home'].includes(evento.key)) {
                evento.preventDefault();
                this.zoom(evento.key === 'Home' ? 0 : evento.key === '-' ? 0.5 : 2);
            }
        }, opciones);
        this.observadorTamano = new ResizeObserver(() => this.solicitarDibujo());
        this.observadorTamano.observe(contenedor);
        this.solicitarDibujo();
    }
    encuadrar() {
        this.factor = 1;
        this.centroX = 35;
        this.centroY = 25;
        this.solicitarDibujo();
    }
    zoom(factor) {
        if (!factor) this.encuadrar();
        else { this.factor = Math.max(1, Math.min(32, this.factor * factor)); this.solicitarDibujo(); }
    }
    posicion(x, y) {
        return [this.ancho / 2 + (x - this.centroX) * this.escala, this.alto / 2 - (y - this.centroY) * this.escala];
    }
    solicitarDibujo() {
        if (!this.cuadro) this.cuadro = requestAnimationFrame(() => { this.cuadro = null; this.dibujar(); });
    }
    prepararLienzo() {
        this.ancho = this.contenedor.clientWidth;
        this.alto = this.contenedor.clientHeight;
        this.escala = Math.max(1, Math.min((this.ancho - 60) / 70, (this.alto - 150) / 50)) * this.factor;
        this.centroX = Math.max(0, Math.min(70, this.centroX));
        this.centroY = Math.max(0, Math.min(50, this.centroY));
        const densidad = Math.min(window.devicePixelRatio || 1, 2);
        this.lienzo.width = this.ancho * densidad;
        this.lienzo.height = this.alto * densidad;
        this.contexto.setTransform(densidad, 0, 0, densidad, 0, 0);
        this.colores = getComputedStyle(this.contenedor);
        const pincel = this.contexto;
        pincel.strokeStyle = '#616161';
        pincel.fillStyle = this.colores.getPropertyValue('--etiqueta').trim() || '#ccc';
        pincel.font = '12px sans-serif';
        for (let x = 0; x <= 70; x++) {
            pincel.lineWidth = x % 10 ? 0.5 : 1;
            pincel.beginPath(); pincel.moveTo(...this.posicion(x, 0)); pincel.lineTo(...this.posicion(x, 50)); pincel.stroke();
            if (x % 10 === 0) { const [px, py] = this.posicion(x, 0); pincel.fillText(String(x), px - 5, py + 18); }
        }
        for (let y = 0; y <= 50; y++) {
            pincel.lineWidth = y % 10 ? 0.5 : 1;
            pincel.beginPath(); pincel.moveTo(...this.posicion(0, y)); pincel.lineTo(...this.posicion(70, y)); pincel.stroke();
            if (y % 10 === 0) { const [px, py] = this.posicion(0, y); pincel.fillText(String(y), px - 25, py + 4); }
        }
    }
    actualizar(datos, pedidos, parametros, corrida) {
        this.datos = datos;
        this.pedidos = pedidos;
        this.parametros = parametros;
        this.corrida = corrida;
        this.solicitarDibujo();
    }
    elegir(clave) {
        this.elegida = clave;
        this.seleccionar(clave);
        this.solicitarDibujo();
    }
    centrarPedido(pedido) {
        this.centroX = pedido.x;
        this.centroY = pedido.y;
        this.elegir(`pedido-${pedido.id}`);
    }
    camino(nodos, clase, color, opacidad = 1) {
        if (!nodos || nodos.length < 4) return;
        const pincel = this.contexto;
        pincel.strokeStyle = this.colores.getPropertyValue(`--${color}`).trim() || '#888';
        pincel.globalAlpha = opacidad;
        pincel.lineWidth = clase === 'bloqueo' ? 8 : 4;
        pincel.setLineDash(clase.includes('pendiente') ? [14, 10] : []);
        pincel.beginPath();
        for (let indice = 0; indice < nodos.length; indice += 2) {
            const punto = this.posicion(nodos[indice], nodos[indice + 1]);
            if (indice === 0) pincel.moveTo(...punto); else pincel.lineTo(...punto);
        }
        pincel.stroke();
        pincel.globalAlpha = 1;
        pincel.setLineDash([]);
    }
    dibujar() {
        this.prepararLienzo();
        const unidades = this.datos.unidades || [];
        this.lienzo.dataset.rutas = String(this.capas.rutas ? unidades.filter(unidad => unidad.caminoPendiente?.length >= 4 || unidad.caminoRecorrido?.length >= 4).length : 0);
        this.lienzo.dataset.bloqueos = String(this.capas.bloqueos ? (this.datos.bloqueosVigentes || []).length : 0);
        if (this.capas.bloqueos) for (const bloqueo of this.datos.bloqueosVigentes || []) this.camino(bloqueo.nodos, 'bloqueo', 'rojo');
        if (this.capas.rutas) for (const unidad of unidades) {
            const opacidad = !this.elegida || this.elegida === unidad.codigo ? 1 : 0.5;
            this.camino(unidad.caminoPendiente, 'ruta pendiente', unidad.tipo.toLowerCase(), opacidad);
            this.camino(unidad.caminoRecorrido, 'ruta recorrida', unidad.tipo.toLowerCase(), opacidad);
            if (this.elegida === unidad.codigo && unidad.destinoX >= 0) {
                this.contexto.beginPath();
                this.contexto.arc(...this.posicion(unidad.destinoX, unidad.destinoY), 8, 0, Math.PI * 2);
                this.contexto.stroke();
            }
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
                this.contenedor.querySelector('.marcadoresCiudad').append(boton);
                registro = { boton };
                this.marcadores.set(elemento.clave, registro);
            }
            const { boton } = registro;
            const [x, y] = this.posicion(elemento.x, elemento.y);
            boton.style.left = `${x}px`;
            boton.style.top = `${y}px`;
            boton.style.zIndex = this.elegida === elemento.clave ? 100 : elemento.clase === 'unidad' ? 10 : 1;
            boton.dataset.clave = elemento.clave;
            boton.className = `marcador ${elemento.clase}${this.elegida === elemento.clave ? ' elegido' : ''}`;
            boton.style.setProperty('--colorMarcador', `var(--${elemento.color})`);
            boton.setAttribute('aria-label', elemento.etiqueta);
            boton.title = elemento.etiqueta;
            const contenido = `${icono(elemento.simbolo)}<span class="rotulo">${escapar(elemento.texto)}</span>${elemento.tipoAveria ? '<b class="insignia">!</b>' : ''}${elemento.clase === 'almacen' && !elemento.central ? `<small class="nivel">${{VERDE:'▰▰▰',AMBAR:'▰▰',ROJO:'!'}[elemento.dato.color]}</small>` : ''}`;
            if (registro.contenido !== contenido) { boton.innerHTML = contenido; registro.contenido = contenido; }
        }
        for (const [clave, registro] of this.marcadores) if (!presentes.has(clave)) { registro.boton.remove(); this.marcadores.delete(clave); }
        this.elementos = elementos;
        this.actualizarSuperposiciones();
    }
    actualizarSuperposiciones() {
        this.ancho = this.contenedor.clientWidth;
        this.alto = this.contenedor.clientHeight;
        const elegido = this.elementos?.find(elemento => elemento.clave === this.elegida);
        this.tarjeta.hidden = !elegido;
        if (elegido) this.mostrarDetalle(elegido);
        const longitud = 5 * this.escala;
        this.contenedor.querySelector('#escalaGrafica').style.width = `${longitud}px`;
    }
    destruir() {
        this.observadorTamano.disconnect();
        this.eventos.abort();
        cancelAnimationFrame(this.cuadro);
        this.contenedor.replaceChildren();
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
