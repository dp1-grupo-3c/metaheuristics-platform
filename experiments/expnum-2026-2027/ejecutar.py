#!/usr/bin/env python3
"""Campana local 2026–2027: preparar, piloto, ejecutar, estado y verificar."""
import argparse
import concurrent.futures
import contextlib
import csv
import datetime as dt
import fcntl
import hashlib
import io
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tarfile
import threading
import time
import zipfile

BASE = Path(__file__).resolve().parent
REPO = BASE.parents[1]
REFERENCIA = '32034e775666e8f8556db42222f9d064a5bc5a27'
INICIO = dt.date(2026, 1, 1)
PRESUPUESTO = 2000
SEMILLA_ORDEN = 27092026
STOP = threading.Event()
PROCESOS = set()
MUTEX = threading.Lock()


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def hash_file(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def hashes(root):
    return {str(p.relative_to(root)): hash_file(p) for p in sorted(root.rglob('*')) if p.is_file()}


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n', encoding='utf-8')
    tmp.replace(path)


def matriz(piloto=False):
    pares = []
    for offset in range(730):
        fecha = (INICIO + dt.timedelta(days=offset)).isoformat()
        for semilla in (20260927, 20260928):
            pares.append(dict(bloque='D', fecha=fecha, dias=1, semilla=semilla, incidencias=False))
        pares.append(dict(bloque='P', fecha=fecha, dias=1, semilla=20260927, incidencias=True))
    for offset in range(0, 730, 5):
        for semilla in (20260927, 20260928, 20260929):
            pares.append(dict(bloque='F', fecha=(INICIO + dt.timedelta(days=offset)).isoformat(),
                              dias=5, semilla=semilla, incidencias=False))
    if piloto:
        # Diagnostico separado: ambos anos, horizontes y una fecha con mantenimiento.
        pares = [dict(bloque=b, fecha=f, dias=d, semilla=20260927, incidencias=b == 'P')
                 for b, f, d in [('D', '2026-01-01', 1), ('D', '2027-12-31', 1),
                                 ('F', '2026-01-31', 5), ('F', '2027-01-31', 5),
                                 ('P', '2026-09-01', 1)]]
    rng = random.Random(SEMILLA_ORDEN)
    rng.shuffle(pares)
    for numero, par in enumerate(pares, 1):
        algoritmos = ['HGS', 'ALNS']
        rng.shuffle(algoritmos)
        par.update(id=f"{numero:04d}-{par['bloque']}-{par['fecha']}-{par['semilla']}",
                   algoritmos=algoritmos, presupuestoMs=PRESUPUESTO, planVigente=True)
    return pares


def expandir_bloqueos(data):
    registros = []
    for numero, linea in enumerate(data.decode('utf-8-sig').splitlines(), 1):
        if not linea.strip() or linea.lstrip().startswith('#'):
            continue
        ventana, coordenadas = linea.split(':', 1)
        valores = list(map(int, coordenadas.split(',')))
        if len(valores) < 4 or len(valores) % 2:
            raise ValueError(f'Bloqueo {numero}: pares incompletos')
        puntos = list(zip(valores[::2], valores[1::2]))
        expandidos = [puntos[0]]
        for (x, y), (a, b) in zip(puntos, puntos[1:]):
            if x != a and y != b:
                raise ValueError(f'Bloqueo {numero}: tramo diagonal')
            while (x, y) != (a, b):
                x += (a > x) - (a < x)
                y += (b > y) - (b < y)
                expandidos.append((x, y))
        if len(set(expandidos)) != len(expandidos):
            raise ValueError(f'Bloqueo {numero}: poligonal repetida')
        registros.append(ventana + ':' + ','.join(str(v) for punto in expandidos for v in punto))
    return ('\n'.join(registros) + '\n').encode('utf-8')


def preparar_datos(destino):
    originales = read_json(BASE / 'datos-publicados.json')['archivos']
    with zipfile.ZipFile(BASE / 'datos-publicados.zip') as z:
        if set(z.namelist()) != set(originales):
            raise ValueError('Inventario ZIP distinto del manifiesto')
        for nombre, esperado in originales.items():
            data = z.read(nombre)
            if hashlib.sha256(data).hexdigest() != esperado['sha256']:
                raise ValueError(f'Datos publicados alterados: {nombre}')
            if nombre.startswith('ventas.'):
                relativo = 'ventas/ventas' + nombre.split('.')[1] + '.txt'
            elif nombre.startswith('bloqueo.'):
                relativo = 'bloqueos/20' + nombre.split('.')[1] + '.bloqueadas'
                data = expandir_bloqueos(data)
            else:
                relativo = 'mantenimiento/' + nombre
            archivo = destino / relativo
            archivo.parent.mkdir(parents=True, exist_ok=True)
            archivo.write_bytes(data)
    (destino / 'flota.txt').write_text('AUTO,10\nMOTO,15\nBICICLETA,12\n', encoding='utf-8')
    meses = {f'{a}{m:02d}' for a in (2026, 2027) for m in range(1, 13)}
    if {p.name[6:12] for p in (destino / 'ventas').iterdir()} != meses:
        raise ValueError('Faltan meses de ventas')
    if {p.name[:6] for p in (destino / 'bloqueos').iterdir()} != meses:
        raise ValueError('Faltan meses de bloqueos')


def version(programa):
    return subprocess.check_output([programa, '-version'], stderr=subprocess.STDOUT, text=True).strip()


def fuentes_experimento():
    return {str(p.relative_to(BASE)): hash_file(p)
            for p in [BASE / 'ejecutar.py', BASE / 'datos-publicados.json', BASE / 'datos-publicados.zip',
                      *sorted((BASE / 'fuentes').glob('*.java'))]}


def preparar(out, piloto):
    if not shutil.which('java') or not shutil.which('javac'):
        raise ValueError('Instala JDK 21 (java y javac) y agregalo a PATH.')
    if not re.search(r'\bversion "21\.', version('java')) or not version('javac').startswith('javac 21.'):
        raise ValueError('La campana requiere Java y javac 21.')
    if (out / 'entorno.json').exists():
        validar_preparacion(out, piloto)
        print('Preparacion existente verificada.', flush=True)
        return
    work = out / 'preparacion'
    if work.exists():
        work.rename(out / ('preparacion-incompleta-' + str(time.time_ns())))
    work.mkdir()
    datos = work / 'datos'
    datos.mkdir()
    preparar_datos(datos)
    fuentes = work / 'fuentes'
    fuentes.mkdir()
    archive = subprocess.check_output(['git', 'archive', REFERENCIA, 'core/src/main/java',
                                       'experiments/src/main/java'], cwd=REPO)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for miembro in tar.getmembers():
            if not miembro.isfile():
                continue
            ruta = fuentes / miembro.name
            if not ruta.resolve().is_relative_to(fuentes.resolve()):
                raise ValueError('Ruta insegura en el archivo de fuentes')
            ruta.parent.mkdir(parents=True, exist_ok=True)
            ruta.write_bytes(tar.extractfile(miembro).read())
    for fuente in (BASE / 'fuentes').glob('*.java'):
        shutil.copyfile(fuente, fuentes / fuente.name)
    clases = work / 'clases'
    clases.mkdir()
    # javac @archivo evita superar el limite de longitud de comandos.
    lista = work / 'fuentes.txt'
    lista.write_text('\n'.join('"' + str(p).replace('\\', '\\\\') + '"'
                              for p in sorted(fuentes.rglob('*.java'))), encoding='utf-8')
    subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8', '-d', str(clases), '@' + str(lista)], check=True)
    with (work / 'cobertura.csv').open('w', encoding='utf-8') as cobertura:
        subprocess.run(['java', '-Xmx640m', '-XX:+UseSerialGC', '-cp', str(clases),
                        'org.kindbox.experiments.ValidarDatosCampana', str(datos)], stdout=cobertura, check=True)
    write_json(out / 'matriz.json', matriz(piloto))
    write_json(out / 'entorno.json', dict(fechaUtc=utc(), referencia=REFERENCIA,
               commitEjecutor=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
               java=version('java'), javac=version('javac'), python=sys.version,
               tipo='piloto' if piloto else 'campana', fuentesEjecutor=fuentes_experimento(),
               fuentes=hashes(fuentes), datos=hashes(datos), clases=hashes(clases),
               matrizSha256=hash_file(out / 'matriz.json'), coberturaSha256=hash_file(work / 'cobertura.csv')))
    print(f"Preparado: {len(matriz(piloto)) * 2} corridas en {out}", flush=True)


def validar_preparacion(out, piloto):
    e = read_json(out / 'entorno.json')
    if e['referencia'] != REFERENCIA or e['tipo'] != ('piloto' if piloto else 'campana'):
        raise ValueError('Tipo o referencia distintos; usa otra carpeta de salida.')
    if e['fuentesEjecutor'] != fuentes_experimento() or e['java'] != version('java'):
        raise ValueError('El ejecutor o Java cambiaron; conserva la version original para reanudar.')
    if hash_file(out / 'matriz.json') != e['matrizSha256'] or read_json(out / 'matriz.json') != matriz(piloto):
        raise ValueError('Matriz alterada')
    for nombre in ('datos', 'fuentes', 'clases'):
        if hashes(out / 'preparacion' / nombre) != e[nombre]:
            raise ValueError(f'Preparacion alterada: {nombre}')
    if hash_file(out / 'preparacion/cobertura.csv') != e['coberturaSha256']:
        raise ValueError('Cobertura alterada')


def cores_fisicos():
    disponibles = sorted(os.sched_getaffinity(0))
    unicos = {}
    for cpu in disponibles:
        topo = Path(f'/sys/devices/system/cpu/cpu{cpu}/topology')
        if not (topo / 'core_id').exists():
            # Sin topologia verificable, un trabajador por defecto.
            return [disponibles[0]]
        clave = ((topo / 'physical_package_id').read_text(), (topo / 'core_id').read_text())
        unicos.setdefault(clave, cpu)
    return list(unicos.values())


def memoria_disponible():
    info = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    return int(info['MemAvailable'].split()[0]) * 1024


def configuracion(workers):
    cores = cores_fisicos()
    memoria = memoria_disponible()
    limite_ram = max(0, (memoria - 2 * 1024 ** 3) // (1280 * 1024 ** 2))
    limite_cpu = max(1, len(cores) - 2)
    if workers is None:
        workers = max(1, min(8, limite_cpu, limite_ram))
    if workers < 1 or workers > len(cores):
        raise ValueError(f'--trabajadores debe estar entre 1 y {len(cores)} nucleos fisicos disponibles.')
    if workers > limite_ram:
        raise ValueError('RAM disponible insuficiente: reserva 2 GiB para sistema y 1,25 GiB por trabajador.')
    if not shutil.which('taskset'):
        raise ValueError('Instala util-linux para fijar cada JVM a un nucleo fisico con taskset.')
    cpuinfo = Path('/proc/cpuinfo').read_text()
    modelo = next((l.split(':', 1)[1].strip() for l in cpuinfo.splitlines() if l.startswith('model name')), '')
    return dict(trabajadores=workers, nucleos=cores[:workers], equipo=platform.node(),
                cpu=modelo, sistema=platform.platform(), java=version('java'),
                memoriaTotal=Path('/proc/meminfo').read_text().splitlines()[0],
                politica='pares secuenciales, JVM nueva, afinidad fisica, lotes sincronizados',
                opcionesJava=['-Xmx640m', '-XX:+UseSerialGC'])


def csv_rows(path):
    with path.open(newline='', encoding='utf-8') as f:
        return list(csv.DictReader(f))


def validar_corrida(path, par, algoritmo):
    resumen = csv_rows(path / 'resumen.csv')
    if len(resumen) != 1:
        raise ValueError('Resumen sin una unica fila')
    r = resumen[0]
    expected = dict(algoritmo=algoritmo, primerDia=par['fecha'], dias=str(par['dias']),
                    semilla=str(par['semilla']), presupuestoMs=str(PRESUPUESTO),
                    incidencias=str(par['incidencias']).lower(), planVigente='true', estado='CULMINADA',
                    minutoFinal=str(par['dias'] * 1440))
    if any(r.get(k) != v for k, v in expected.items()):
        raise ValueError('Resumen no corresponde a la celda o no termino el horizonte')
    if int(r['registrados']) != sum(int(r[k]) for k in ('entregados', 'pendientes', 'incumplidos')):
        raise ValueError('Particion final incoherente')
    planes = csv_rows(path / 'planes.csv')
    if len(planes) != int(r['planificaciones']) or len(planes) != int(r['planesVerificados']):
        raise ValueError('Numero de planes incoherente')
    for p in planes:
        if p['valido'] != 'true' or not 0 <= int(p['h']) <= int(p['pedidos']):
            raise ValueError('Plan invalido')
        if any(not math.isfinite(float(p[k])) or float(p[k]) < 0
               for k in ('tiempoInternoMs', 'tiempoExternoMs')):
            raise ValueError('Tiempo de plan invalido')
    serie = csv_rows(path / 'serie.csv')
    if not serie or int(serie[-1]['minuto']) != int(r['minutoFinal']):
        raise ValueError('Serie incompleta')
    ultimo = -1
    for p in serie:
        if int(p['minuto']) < ultimo or int(p['registrados']) != sum(int(p[k]) for k in ('entregados', 'pendientes', 'incumplidos')):
            raise ValueError('Serie temporal incoherente')
        ultimo = int(p['minuto'])
    for k in ('registrados', 'entregados', 'pendientes', 'incumplidos', 'costo', 'kilometros'):
        if float(serie[-1][k]) != float(r[k]):
            raise ValueError('Serie distinta del resumen: ' + k)
    if len(csv_rows(path / 'averias.csv')) != int(r['averias']):
        raise ValueError('Averias incoherentes')
    if not (path / 'entrada.txt').is_file():
        raise ValueError('Falta registro de entrada')
    return r


def completo(out, par):
    root = out / 'resultados' / par['id']
    marca = root / 'completo.json'
    if not marca.exists():
        return None
    m = read_json(marca)
    if m['par'] != par:
        raise ValueError(f"Marcador ajeno a la matriz: {par['id']}")
    intento = root / m['intento']
    if hashes(intento) != m['sha256']:
        raise ValueError(f"Resultado alterado: {par['id']}")
    for a in par['algoritmos']:
        meta = read_json(intento / a / 'metadatos.json')
        if meta['estado'] != 'completa' or meta['codigoSalida'] != 0:
            raise ValueError('Marcador completo con corrida fallida')
        validar_corrida(intento / a, par, a)
    return m


def log(out, mensaje):
    with MUTEX:
        linea = utc() + ' ' + mensaje
        print(linea, flush=True)
        with (out / 'campana.log').open('a', encoding='utf-8') as f:
            f.write(linea + '\n')


def detener(_sig=None, _frame=None):
    STOP.set()
    with MUTEX:
        for p in tuple(PROCESOS):
            with contextlib.suppress(ProcessLookupError):
                p.terminate()


def proceso(comando, stdout, stderr):
    with MUTEX:
        if STOP.is_set():
            return -signal.SIGTERM
        p = subprocess.Popen(comando, stdout=stdout, stderr=stderr)
        PROCESOS.add(p)
    try:
        while p.poll() is None:
            if STOP.wait(0.25):
                with contextlib.suppress(ProcessLookupError):
                    p.terminate()
                try:
                    p.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    p.kill()
                break
        return p.wait()
    finally:
        with MUTEX:
            PROCESOS.discard(p)


def ejecutar_par(out, par, trabajador, cpu, lote, carga):
    if STOP.is_set():
        return False
    root = out / 'resultados' / par['id']
    root.mkdir(parents=True, exist_ok=True)
    anteriores = [int(p.name.split('-')[1]) for p in root.glob('intento-*') if p.is_dir()]
    numero = max(anteriores, default=0) + 1
    intento = root / f'intento-{numero:03d}'
    intento.mkdir()
    log(out, f"INICIO {par['id']} trabajador={trabajador} cpu={cpu} intento={numero} lote={lote}")
    for algoritmo in par['algoritmos']:
        if STOP.is_set():
            return False
        salida = intento / algoritmo
        salida.mkdir()
        comando = ['taskset', '-c', str(cpu), 'java', '-Xmx640m', '-XX:+UseSerialGC', '-cp',
                   str(out / 'preparacion/clases'), 'org.kindbox.experiments.EjecutarCampana',
                   str(out / 'preparacion/datos'), algoritmo, par['fecha'], str(par['dias']),
                   str(PRESUPUESTO), str(par['semilla']), str(par['incidencias']).lower(), 'true', str(salida)]
        meta = dict(par, algoritmo=algoritmo, trabajador=trabajador, nucleo=cpu, intento=numero,
                    lote=lote, trabajadoresEnLote=carga, inicioUtc=utc(), estado='en_ejecucion', comando=comando)
        write_json(salida / 'metadatos.json', meta)
        t = time.monotonic()
        error = None
        codigo = None
        try:
            with (salida / 'resumen.csv').open('w', encoding='utf-8') as resumen, (salida / 'ejecucion.log').open('w', encoding='utf-8') as registro:
                codigo = proceso(comando, resumen, registro)
            if codigo != 0:
                raise ValueError(f'Java termino con codigo {codigo}; revisar {salida / "ejecucion.log"}')
            validar_corrida(salida, par, algoritmo)
        except Exception as e:
            error = str(e)
        meta.update(finUtc=utc(), duracionProcesoSegundos=time.monotonic() - t, codigoSalida=codigo,
                    estado=('interrumpida' if STOP.is_set() else 'fallida') if error else 'completa', error=error)
        write_json(salida / 'metadatos.json', meta)
        log(out, f"FIN {par['id']}/{algoritmo} estado={meta['estado']} segundos={meta['duracionProcesoSegundos']:.1f}")
        if error:
            log(out, error)
            detener()
            return False
    write_json(root / 'completo.json', dict(par=par, intento=intento.name, fechaUtc=utc(), sha256=hashes(intento)))
    return True


def consolidar(out, pares, validar=False):
    filas = []
    terminados = 0
    for par in pares:
        root = out / 'resultados' / par['id']
        marca = completo(out, par) if validar else (read_json(root / 'completo.json') if (root / 'completo.json').exists() else None)
        if not marca:
            continue
        terminados += 1
        for a in par['algoritmos']:
            salida = root / marca['intento'] / a
            r = csv_rows(salida / 'resumen.csv')[0]
            meta = read_json(salida / 'metadatos.json')
            r.update(bloque=par['bloque'], par=par['id'], intento=marca['intento'],
                     duracionProcesoSegundos=meta['duracionProcesoSegundos'], trabajador=meta['trabajador'],
                     nucleo=meta['nucleo'], ruta=str(salida.relative_to(out)))
            filas.append(r)
    if filas:
        temporal = out / 'corridas.csv.tmp'
        with temporal.open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=list(filas[0]))
            w.writeheader()
            w.writerows(filas)
        temporal.replace(out / 'corridas.csv')
    progreso = dict(fechaUtc=utc(), paresCompletos=terminados, paresPrevistos=len(pares),
                    corridasCompletas=terminados * 2, corridasPrevistas=len(pares) * 2,
                    completa=terminados == len(pares), verificacionIntegral=validar)
    write_json(out / 'progreso.json', progreso)
    return progreso


def informe_piloto(out, workers):
    filas = csv_rows(out / 'corridas.csv')
    cantidades = {'D': 2920, 'F': 876, 'P': 1460}
    medios = {b: statistics.mean(float(r['duracionProcesoSegundos']) for r in filas if r['bloque'] == b)
              for b in cantidades}
    estimado = sum(cantidades[b] * medios[b] for b in cantidades) / 3600 / workers
    ratios = []
    for r in filas:
        for p in csv_rows(out / r['ruta'] / 'planes.csv'):
            wall, cpu = float(p['tiempoExternoMs']), float(p['tiempoCpuMs'])
            if wall > 0 and cpu >= 0:
                ratios.append(cpu / wall)
    informe = dict(trabajadores=workers, segundosMediosPorCorrida=medios,
                   horasIdealesCampana=estimado, medianaFraccionCpu=statistics.median(ratios) if ratios else None,
                   limite='Extrapolacion de cinco pares; excluye esperas de lote y variacion entre fechas. No garantiza duracion ni igualdad de carga.')
    write_json(out / 'informe-piloto.json', informe)
    log(out, f"PILOTO: campana ideal estimada={estimado:.1f} horas con {workers} trabajadores; revisar informe-piloto.json y carga del equipo")


def correr(out, pares, workers):
    config = configuracion(workers)
    if shutil.disk_usage(out).free < 5 * 1024 ** 3:
        raise ValueError('La carpeta de salida necesita al menos 5 GiB libres.')
    archivo = out / 'ejecucion.json'
    if archivo.exists():
        original = read_json(archivo)
        if original['configuracion'] != config:
            raise ValueError('Equipo, Java o concurrencia cambiaron. Reanuda con los mismos trabajadores y equipo.')
    else:
        write_json(archivo, dict(fechaUtc=utc(), configuracion=config))
    pendientes = [p for p in pares if completo(out, p) is None]
    total = len(pares)
    workers = config['trabajadores']
    log(out, f"Pares pendientes={len(pendientes)}/{total}; trabajadores={workers}; nucleos={config['nucleos']}")
    signal.signal(signal.SIGINT, detener)
    signal.signal(signal.SIGTERM, detener)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for start in range(0, len(pendientes), workers):
            if STOP.is_set():
                break
            lote = pendientes[start:start + workers]
            futures = [pool.submit(ejecutar_par, out, p, lane, config['nucleos'][lane],
                                   start // workers + 1, len(lote)) for lane, p in enumerate(lote)]
            try:
                for f in futures:
                    f.result()
            except BaseException:
                detener()
                raise
            progreso = consolidar(out, pares)
            log(out, f"AVANCE {progreso['corridasCompletas']}/{progreso['corridasPrevistas']} corridas verificadas en pares")
    progreso = consolidar(out, pares, validar=True)
    if progreso['completa'] and read_json(out / 'entorno.json')['tipo'] == 'piloto':
        informe_piloto(out, workers)
    log(out, 'CAMPANA COMPLETA Y VERIFICADA' if progreso['completa'] else 'CAMPANA INCOMPLETA: conserva la carpeta y repite el comando para reanudar')
    return 0 if progreso['completa'] else 2


@contextlib.contextmanager
def bloqueo(out):
    out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Otra ejecucion usa esta carpeta. Consulta progreso.json o campana.log.')
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('accion', choices=['preparar', 'piloto', 'ejecutar', 'estado', 'verificar'])
    parser.add_argument('--salida', type=Path, help='Carpeta independiente; conserva resultados y permite reanudar')
    parser.add_argument('--trabajadores', type=int, help='JVM simultaneas; automatico segun CPU fisica y RAM, maximo 8')
    args = parser.parse_args()
    piloto = args.accion == 'piloto'
    out = (args.salida or REPO / 'salidas' / ('piloto-expnum-2026-2027' if piloto else 'expnum-2026-2027')).resolve()
    if args.accion in ('estado', 'verificar'):
        if not (out / 'entorno.json').exists():
            raise ValueError('No hay campana preparada en ' + str(out))
        piloto = read_json(out / 'entorno.json')['tipo'] == 'piloto'
    if args.accion == 'estado':
        pares = read_json(out / 'matriz.json')
        n = sum((out / 'resultados' / p['id'] / 'completo.json').exists() for p in pares)
        print(json.dumps(dict(paresCompletos=n, paresPrevistos=len(pares),
                              corridasCompletas=n * 2, corridasPrevistas=len(pares) * 2,
                              completa=n == len(pares)), indent=2))
        return 0
    with bloqueo(out):
        preparar(out, piloto)
        pares = read_json(out / 'matriz.json')
        if args.accion == 'verificar':
            progreso = consolidar(out, pares, validar=True)
            print(json.dumps(progreso, indent=2))
            return 0 if progreso['completa'] else 2
        if args.accion == 'preparar':
            return 0
        return correr(out, pares, args.trabajadores)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError) as e:
        print('ERROR: ' + str(e), file=sys.stderr)
        sys.exit(1)
