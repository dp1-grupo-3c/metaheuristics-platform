"""Copia ventas y expande bloqueos publicados sin modificar los archivos originales."""
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
