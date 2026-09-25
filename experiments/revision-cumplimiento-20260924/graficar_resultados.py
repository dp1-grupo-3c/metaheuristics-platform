"""Compara incumplimientos y costo a partir de los registros de las corridas operativas."""
import csv
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as grafica

raiz = Path(__file__).resolve().parent
base = {fila['algoritmo']: fila for fila in json.loads((raiz / 'base.json').read_text())}
actual = {}
for algoritmo in ['HGS', 'ALNS']:
    with (raiz / f'operativa-{algoritmo}.csv').open() as archivo:
        actual[algoritmo] = next(csv.DictReader(archivo))
grafica.rcParams.update({'font.size': 10, 'figure.dpi': 300, 'savefig.dpi': 300,
                        'axes.spines.top': False, 'axes.spines.right': False})
figura, ejes = grafica.subplots(1, 2, figsize=(8.4, 3.8))
colores = ['#888888', '#0072B2']
for eje, claveBase, claveActual, etiqueta, divisor in [
        (ejes[0], 'pedidosIncumplidos', 'incumplidos', 'Pedidos incumplidos', 1),
        (ejes[1], 'costoAcumulado', 'costo', 'Costo acumulado (miles de S/)', 1000)]:
    for grupo, (datos, clave, nombre) in enumerate([(base, claveBase, 'Base babdc19'),
                                                  (actual, claveActual, 'Mejora')]):
        valores = [float(datos[a][clave]) / divisor for a in ['HGS', 'ALNS']]
        posiciones = [i + (grupo - 0.5) * 0.34 for i in range(2)]
        barras = eje.bar(posiciones, valores, width=0.32, color=colores[grupo], label=nombre)
        eje.bar_label(barras, labels=[f'{v:.0f}' if divisor == 1 else f'{v:.1f}' for v in valores], padding=4)
    eje.set_xticks([0, 1], ['HGS', 'ALNS'])
    eje.set_ylabel(etiqueta)
    eje.set_ylim(0, 11 if divisor == 1 else 145)
    eje.grid(axis='y', alpha=0.15)
    eje.set_axisbelow(True)
figura.legend(*ejes[0].get_legend_handles_labels(), loc='upper center', ncol=2, frameon=False)
figura.subplots_adjust(top=0.85, bottom=0.25, wspace=0.42)
figura.text(0.5, 0.07, 'Data publicada, 01–05/09/2026 · 2 s por planificación · Sin averías ni mantenimientos\n'
            'Una corrida por algoritmo y versión; semilla 20260924.', ha='center', fontsize=9)
figura.savefig(raiz / 'comparacion.png', bbox_inches='tight')
figura.savefig(raiz / 'comparacion.pdf', bbox_inches='tight')
print('Figuras guardadas en', raiz)
