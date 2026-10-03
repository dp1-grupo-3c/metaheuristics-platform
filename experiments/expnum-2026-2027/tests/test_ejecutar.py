import collections
import csv
import datetime as dt
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'ejecutar.py'
spec = importlib.util.spec_from_file_location('campana', SCRIPT)
campana = importlib.util.module_from_spec(spec)
spec.loader.exec_module(campana)


def csv_file(path, rows):
    with path.open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def resultado(path, par, algoritmo):
    path.mkdir(parents=True, exist_ok=True)
    r = dict(algoritmo=algoritmo, primerDia=par['fecha'], dias=par['dias'],
             semilla=par['semilla'], presupuestoMs=2000, incidencias=str(par['incidencias']).lower(),
             planVigente='true', estado='CULMINADA', minutoFinal=par['dias'] * 1440,
             registrados=3, entregados=1, pendientes=1, incumplidos=1, costo=2,
             kilometros=1, planificaciones=1, planesVerificados=1, averias=0)
    csv_file(path / 'resumen.csv', [r])
    csv_file(path / 'planes.csv', [dict(valido='true', h=1, pedidos=3, tiempoInternoMs=1, tiempoExternoMs=1)])
    csv_file(path / 'serie.csv', [dict(minuto=r['minutoFinal'], registrados=3, entregados=1,
                                      pendientes=1, incumplidos=1, costo=2, kilometros=1)])
    (path / 'averias.csv').write_text('minuto,unidad,tipo,x,y,carga\n')
    (path / 'entrada.txt').write_text('entrada\n')
    (path / 'ejecucion.log').write_text('')


