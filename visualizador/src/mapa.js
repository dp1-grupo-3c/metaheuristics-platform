import { escapar, nombresTipo, nombresEstado, numero, duracion, fechaMinuto, etiquetaSemaforo } from './formato.js';
import { icono } from './iconos.js';

export class MapaCiudad {
    constructor(contenedor, seleccionar) {
        this.contenedor = contenedor;
        this.seleccionar = seleccionar;
        this.centroX = 35;
        this.centroY = 25;
        this.aumento = 1;
        this.capas = { unidades: true, rutas: true, pedidos: true, bloqueos: true };
        this.datos = { almacenes: [], unidades: [], bloqueosVigentes: [] };
        this.pedidos = [];
        this.marcadores = new Map();
        contenedor.innerHTML = `<svg class="reticula" aria-hidden="true"><g class="cuadricula"></g><g class="trazos"></g></svg><div class="marcadores"></div>
            <div class="mapaTitulo"><span class="sobreTitulo">PaqRap · Red de reparto</span><h1>Mapa de distribución</h1><span>70 × 50 km · Retícula de distribución</span></div>
            <div class="herramientasMapa"><button data-zoom="2" aria-label="Acercar mapa">+</button><button data-zoom="0.5" aria-label="Alejar mapa">−</button><button data-zoom="0" aria-label="Ver toda la ciudad">⌖</button></div>
            <div class="escalaMapa"><span id="escalaGrafica"></span><span id="escalaTexto"></span></div>
            <output class="coordenadas" aria-label="Coordenadas del mapa">(0,0)</output>
            <section class="tarjetaMapa" hidden aria-label="Detalle del elemento"><button class="cerrarTarjeta" aria-label="Cerrar detalle">×</button><div class="detalleMapa"></div></section>`;
        this.svg = contenedor.querySelector('svg');
        this.capaMarcadores = contenedor.querySelector('.marcadores');
        this.tarjeta = contenedor.querySelector('.tarjetaMapa');
        contenedor.querySelector('.cerrarTarjeta').onclick = () => this.elegir(null);
        contenedor.querySelectorAll('[data-zoom]').forEach(boton => boton.onclick = () => this.zoom(Number(boton.dataset.zoom)));
        contenedor.addEventListener('wheel', evento => {
            if (evento.target.closest('.tarjetaMapa')) return;
            evento.preventDefault();
            this.zoom(evento.deltaY < 0 ? 2 : 0.5);
        }, { passive: false });
        this.punteros = new Map();
        contenedor.addEventListener('pointerdown', evento => {
            if (evento.target.closest('button, .tarjetaMapa')) return;
            this.punteros.set(evento.pointerId, { x: evento.clientX, y: evento.clientY });
            this.arrastre = { x: evento.clientX, y: evento.clientY, centroX: this.centroX, centroY: this.centroY };
            this.movido = false;
            contenedor.setPointerCapture(evento.pointerId);
        });
        contenedor.addEventListener('pointermove', evento => {
            const limites = contenedor.getBoundingClientRect();
            const x = Math.round((evento.clientX - limites.left - this.ancho / 2) / this.escala + this.centroX);
            const y = Math.round((this.alto / 2 + 30 - evento.clientY + limites.top) / this.escala + this.centroY);
            contenedor.querySelector('.coordenadas').textContent = `(${Math.max(0, Math.min(70, x))},${Math.max(0, Math.min(50, y))})`;
            if (!this.punteros.has(evento.pointerId)) return;
            this.punteros.set(evento.pointerId, { x: evento.clientX, y: evento.clientY });
            if (this.punteros.size === 2) {
                const [primero, segundo] = [...this.punteros.values()];
                const distancia = Math.hypot(primero.x - segundo.x, primero.y - segundo.y);
                if (this.distanciaPellizco && Math.abs(distancia / this.distanciaPellizco - 1) > 0.35) {
                    this.zoom(distancia > this.distanciaPellizco ? 2 : 0.5);
                    this.distanciaPellizco = distancia;
                }
                this.distanciaPellizco ||= distancia;
                this.movido = true;
            } else if (this.arrastre) {
                const desplazamientoX = evento.clientX - this.arrastre.x;
                const desplazamientoY = evento.clientY - this.arrastre.y;
                if (Math.abs(desplazamientoX) + Math.abs(desplazamientoY) > 4) this.movido = true;
                this.centroX = Math.max(0, Math.min(70, this.arrastre.centroX - desplazamientoX / this.escala));
                this.centroY = Math.max(0, Math.min(50, this.arrastre.centroY + desplazamientoY / this.escala));
                this.dibujar();
            }
        });
        const soltar = evento => {
            if (!this.punteros.has(evento.pointerId)) return;
            this.punteros.delete(evento.pointerId);
            this.distanciaPellizco = null;
            this.arrastre = null;
            if (!this.movido) this.elegir(null);
        };
        contenedor.addEventListener('pointerup', soltar);
        contenedor.addEventListener('pointercancel', soltar);
        contenedor.addEventListener('keydown', evento => {
            if (evento.target !== contenedor) return;
            const teclas = { ArrowLeft: [-5, 0], ArrowRight: [5, 0], ArrowUp: [0, 5], ArrowDown: [0, -5] };
            if (teclas[evento.key]) {
                evento.preventDefault();
                this.centroX = Math.max(0, Math.min(70, this.centroX + teclas[evento.key][0]));
                this.centroY = Math.max(0, Math.min(50, this.centroY + teclas[evento.key][1]));
                this.dibujar();
            } else if (['+', '=', '-', 'Home'].includes(evento.key)) {
                evento.preventDefault();
                this.zoom(evento.key === 'Home' ? 0 : evento.key === '-' ? 0.5 : 2);
            }
        });
        new ResizeObserver(() => this.dibujar()).observe(contenedor);
    }
    zoom(factor) {
        if (!factor) { this.aumento = 1; this.centroX = 35; this.centroY = 25; }
        else this.aumento = Math.max(1, Math.min(64 / this.escalaBase, this.aumento * factor));
        this.dibujar();
    }
    posicion(x, y) {
        return [this.ancho / 2 + (x - this.centroX) * this.escala, this.alto / 2 + 30 - (y - this.centroY) * this.escala];
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
        this.centroX = pedido.x;
        this.centroY = pedido.y;
        this.elegir(`pedido-${pedido.id}`);
    }
    camino(nodos, clase, color, opacidad = 1) {
        if (!nodos || nodos.length < 4) return '';
        const puntos = [];
        for (let indice = 0; indice < nodos.length; indice += 2) puntos.push(this.posicion(nodos[indice], nodos[indice + 1]).join(','));
        return `<polyline points="${puntos.join(' ')}" class="${clase}" style="--colorTrazo:var(--${color});opacity:${opacidad}"/>`;
    }
    dibujar() {
        this.ancho = this.contenedor.clientWidth;
        this.alto = this.contenedor.clientHeight;
        this.escalaBase = Math.max(1, Math.min((this.ancho - 80) / 70, (this.alto - 220) / 50));
        this.escala = Math.min(64, this.escalaBase * this.aumento);
        this.svg.setAttribute('viewBox', `0 0 ${this.ancho} ${this.alto}`);
        let lineas = '';
        for (let x = 0; x <= 70; x++) {
            const [px, arriba] = this.posicion(x, 50);
            const [, abajo] = this.posicion(x, 0);
            lineas += `<path d="M${px} ${arriba}V${abajo}" class="${x % 10 ? '' : 'principal'}"/>`;
            if (x % 10 === 0) lineas += `<text x="${px}" y="${abajo + 23}">${x}</text>`;
        }
        for (let y = 0; y <= 50; y++) {
            const [izquierda, py] = this.posicion(0, y);
            const [derecha] = this.posicion(70, y);
            lineas += `<path d="M${izquierda} ${py}H${derecha}" class="${y % 10 ? '' : 'principal'}"/>`;
            if (y % 10 === 0) lineas += `<text x="${izquierda - 22}" y="${py + 4}">${y}</text>`;
        }
        this.svg.querySelector('.cuadricula').innerHTML = lineas;
        const unidades = this.datos.unidades || [];
        let trazos = this.capas.bloqueos ? (this.datos.bloqueosVigentes || []).map(bloqueo => this.camino(bloqueo.nodos, 'bloqueo', 'rojo')).join('') : '';
        if (this.capas.rutas) for (const unidad of unidades) {
            const opacidad = !this.elegida || this.elegida === unidad.codigo ? 1 : 0.5;
            trazos += this.camino(unidad.caminoPendiente, 'ruta pendiente', unidad.tipo.toLowerCase(), opacidad);
            trazos += this.camino(unidad.caminoRecorrido, 'ruta recorrida', unidad.tipo.toLowerCase(), opacidad);
            if (this.elegida === unidad.codigo && unidad.destinoX >= 0) {
                const [x, y] = this.posicion(unidad.destinoX, unidad.destinoY);
                trazos += `<circle cx="${x}" cy="${y}" r="10" fill="var(--${unidad.tipo.toLowerCase()})"/>`;
            }
        }
        this.svg.querySelector('.trazos').innerHTML = trazos;
        const elementos = [];
        if (this.capas.pedidos) for (const pedido of this.pedidos) elementos.push({ clave: `pedido-${pedido.id}`, x: pedido.x, y: pedido.y, color: pedido.semaforo.toLowerCase(), simbolo: 'pedidos', etiqueta: `Pedido ${pedido.id} en (${pedido.x},${pedido.y})`, dato: pedido, clase: 'pedido', texto: String(pedido.id) });
        for (const almacen of this.datos.almacenes || []) elementos.push({ clave: `almacen-${almacen.id}`, ...almacen, color: almacen.central ? 'marca' : almacen.color.toLowerCase(), simbolo: almacen.central ? 'casa' : 'caja', etiqueta: `${almacen.nombre} en (${almacen.x},${almacen.y})`, dato: almacen, clase: 'almacen', texto: almacen.central ? 'Central' : `A${almacen.id}` });
        if (this.capas.unidades) for (const unidad of unidades) elementos.push({ clave: unidad.codigo, ...unidad, color: unidad.tipoAveria ? 'rojo' : unidad.tipo.toLowerCase(), simbolo: unidad.tipo.toLowerCase(), etiqueta: `${nombresTipo[unidad.tipo]} ${unidad.codigo} en (${unidad.x},${unidad.y})`, dato: unidad, clase: 'unidad', texto: unidad.codigo });
        if (this.capas.bloqueos) (this.datos.bloqueosVigentes || []).forEach((bloqueo, indice) => elementos.push({ clave: `bloqueo-${indice}`, x: bloqueo.nodos[0], y: bloqueo.nodos[1], color: 'rojo', simbolo: 'bloqueo', etiqueta: `Vía bloqueada hasta el minuto ${bloqueo.minutoFin}`, dato: bloqueo, clase: 'incidencia', texto: '×' }));
        const presentes = new Set();
        for (const elemento of elementos) {
            presentes.add(elemento.clave);
            let boton = this.marcadores.get(elemento.clave);
            if (!boton) {
                boton = document.createElement('button');
                boton.type = 'button';
                boton.onclick = () => this.elegir(elemento.clave);
                this.marcadores.set(elemento.clave, boton);
                this.capaMarcadores.append(boton);
            }
            const [x, y] = this.posicion(elemento.x, elemento.y);
            boton.className = `marcador ${elemento.clase}${this.elegida === elemento.clave ? ' elegido' : ''}`;
            boton.style.cssText = `left:${x}px;top:${y}px;--colorMarcador:var(--${elemento.color})`;
            boton.setAttribute('aria-label', elemento.etiqueta);
            boton.title = elemento.etiqueta;
            const contenido = `${icono(elemento.simbolo)}<span class="rotulo">${escapar(elemento.texto)}</span>${elemento.tipoAveria ? '<b class="insignia">!</b>' : ''}${elemento.clase === 'almacen' && !elemento.central ? `<small class="nivel">${{VERDE:'▰▰▰',AMBAR:'▰▰',ROJO:'!'}[elemento.dato.color]}</small>` : ''}`;
            if (boton.innerHTML !== contenido) boton.innerHTML = contenido;
        }
        for (const [clave, boton] of this.marcadores) if (!presentes.has(clave)) { boton.remove(); this.marcadores.delete(clave); }
        const elegido = elementos.find(elemento => elemento.clave === this.elegida);
        this.tarjeta.hidden = !elegido;
        if (elegido) this.mostrarDetalle(elegido);
        this.contenedor.querySelector('#escalaGrafica').style.width = `${this.escala * 5}px`;
        this.contenedor.querySelector('#escalaTexto').textContent = '5 km';
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
