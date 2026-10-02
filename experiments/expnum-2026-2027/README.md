# Experimentacion numerica local: 2026–2027

Ejecutor de las **5.256 corridas** del diseno IEN 2.1: D = 2.920, F = 876,
P = 1.460. Incluye los 49 archivos publicados necesarios en un ZIP de unos
1,1 MB, con sus hashes originales. No hace falta copiar nada desde la otra PC.
Las 100.010 ventas de enero de 2026 a diciembre de 2027 se usan sin escalarlas.
Los resultados de esta campana permanecen pendientes hasta ejecutar y auditar.

## Windows: instalacion inicial

En **PowerShell como administrador**, instala Ubuntu en WSL y reinicia:

```powershell
wsl --install -d Ubuntu-24.04
```

Abre Ubuntu desde Inicio y crea el usuario de Linux que te pide. Los siguientes
comandos se ejecutan **dentro de Ubuntu**, no en PowerShell. WSL necesita Windows
10 2004 o posterior, o Windows 11; la instalacion nueva usa WSL 2 por defecto.
[Instrucciones oficiales de Microsoft](https://learn.microsoft.com/en-us/windows/wsl/install).

```bash
sudo apt update
sudo apt install -y git python3 openjdk-21-jdk-headless util-linux tmux
git clone https://github.com/dp1-grupo-3c/metaheuristics-platform.git
cd metaheuristics-platform
java -version
python3 experiments/expnum-2026-2027/ejecutar.py preparar
```

Si el repositorio es privado, autentica Git con tu cuenta de GitHub. Clona dentro
del directorio personal de Ubuntu (`~/metaheuristics-platform`) para mantener
los resultados en el sistema de archivos de Linux. No requiere Maven, Docker,
servidor web, paquetes de pip ni GPU.

## Piloto: comprobar la computadora antes de la campana

Para una PC con 32 GB de RAM, **cuatro trabajadores** son un punto de partida.
Cada trabajador ejecuta una JVM con `-Xmx640m`; se reservan 1,25 GiB por proceso
y 2 GiB para el sistema. El script rechaza una concurrencia mayor que la
topologia de CPU o la memoria disponible. Si la CPU permite menos de cuatro
nucleos, usa dos o uno. Sin `--trabajadores`, calcula un valor conservador,
con maximo ocho; no lo interpreta como una calibracion de rendimiento.

Para consultar CPU y RAM desde Ubuntu:

```bash
lscpu
free -h
```

Deja la computadora conectada a corriente y desactiva la suspension automatica
en Windows durante la ejecucion. Una sesion tmux permite cerrar la terminal y
volver a entrar; **no mantiene la ejecucion durante una suspension o reinicio**.

```bash
tmux new -s expnum
python3 experiments/expnum-2026-2027/ejecutar.py piloto --trabajadores 4
```

El piloto ejecuta diez corridas en `salidas/piloto-expnum-2026-2027/`: ambos
algoritmos, ambos anos, uno y cinco dias, y una fecha con mantenimiento. Conserva
el presupuesto de 2.000 ms; puede tardar alrededor de una hora, segun CPU.
**No se agrega a las 5.256 corridas**. Para contrastar ejecucion aislada y
concurrente antes de fijar la concurrencia:

```bash
python3 experiments/expnum-2026-2027/ejecutar.py piloto --trabajadores 1 --salida salidas/piloto-aislado
python3 experiments/expnum-2026-2027/ejecutar.py piloto --trabajadores 4 --salida salidas/piloto-paralelo
```

Revisa tiempos en `corridas.csv` y la relacion de `tiempoCpuMs` con
`tiempoExternoMs` en cada `planes.csv`, junto con uso de RAM y swap (`free -h`).
Al completar el piloto, `informe-piloto.json` muestra la mediana de la fraccion
CPU/tiempo externo y una estimacion ideal de duracion basada en esas mediciones;
su muestra pequena y las esperas entre lotes limitan esa estimacion.
Si hay competencia apreciable de CPU, memoria o carga externa, reduce los
trabajadores y repite el piloto en otra carpeta antes de lanzar la campana.
Esta revision es operativa; no se elige la concurrencia por el algoritmo ganador.
La topologia que WSL expone puede ser virtual: la afinidad fija una CPU del
invitado, sin garantizar un nucleo fisico exclusivo en Windows. La evidencia
registra el equipo y los nucleos utilizados; no promete condiciones de EC2.

## Ejecutar la campana completa

Dentro de la sesion tmux, despues del piloto:

```bash
python3 experiments/expnum-2026-2027/ejecutar.py ejecutar --trabajadores 4
```

Muestra avances y guarda `campana.log`. Para salir de tmux sin detenerlo pulsa
**Ctrl+B, suelta y luego D**. Para volver:

```bash
tmux attach -t expnum
```

En otra terminal de Ubuntu, desde el repositorio:

```bash
python3 experiments/expnum-2026-2027/ejecutar.py estado
tail -f salidas/expnum-2026-2027/campana.log
```

La estimacion previa de 382 horas secuenciales equivalentes daria unas 96 horas
ideales con cuatro trabajadores (unos cuatro dias). Es una extrapolacion de la
campana pequena, **no una medicion de esta PC**: el piloto y el avance real
permiten corregirla. La RAM por si sola no determina la velocidad.

## Interrumpir y reanudar

Pulsa **Ctrl+C** para detener: finaliza los procesos Java y conserva la evidencia.
Un reinicio tambien deja los archivos anteriores. Para reanudar usa exactamente
el mismo comando, equipo, version de Java y numero de trabajadores:

```bash
cd ~/metaheuristics-platform
python3 experiments/expnum-2026-2027/ejecutar.py ejecutar --trabajadores 4
```

Solo omite **pares completos y auditados**. Si se interrumpio uno de sus
algoritmos, vuelve a ejecutar ambos en otro `intento-NNN` y conserva el anterior.
Un fallo de Java o de auditoria detiene la campana y devuelve codigo 2; revisa
`ejecucion.log` antes de reanudar. No borres la carpeta ni actualices el ejecutor,
Java o WSL a mitad de la campana. Si necesitas cambiar concurrencia o equipo,
crea una campana independiente con `--salida` para no mezclar condiciones.

## Verificacion y entrega de resultados

Al terminar, el ejecutor audita todas las corridas y consolida `corridas.csv`.
Tambien puedes verificar manualmente:

```bash
python3 experiments/expnum-2026-2027/ejecutar.py verificar
tar -czf expnum-2026-2027-resultados.tar.gz -C salidas expnum-2026-2027
explorer.exe .
```

`verificar` devuelve 0 solo si estan completos los 2.628 pares / 5.256 corridas.
El archivo `expnum-2026-2027-resultados.tar.gz` contiene la evidencia para el
analisis posterior. No ejecutes los scripts historicos de 24 corridas sobre
esta carpeta. El ejecutor consolida mediciones, no publica conclusiones ni
rellena automaticamente el documento Word.

## Protocolo que aplica el ejecutor

- D/P: las 730 fechas diarias; D con semillas 20260927/20260928, P con 20260927.
- F: 146 ventanas contiguas de cinco dias desde 01/01/2026, sin solapamiento;
  semillas 20260927/20260928/20260929. Ultima ventana: 27–31/12/2027.
- HGS y ALNS por par; orden de pares y algoritmos fijado con semilla 27092026.
- Presupuesto de 2.000 ms, plan vigente habilitado, reloj LIBRE y JVM nueva por
  corrida; calentamiento original de tres llamadas de 200 ms, reserva 50 ms.
- `-Xmx640m -XX:+UseSerialGC`; algoritmos del mismo par secuenciales sobre la
  misma CPU, pares concurrentes por lotes y CPUs distintas. Los lotes esperan
  que todos sus pares terminen; se registra el numero de trabajadores del lote.
  Duraciones diferentes y el ultimo lote pueden producir carga desigual: la
  afinidad no elimina toda competencia ni garantiza igual CPU por presupuesto.
- Nucleo y clases de experiments compilados desde **32034e775666e8f8556db42222f9d064a5bc5a27**,
  la referencia del IEN. `git archive` extrae sus fuentes a la salida: el
  checkout actual permanece disponible y sus cambios posteriores no entran
  en la experimentacion. El clon normal debe conservar ese commit; si se hizo
  un clon superficial, ejecuta `git fetch --unshallow` antes de preparar.
- Solo se amplia orquestacion e instrumentacion. La medicion de CPU por llamada
  se agrega fuera del algoritmo, para observar competencia por tiempo de CPU.
- Datos originales: 24 ventas, 24 bloqueos y el unico mantenimiento publicado
  (septiembre–octubre de 2026). D/F excluyen mantenimiento; P conserva fechas
  reales y habilita averias. No se repite mantenimiento en 2027.
- Los bloqueos se expanden a pasos ortogonales de un kilometro; ventas conservan
  exactamente sus bytes. Flota: 10 autos, 15 motos, 12 bicicletas.
- Antes de simular, el lector Java valida las 730 fechas, las 146 ventanas,
  las 100.010 ventas y un rango entre anos; produce `preparacion/cobertura.csv`.
- Los resumenes, planes, series y eventos se auditan antes de marcar un par.
  El objetivo H/U/S se verifica como en el arnes original; no representa una
  validacion independiente de cada penalizacion del modelo.
- Una corrida incompleta queda fuera de `corridas.csv`; no se oculta el intento.
  La campana solo es completa cuando todos los pares estan verificados.

## Archivos de salida

Todo queda en `salidas/expnum-2026-2027/`, excluido de Git:

- `matriz.json`: 2.628 pares, orden congelado y ambas celdas por par.
- `entorno.json`: referencia, versiones, hashes de fuentes, clases y datos.
- `ejecucion.json`: equipo, CPU, afinidad y concurrencia efectiva.
- `resultados/<par>/intento-NNN/<algoritmo>/`: resumen, planes, series, eventos,
  entrada, log y metadatos individuales. `completo.json` conserva hashes del par.
- `progreso.json`, `campana.log` y `corridas.csv`: estado y mediciones consolidadas.
- `preparacion/`: datos transformados, fuentes de referencia, clases compiladas
  y cobertura; permite preservar exactamente lo ejecutado.

Pruebas del ejecutor, sin simulaciones masivas:

```bash
python3 -m unittest discover -s experiments/expnum-2026-2027/tests -v
```