class CampanaTest(unittest.TestCase):
    def setUp(self):
        campana.STOP.clear()
        self.temp = tempfile.TemporaryDirectory()
        self.out = Path(self.temp.name)
        self.par = campana.matriz()[0]

    def tearDown(self):
        self.temp.cleanup()
        campana.STOP.clear()

    def test_matriz_solo_con_dias_completos(self):
        matriz = campana.matriz()
        self.assertEqual(matriz, campana.matriz())
        self.assertEqual(len({p['id'] for p in matriz}), 1749)
        self.assertEqual(collections.Counter(p['bloque'] for p in matriz), {'D': 982, 'P': 491, 'F': 276})
        for bloque, n in [('D', 2), ('P', 1), ('F', 3)]:
            fechas = collections.Counter(p['fecha'] for p in matriz if p['bloque'] == bloque)
            self.assertTrue(all(c == n for c in fechas.values()))
            self.assertEqual(len(fechas), 92 if bloque == 'F' else 491)
        clases = campana.clasificacion()
        dias_f = [dt.date.fromisoformat(f) + dt.timedelta(days=i)
                  for f in {p['fecha'] for p in matriz if p['bloque'] == 'F'} for i in range(5)]
        self.assertEqual(len(dias_f), len(set(dias_f)))  # ventanas sin solapamiento
        dias = dias_f + [dt.date.fromisoformat(p['fecha']) for p in matriz if p['bloque'] != 'F']
        self.assertTrue(all(clases[d][1] == 'completo' for d in dias))
        self.assertTrue(all(set(p['algoritmos']) == {'HGS', 'ALNS'} and p['presupuestoMs'] == 2000 for p in matriz))

    def test_clasificacion_de_fechas_publicadas(self):
        clases = campana.clasificacion()
        self.assertEqual(len(clases), 730)
        self.assertEqual(collections.Counter(c for _, c in clases.values()),
                         {'completo': 491, 'vacio': 223, 'cortado': 16})
        self.assertEqual(sum(n for n, _ in clases.values()), 100010)
        self.assertEqual(clases[dt.date(2026, 8, 31)], (192, 'completo'))  # mes bajo el tope
        self.assertEqual(clases[dt.date(2026, 10, 27)], (8, 'cortado'))  # termina a las 17:15
        self.assertEqual(clases[dt.date(2026, 12, 24)], (0, 'vacio'))
        self.assertEqual(clases[dt.date(2026, 11, 2)][1], 'completo')

    def test_cobertura_rechaza_celdas_incompletas_o_conteos_distintos(self):
        cobertura = self.out / 'cobertura.csv'
        filas = [dict(fecha=f.isoformat(), dias=1, pedidos=n, bloqueos=0, mantenimientos=0)
                 for f, (n, _) in campana.clasificacion().items()]
        csv_file(cobertura, filas)
        campana.verificar_cobertura(cobertura, campana.matriz())
        with self.assertRaises(ValueError):
            campana.verificar_cobertura(cobertura, [dict(bloque='D', fecha='2026-12-24', dias=1)])
        with self.assertRaises(ValueError):
            campana.verificar_cobertura(cobertura, [dict(bloque='F', fecha='2026-10-25', dias=5)])
        filas[0]['pedidos'] = 0
        csv_file(cobertura, filas)
        with self.assertRaises(ValueError):
            campana.verificar_cobertura(cobertura, [])

    def test_originales_y_cobertura_de_datos(self):
        campana.preparar_datos(self.out)
        self.assertEqual(len(list((self.out / 'ventas').iterdir())), 24)
        self.assertEqual(len(list((self.out / 'bloqueos').iterdir())), 24)
        self.assertEqual(sum(len(p.read_text().splitlines()) for p in (self.out / 'ventas').iterdir()), 100010)
        for p in (self.out / 'ventas').iterdir():
            original = 'ventas.' + p.name[6:12] + '.txt'
            esperado = campana.read_json(campana.BASE / 'datos-publicados.json')['archivos'][original]['sha256']
            self.assertEqual(campana.hash_file(p), esperado)

    def test_expansion_ortogonal_y_rechazo_de_diagonales(self):
        self.assertEqual(campana.expandir_bloqueos(b'01d00h00m-01d01h00m:1,1,3,1'),
                         b'01d00h00m-01d01h00m:1,1,2,1,3,1\n')
        with self.assertRaises(ValueError):
            campana.expandir_bloqueos(b'01d00h00m-01d01h00m:1,1,3,3')

    def test_piloto_separado(self):
        piloto = campana.matriz(True)
        self.assertEqual(len(piloto), 5)
        self.assertEqual({p['bloque'] for p in piloto}, {'D', 'F', 'P'})
        self.assertEqual({p['fecha'][:4] for p in piloto}, {'2026', '2027'})
        clases = campana.clasificacion()
        self.assertTrue(all(clases[dt.date.fromisoformat(p['fecha']) + dt.timedelta(days=i)][1] == 'completo'
                            for p in piloto for i in range(p['dias'])))

    def test_no_acepta_resumen_incompleto_o_ajeno(self):
        resultado(self.out, self.par, 'HGS')
        campana.validar_corrida(self.out, self.par, 'HGS')
        with self.assertRaises(ValueError):
            campana.validar_corrida(self.out, self.par, 'ALNS')
        r = campana.csv_rows(self.out / 'resumen.csv')[0]
        r['minutoFinal'] = '30'
        csv_file(self.out / 'resumen.csv', [r])
        with self.assertRaises(ValueError):
            campana.validar_corrida(self.out, self.par, 'HGS')

    def test_rechaza_series_incoherentes(self):
        resultado(self.out, self.par, 'HGS')
        r = campana.csv_rows(self.out / 'serie.csv')[0]
        r['registrados'] = '9'
        csv_file(self.out / 'serie.csv', [r])
        with self.assertRaises(ValueError):
            campana.validar_corrida(self.out, self.par, 'HGS')

    def test_reanudacion_conserva_intento_y_repite_el_par(self):
        # Primer intento: el proceso falla. No debe crearse un marcador completo.
        with patch.object(campana, 'proceso', return_value=137):
            self.assertFalse(campana.ejecutar_par(self.out, self.par, 0, 0, 1, 1))
        self.assertIsNone(campana.completo(self.out, self.par))
        root = self.out / 'resultados' / self.par['id']
        anterior = campana.hashes(root / 'intento-001')
        campana.STOP.clear()
        llamados = []

        def simular(cmd, stdout, stderr):
            algoritmo = cmd[-8]
            llamados.append(algoritmo)
            # Escribir al archivo abierto por el orquestador.
            stdout.flush()
            resultado(Path(cmd[-1]), self.par, algoritmo)
            return 0

        with patch.object(campana, 'proceso', side_effect=simular):
            self.assertTrue(campana.ejecutar_par(self.out, self.par, 0, 0, 1, 1))
        self.assertEqual(llamados, self.par['algoritmos'])
        self.assertEqual(campana.hashes(root / 'intento-001'), anterior)
        self.assertEqual(campana.completo(self.out, self.par)['intento'], 'intento-002')
        # No se acepta una manipulacion posterior de los resultados.
        (root / 'intento-002/HGS/entrada.txt').write_text('alterado')
        with self.assertRaises(ValueError):
            campana.completo(self.out, self.par)

    def test_interrupcion_detiene_jvm(self):
        campana.STOP.clear()
        import threading
        timer = threading.Timer(0.2, campana.detener)
        timer.start()
        try:
            code = campana.proceso(['python3', '-c', 'import time; time.sleep(30)'],
                                   subprocess.DEVNULL, subprocess.DEVNULL)
            self.assertNotEqual(code, 0)
            self.assertFalse(campana.PROCESOS)
        finally:
            timer.join()

    def test_planificador_y_reanudacion_omiten_solo_pares_completos(self):
        pares = campana.matriz()[:2]
        campana.write_json(self.out / 'entorno.json', {'tipo': 'campana'})
        config = dict(trabajadores=2, nucleos=[0, 1])

        def simular(cmd, stdout, stderr):
            salida = Path(cmd[-1])
            par = next(p for p in pares if p['id'] == salida.parent.parent.name)
            resultado(salida, par, cmd[-8])
            return 0

        with patch.object(campana, 'configuracion', return_value=config), \
             patch.object(campana.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 10 * 1024 ** 3})()), \
             patch.object(campana.signal, 'signal'), \
             patch.object(campana, 'proceso', side_effect=simular) as proc:
            self.assertEqual(campana.correr(self.out, pares, 2), 0)
            self.assertEqual(proc.call_count, 4)
            proc.reset_mock()
            self.assertEqual(campana.correr(self.out, pares, 2), 0)
            proc.assert_not_called()
        self.assertEqual(len(campana.csv_rows(self.out / 'corridas.csv')), 4)
        progreso = campana.read_json(self.out / 'progreso.json')
        self.assertTrue(progreso['completa'])
        self.assertTrue(progreso['verificacionIntegral'])


if __name__ == '__main__':
    unittest.main()
