"""Copia ventas y expande bloqueos publicados sin modificar los archivos originales."""
import datetime
import re
import argparse
import hashlib
import json
from pathlib import Path
import shutil

analizador = argparse.ArgumentParser(description=__doc__)
analizador.add_argument('origen', type=Path)
analizador.add_argument('destino', type=Path)
argumentos = analizador.parse_args()
origen = argumentos.origen.resolve()
destino = argumentos.destino.resolve()
if destino.exists():
    raise SystemExit('El destino debe ser un directorio nuevo para evitar mezclar datos.')
ventas = sorted(origen.rglob('ventas.20*.txt'))
bloqueos = sorted(origen.rglob('bloqueo.*.txt'))
if not ventas or not bloqueos:
    raise SystemExit('No se encontraron las ventas y bloqueos publicados.')
for nombre in ['ventas', 'bloqueos', 'mantenimiento']:
    (destino / nombre).mkdir(parents=True, exist_ok=True)
manifiesto = []
for archivo in ventas:
    mes = archivo.name.split('.')[1]
    salida = destino / 'ventas' / f'ventas{mes}.txt'
    shutil.copyfile(archivo, salida)
    manifiesto.append({'archivo': str(archivo.relative_to(origen)),
                      'sha256': hashlib.sha256(archivo.read_bytes()).hexdigest()})
for archivo in bloqueos:
    mes = '20' + archivo.name.split('.')[1]
    registros = []
    for numero, linea in enumerate(archivo.read_text(encoding='utf-8-sig').splitlines(), 1):
        if not linea.strip():
            continue
        ventana, coordenadas = linea.split(':')
        valores = list(map(int, coordenadas.split(',')))
        if len(valores) < 4 or len(valores) % 2:
            raise ValueError(f'{archivo}:{numero}: pares incompletos')
        puntos = list(zip(valores[::2], valores[1::2]))
        expandidos = [puntos[0]]
        for (x, y), (a, b) in zip(puntos, puntos[1:]):
            if x != a and y != b:
                raise ValueError(f'{archivo}:{numero}: tramo diagonal')
            while (x, y) != (a, b):
                x += (a > x) - (a < x)
                y += (b > y) - (b < y)
                expandidos.append((x, y))
        if len(set(expandidos)) != len(expandidos):
            raise ValueError(f'{archivo}:{numero}: poligonal con nodos repetidos')
        registros.append(ventana + ':' + ','.join(str(v) for punto in expandidos for v in punto))
    salida = destino / 'bloqueos' / f'{mes}.bloqueadas'
    salida.write_text('\n'.join(registros) + '\n', encoding='utf-8')
    manifiesto.append({'archivo': str(archivo.relative_to(origen)),
                      'sha256': hashlib.sha256(archivo.read_bytes()).hexdigest(),
                      'sha256Expandido': hashlib.sha256(salida.read_bytes()).hexdigest()})
(destino / 'flota.txt').write_text('AUTO,10\nMOTO,15\nBICICLETA,12\n', encoding='utf-8')
(destino / 'manifiesto.json').write_text(json.dumps(manifiesto, indent=2) + '\n', encoding='utf-8')
print(f'{len(ventas)} archivos de ventas y {len(bloqueos)} de bloqueos preparados en {destino}')

archivosMantenimiento = sorted(origen.rglob('mant.preventivo.09.10*'))
if len(archivosMantenimiento) != 1:
    raise SystemExit('Se requiere un unico plan publicado mant.preventivo.09.10.')
archivo = archivosMantenimiento[0]
patron = []
for registro in archivo.read_text(encoding='utf-8-sig').split():
    coincidencia = re.fullmatch(r'(2026)(09|10)(\d{2}):(T[AMB]\d{2})', registro)
    if not coincidencia:
        raise ValueError(f'Registro de mantenimiento invalido: {registro}')
    _, mes, dia, codigo = coincidencia.groups()
    patron.append((int(mes) - 9, int(dia), codigo))
esperados = {f'{tipo}{numero:02d}' for tipo, cantidad in [('TA', 10), ('TM', 15), ('TB', 12)] for numero in range(1, cantidad + 1)}
if len(patron) != 37 or {fila[2] for fila in patron} != esperados:
    raise ValueError('El patron debe programar una vez cada una de las 37 unidades.')
for ano in range(2026, 2029):
    carpeta = destino / 'mantenimiento' / str(ano)
    carpeta.mkdir()
    for primerMes in range(1, 13, 2):
        registros = []
        for desplazamiento, dia, codigo in patron:
            fecha = datetime.date(ano, primerMes + desplazamiento, dia)
            registros.append(f'{fecha:%Y%m%d}:{codigo}')
        (carpeta / f'mant.preventivo.{primerMes:02d}.{primerMes + 1:02d}').write_text(
            '\n'.join(sorted(registros)) + '\n', encoding='utf-8')
(destino / 'mantenimiento' / 'procedencia.json').write_text(json.dumps({
    'archivoBase': str(archivo.relative_to(origen)),
    'sha256': hashlib.sha256(archivo.read_bytes()).hexdigest(),
    'regla': 'Repeticion cada dos meses, confirmada por el product owner; mismo dia y unidad.',
    'desde': '2026-01-01', 'hasta': '2028-12-31', 'registros': 666,
}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Mantenimiento: 18 bimestres, 666 registros, 2026–2028.')
