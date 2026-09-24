#!/usr/bin/env python3
"""Analisis de la campana experimental ALNS vs HGS.

    python3 experiments/campana/analizar.py [directorio_resultados]

Lee resultados/filas/*.csv y resultados/series/*.csv y produce, en el mismo directorio:

  campana.csv     todas las corridas en una tabla (una fila por corrida)
  pruebas.csv     pruebas de Wilcoxon pareadas, con Hodges-Lehmann y correccion de Holm
  tablas.md       tablas descriptivas y de contraste, listas para el informe
  figuras/*.png   graficos de los resultados

Criterios estadisticos (fijados antes de mirar los resultados):
  * Unidad de emparejamiento: la replica. En cada replica las tres variantes resuelven la
    misma instancia (la misma submuestra de la base maestra).
  * Prueba: Wilcoxon de rangos con signo, exacta, bilateral, con las diferencias nulas
    descartadas (procedimiento de Wilcoxon). Si todas las diferencias son nulas no hay
    prueba: se informa "sin diferencias".
  * Tamano de efecto: estimador de Hodges-Lehmann (mediana de los promedios de Walsh) con su
    intervalo de confianza exacto del 95 %, y correlacion biserial de rangos.
  * Multiplicidad: Holm sobre la familia de contrastes principales (ALNS-C frente a HGS), a
    alfa 0,05. Los contrastes del arranque (ALNS-C frente a ALNS-F) forman otra familia.
  * Censura: una corrida 5D sin incumplidos tiene su primer incumplido en ">7200"; se le
    asigna 7200 (fin del horizonte), lo que solo puede acortar la diferencia. En colapso,
    una corrida que agota los 30 dias se censura en 43200.
"""
import glob
import itertools
import math
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

VARIANTES = ["ALNS-C", "ALNS-F", "HGS"]
NOMBRE = {"ALNS-C": "ALNS arranque cálido", "ALNS-F": "ALNS arranque frío", "HGS": "HGS"}
# Paleta categorica validada (azul, naranja, aqua) con dataviz/validate_palette.js.
COLOR = {"ALNS-C": "#2a78d6", "ALNS-F": "#eb6834", "HGS": "#1baf7a"}
NIVELES_5D = ["N1", "N2", "N3"]
HORIZONTE_5D = 7200
HORIZONTE_COLAPSO = 30 * 1440
ALFA = 0.05
TINTA = "#1f1f1e"
TINTA_2 = "#5c5b55"
REJILLA = "#e4e3dd"


# --------------------------------------------------------------------- estadistica

def distribucion_wilcoxon(n):
    """Distribucion exacta del estadistico W+ para n diferencias no nulas sin empates."""
    maximo = n * (n + 1) // 2
    cuentas = np.zeros(maximo + 1)
    cuentas[0] = 1
    for k in range(1, n + 1):
        nuevo = cuentas.copy()
        nuevo[k:] += cuentas[:-k]
        cuentas = nuevo
    return cuentas / cuentas.sum()


def wilcoxon_exacto(d):
    """p bilateral exacto de Wilcoxon. Con empates en |d| usa rangos medios y permutacion
    exacta de signos (2^n combinaciones, n <= 10 en esta campana)."""
    d = np.asarray([x for x in d if x != 0], dtype=float)
    n = len(d)
    if n == 0:
        return None, 0
    rangos = pd.Series(np.abs(d)).rank().to_numpy()
    w_obs = rangos[d > 0].sum()
    centro = rangos.sum() / 2
    if len(set(rangos)) == n:
        dist = distribucion_wilcoxon(n)
        w = int(round(w_obs))
        extremo = min(w, n * (n + 1) // 2 - w)
        p = min(1.0, 2 * dist[: extremo + 1].sum())
    else:
        total = 0
        extremos = 0
        for signos in itertools.product([0, 1], repeat=n):
            w = sum(r for r, s in zip(rangos, signos) if s)
            total += 1
            if abs(w - centro) >= abs(w_obs - centro) - 1e-9:
                extremos += 1
        p = extremos / total
    return p, n


def hodges_lehmann(d, confianza=0.95):
    """Estimador de Hodges-Lehmann de la diferencia pareada y su IC exacto (Walsh)."""
    d = np.asarray(d, dtype=float)
    n = len(d)
    walsh = np.sort([(d[i] + d[j]) / 2 for i in range(n) for j in range(i, n)])
    estimador = float(np.median(walsh))
    dist = distribucion_wilcoxon(n)
    acumulada = np.cumsum(dist)
    # c: mayor valor con P(W <= c) <= alfa/2; el IC es [A_(c+1), A_(M-c)] (1-indexado), con
    # cobertura 1 - 2 P(W <= c).
    c = int(np.searchsorted(acumulada, (1 - confianza) / 2 + 1e-12, side="right")) - 1
    if c < 0 or c >= len(walsh) - 1 - c:
        return estimador, float("nan"), float("nan")
    return estimador, float(walsh[c]), float(walsh[len(walsh) - 1 - c])


def biserial(d):
    """Correlacion biserial de rangos pareada: (W+ - W-) / (W+ + W-)."""
    d = np.asarray([x for x in d if x != 0], dtype=float)
    if len(d) == 0:
        return 0.0
    r = pd.Series(np.abs(d)).rank().to_numpy()
    positivos = r[d > 0].sum()
    negativos = r[d < 0].sum()
    return float((positivos - negativos) / (positivos + negativos))


def holm(pvalores):
    """Valores p ajustados por Holm, en el orden original; None se conserva."""
    indices = [i for i, p in enumerate(pvalores) if p is not None]
    orden = sorted(indices, key=lambda i: pvalores[i])
    ajustados = [None] * len(pvalores)
    maximo = 0.0
    m = len(orden)
    for k, i in enumerate(orden):
        maximo = max(maximo, min(1.0, (m - k) * pvalores[i]))
        ajustados[i] = maximo
    return ajustados


# --------------------------------------------------------------------- datos

def cargar(directorio):
    filas = [pd.read_csv(f) for f in sorted(glob.glob(os.path.join(directorio, "filas", "*.csv")))]
    if not filas:
        sys.exit("No hay corridas en " + directorio)
    df = pd.concat(filas, ignore_index=True)
    df["validez"] = (df.v1_invalidas == 0) & (df.v2_incoherentes == 0)
    df["pct_llamadas_en_presupuesto"] = 100.0 * (1 - df.llamadas_fuera_presupuesto / df.llamadas)
    cens = np.where(df.escenario == "5D", HORIZONTE_5D, HORIZONTE_COLAPSO)
    df["minuto_primer_incumplido_c"] = np.where(df.minuto_primer_incumplido < 0, cens,
                                                df.minuto_primer_incumplido)
    df["censurado"] = df.minuto_primer_incumplido < 0
    df["sin_incumplidos"] = df.pedidos_incumplidos == 0
    return df.sort_values(["escenario", "nivel", "variante", "replica"])


def series(directorio):
    salida = {}
    for f in glob.glob(os.path.join(directorio, "series", "COLAPSO_*.csv")):
        partes = os.path.basename(f)[:-4].split("_")
        salida[(partes[2], int(partes[3][1:]))] = pd.read_csv(f)
    return salida


# --------------------------------------------------------------------- contrastes

METRICAS_5D = [
    ("pedidos_incumplidos", "Pedidos incumplidos", "menor"),
    ("minuto_primer_incumplido_c", "Minuto del primer incumplido", "mayor"),
    ("pedidos_pendientes", "Pendientes al cierre", "menor"),
    ("costo_por_pedido", "Costo por pedido entregado (S/)", "menor"),
]


def contrastes(df):
    registros = []
    comparaciones = [("ALNS-C", "HGS", "principal"), ("ALNS-C", "ALNS-F", "arranque")]
    bloques = [("5D", n, METRICAS_5D) for n in NIVELES_5D]
    bloques.append(("COLAPSO", "N4", [("minuto_primer_incumplido_c", "Minuto de colapso", "mayor")]))
    for escenario, nivel, metricas in bloques:
        sub = df[(df.escenario == escenario) & (df.nivel == nivel)]
        for a, b, familia in comparaciones:
            pa = sub[sub.variante == a].set_index("replica")
            pb = sub[sub.variante == b].set_index("replica")
            comunes = sorted(set(pa.index) & set(pb.index))
            for col, etiqueta, mejor in metricas:
                if len(comunes) < 2:
                    continue
                d = (pa.loc[comunes, col] - pb.loc[comunes, col]).to_numpy(dtype=float)
                p, n_no_nulas = wilcoxon_exacto(d)
                hl, lo, hi = hodges_lehmann(d)
                registros.append({
                    "familia": familia, "escenario": escenario, "nivel": nivel,
                    "metrica": etiqueta, "columna": col, "sentido_mejor": mejor,
                    "a": a, "b": b, "pares": len(comunes), "no_nulas": n_no_nulas,
                    "mediana_a": float(np.median(pa.loc[comunes, col])),
                    "mediana_b": float(np.median(pb.loc[comunes, col])),
                    "a_gana": int(np.sum(d < 0) if mejor == "menor" else np.sum(d > 0)),
                    "b_gana": int(np.sum(d > 0) if mejor == "menor" else np.sum(d < 0)),
                    "empates": int(np.sum(d == 0)),
                    "hl": hl, "ic_inf": lo, "ic_sup": hi, "biserial": biserial(d), "p": p,
                })
    pruebas = pd.DataFrame(registros, columns=[
        "familia", "escenario", "nivel", "metrica", "columna", "sentido_mejor", "a", "b", "pares",
        "no_nulas", "mediana_a", "mediana_b", "a_gana", "b_gana", "empates", "hl", "ic_inf", "ic_sup",
        "biserial", "p"])
    pruebas["p_holm"] = None
    if pruebas.empty:
        pruebas["veredicto"] = None
        return pruebas
    for familia in pruebas.familia.unique():
        mascara = pruebas.familia == familia
        pruebas.loc[mascara, "p_holm"] = holm(list(pruebas.loc[mascara, "p"]))

    def veredicto(f):
        if f.p is None or (isinstance(f.p, float) and math.isnan(f.p)):
            return "sin diferencias (todas las diferencias son nulas)"
        if f.p_holm is not None and f.p_holm < ALFA:
            favorece_a = (f.hl < 0) if f.sentido_mejor == "menor" else (f.hl > 0)
            return f"significativa a favor de {f.a if favorece_a else f.b}"
        return "no significativa"

    pruebas["veredicto"] = pruebas.apply(veredicto, axis=1)
    return pruebas


# --------------------------------------------------------------------- tablas

def fmt(x, dec=1):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "–"
    return f"{x:,.{dec}f}".replace(",", " ")


def med_rango(s, dec=0):
    return f"{fmt(s.median(), dec)} [{fmt(s.min(), dec)}–{fmt(s.max(), dec)}]"


def tablas(df, pruebas, salida):
    t = []
    t.append("# Resultados de la campaña experimental\n")
    t.append(f"Corridas: {len(df)}. Commit: {', '.join(sorted(df.commit.astype(str).unique()))}. "
             f"Presupuesto por llamada: {', '.join(str(x) for x in sorted(df.presupuesto_ms.unique()))} ms.\n")

    t.append("\n## Tabla 1. Validez estructural y presupuesto (V-1, V-2, V-3)\n")
    t.append("| Escenario | Nivel | Variante | Corridas | Válidas (V-1 y V-2) | Llamadas | Llamadas dentro del presupuesto | ms mediana | ms P90 | ms máx |")
    t.append("|---|---|---|---|---|---|---|---|---|---|")
    for (esc, niv, var), g in df.groupby(["escenario", "nivel", "variante"]):
        t.append(f"| {esc} | {niv} | {var} | {len(g)} | {g.validez.sum()}/{len(g)} | {g.llamadas.sum()} | "
                 f"{fmt(100 * (1 - g.llamadas_fuera_presupuesto.sum() / g.llamadas.sum()), 2)} % | "
                 f"{fmt(g.ms_mediana.median(), 0)} | {fmt(g.ms_p90.median(), 0)} | {fmt(g.ms_max.max(), 0)} |")

    t.append("\n## Tabla 2. Cumplimiento de plazos en la simulación de 5 días\n")
    t.append("Mediana [mínimo–máximo] sobre las réplicas. \"Sin incumplidos\" cuenta las réplicas que "
             "terminaron los 5 días sin ningún pedido fuera de plazo; \">7200\" indica que la mediana "
             "no llegó a incumplir.\n")
    t.append("| Nivel | Pedidos por réplica | Variante | Sin incumplidos | Incumplidos | % cumplimiento | Primer incumplido (min) | Pendientes al cierre |")
    t.append("|---|---|---|---|---|---|---|---|")
    for niv in NIVELES_5D:
        for var in VARIANTES:
            g = df[(df.escenario == "5D") & (df.nivel == niv) & (df.variante == var)]
            if g.empty:
                continue
            mediana_primero = g.minuto_primer_incumplido_c.median()
            primero = ">7200" if mediana_primero >= HORIZONTE_5D else fmt(mediana_primero, 0)
            t.append(f"| {niv} | {fmt(g.pedidos_instancia.median(), 0)} | {var} | {g.sin_incumplidos.sum()}/{len(g)} | "
                     f"{med_rango(g.pedidos_incumplidos)} | {fmt(g.pct_cumplimiento.median(), 2)} | {primero} | "
                     f"{med_rango(g.pedidos_pendientes)} |")

    t.append("\n## Tabla 3. Costo de operación en la simulación de 5 días\n")
    t.append("| Nivel | Variante | Costo total (S/) | Costo por pedido entregado (S/) | Km recorridos | Tiempo de pared (min) |")
    t.append("|---|---|---|---|---|---|")
    for niv in NIVELES_5D:
        for var in VARIANTES:
            g = df[(df.escenario == "5D") & (df.nivel == niv) & (df.variante == var)]
            if g.empty:
                continue
            t.append(f"| {niv} | {var} | {med_rango(g.costo_total)} | {med_rango(g.costo_por_pedido, 2)} | "
                     f"{med_rango(g.km_total)} | {fmt(g.tiempo_pared_s.median() / 60, 1)} |")

    t.append("\n## Tabla 4. Escenario de colapso (N4)\n")
    t.append("| Variante | Réplicas | Minuto de colapso, mediana [mín–máx] | Día y hora mediana | Censuradas (sin colapso en 30 días) |")
    t.append("|---|---|---|---|---|")
    for var in VARIANTES:
        g = df[(df.escenario == "COLAPSO") & (df.variante == var)]
        if g.empty:
            continue
        m = g.minuto_primer_incumplido_c.median()
        t.append(f"| {var} | {len(g)} | {med_rango(g.minuto_primer_incumplido_c)} | "
                 f"día {int(m // 1440) + 1}, {int(m % 1440) // 60:02d}:{int(m % 60):02d} | {g.censurado.sum()} |")

    for familia, titulo in [("principal", "Tabla 5. Contrastes principales: ALNS-C frente a HGS"),
                            ("arranque", "Tabla 6. Efecto del arranque: ALNS-C frente a ALNS-F")]:
        t.append(f"\n## {titulo}\n")
        t.append("Diferencia = A − B por réplica. HL: estimador de Hodges-Lehmann con IC exacto del 95 %. "
                 "p: Wilcoxon exacto bilateral; p Holm: ajustado dentro de la familia.\n")
        t.append("| Escenario | Nivel | Métrica | Mediana A | Mediana B | A mejor / B mejor / empates | HL [IC 95 %] | r biserial | p | p Holm | Lectura |")
        t.append("|---|---|---|---|---|---|---|---|---|---|---|")
        for _, f in pruebas[pruebas.familia == familia].iterrows():
            dec = 2 if "Costo" in f.metrica else 0
            t.append(f"| {f.escenario} | {f.nivel} | {f.metrica} | {fmt(f.mediana_a, dec)} | {fmt(f.mediana_b, dec)} | "
                     f"{f.a_gana} / {f.b_gana} / {f.empates} | {fmt(f.hl, dec)} [{fmt(f.ic_inf, dec)}; {fmt(f.ic_sup, dec)}] | "
                     f"{fmt(f.biserial, 2)} | {fmt(f.p, 4) if f.p is not None else '–'} | "
                     f"{fmt(f.p_holm, 4) if f.p_holm is not None else '–'} | {f.veredicto} |")

    with open(os.path.join(salida, "tablas.md"), "w", encoding="utf-8") as w:
        w.write("\n".join(t) + "\n")


# --------------------------------------------------------------------- figuras

def estilo(ax, titulo, ylabel):
    ax.set_title(titulo, loc="left", fontsize=11, color=TINTA, pad=10)
    ax.set_ylabel(ylabel, color=TINTA_2, fontsize=9)
    ax.grid(axis="y", color=REJILLA, linewidth=0.8)
    ax.set_axisbelow(True)
    for lado in ("top", "right"):
        ax.spines[lado].set_visible(False)
    for lado in ("left", "bottom"):
        ax.spines[lado].set_color(REJILLA)
    ax.tick_params(colors=TINTA_2, labelsize=9)


def cajas_por_nivel(df, col, titulo, ylabel, archivo, salida):
    """Caja y puntos por nivel y variante: cada punto es una replica."""
    fig, ax = plt.subplots(figsize=(8, 4.2), dpi=160)
    ancho = 0.24
    rng = np.random.default_rng(0)
    for i, niv in enumerate(NIVELES_5D):
        for j, var in enumerate(VARIANTES):
            v = df[(df.escenario == "5D") & (df.nivel == niv) & (df.variante == var)][col].to_numpy()
            if len(v) == 0:
                continue
            x = i + (j - 1) * (ancho + 0.04)
            ax.boxplot(v, positions=[x], widths=ancho, patch_artist=True, showfliers=False,
                       boxprops=dict(facecolor=COLOR[var] + "33", edgecolor=COLOR[var], linewidth=1.2),
                       medianprops=dict(color=COLOR[var], linewidth=2),
                       whiskerprops=dict(color=COLOR[var]), capprops=dict(color=COLOR[var]))
            ax.scatter(x + rng.uniform(-ancho / 4, ancho / 4, len(v)), v, s=14, color=COLOR[var],
                       edgecolor="white", linewidth=0.6, zorder=3)
    ax.set_xticks(range(len(NIVELES_5D)))
    ax.set_xticklabels([f"{n}\n({p})" for n, p in zip(NIVELES_5D, ["25 %", "50 %", "75 %"])])
    estilo(ax, titulo, ylabel)
    ax.legend(handles=[plt.Line2D([], [], color=COLOR[v], marker="s", linestyle="", markersize=8,
                                  label=NOMBRE[v]) for v in VARIANTES],
              frameon=False, fontsize=9, loc="upper left", ncols=3, bbox_to_anchor=(0, -0.14))
    fig.tight_layout()
    fig.savefig(os.path.join(salida, archivo))
    plt.close(fig)


def escalabilidad(df, salida):
    """Tres paneles con el mismo eje x (nivel): cumplimiento, costo por pedido, tiempo por llamada."""
    fig, ejes = plt.subplots(1, 3, figsize=(11, 3.6), dpi=160)
    paneles = [("pct_cumplimiento", "% de cumplimiento de plazo", "%"),
               ("costo_por_pedido", "Costo por pedido entregado", "S/ por pedido"),
               ("ms_p90", "Tiempo por llamada (P90)", "ms")]
    for ax, (col, titulo, ylabel) in zip(ejes, paneles):
        for var in VARIANTES:
            g = df[(df.escenario == "5D") & (df.variante == var)].groupby("nivel")[col]
            med = g.median().reindex(NIVELES_5D)
            ax.plot(range(3), med.to_numpy(), color=COLOR[var], linewidth=2, marker="o", markersize=8,
                    markeredgecolor="white", markeredgewidth=1.5, label=NOMBRE[var])
            ax.fill_between(range(3), g.quantile(0.1).reindex(NIVELES_5D).to_numpy(),
                            g.quantile(0.9).reindex(NIVELES_5D).to_numpy(), color=COLOR[var], alpha=0.10,
                            linewidth=0)
        ax.set_xticks(range(3))
        ax.set_xticklabels(NIVELES_5D)
        estilo(ax, titulo, ylabel)
    ejes[0].legend(frameon=False, fontsize=8, loc="lower left")
    fig.suptitle("Escalabilidad: mediana por nivel (banda: percentiles 10–90)", x=0.01, ha="left",
                 fontsize=10, color=TINTA_2)
    fig.tight_layout()
    fig.savefig(os.path.join(salida, "f3_escalabilidad.png"))
    plt.close(fig)


def colapso(df, series_colapso, salida):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4), dpi=160, gridspec_kw={"width_ratios": [1, 1.6]})
    rng = np.random.default_rng(1)
    for j, var in enumerate(VARIANTES):
        g = df[(df.escenario == "COLAPSO") & (df.variante == var)]
        if g.empty:
            continue
        horas = g.minuto_primer_incumplido_c.to_numpy() / 60
        ax1.boxplot(horas, positions=[j], widths=0.5, patch_artist=True, showfliers=False,
                    boxprops=dict(facecolor=COLOR[var] + "33", edgecolor=COLOR[var], linewidth=1.2),
                    medianprops=dict(color=COLOR[var], linewidth=2),
                    whiskerprops=dict(color=COLOR[var]), capprops=dict(color=COLOR[var]))
        ax1.scatter(j + rng.uniform(-0.12, 0.12, len(horas)), horas, s=16, color=COLOR[var],
                    edgecolor="white", linewidth=0.6, zorder=3)
    ax1.set_xticks(range(3))
    ax1.set_xticklabels([NOMBRE[v].replace(" arranque", "\narranque") for v in VARIANTES])
    estilo(ax1, "Instante de colapso (N4)", "horas simuladas hasta el primer incumplido")
    for var in VARIANTES:
        curvas = [s for (v, _), s in series_colapso.items() if v == var]
        for s in curvas:
            ax2.plot(s.minuto / 60, s.pedidos_en_fotografia, color=COLOR[var], linewidth=1, alpha=0.35)
        if curvas:
            ax2.plot([], [], color=COLOR[var], linewidth=2, label=NOMBRE[var])
    estilo(ax2, "Pedidos pendientes en cada replanificación (N4)", "pedidos pendientes")
    ax2.set_xlabel("horas simuladas", color=TINTA_2, fontsize=9)
    ax2.legend(frameon=False, fontsize=8, loc="upper left")
    fig.tight_layout()
    fig.savefig(os.path.join(salida, "f4_colapso.png"))
    plt.close(fig)


def diferencias(pruebas, df, salida):
    """Diferencias pareadas ALNS-C menos HGS por replica, con el estimador HL y su IC."""
    metricas = [("pedidos_incumplidos", "Incumplidos (ALNS-C − HGS)"),
                ("costo_por_pedido", "Costo por pedido, S/ (ALNS-C − HGS)")]
    fig, ejes = plt.subplots(1, 2, figsize=(11, 3.8), dpi=160)
    for ax, (col, titulo) in zip(ejes, metricas):
        for i, niv in enumerate(NIVELES_5D):
            sub = df[(df.escenario == "5D") & (df.nivel == niv)]
            a = sub[sub.variante == "ALNS-C"].set_index("replica")[col]
            b = sub[sub.variante == "HGS"].set_index("replica")[col]
            comunes = sorted(set(a.index) & set(b.index))
            if not comunes:
                continue
            d = (a.loc[comunes] - b.loc[comunes]).to_numpy()
            ax.scatter(np.full(len(d), i) + np.linspace(-0.15, 0.15, len(d)), d, s=16, color=TINTA_2, zorder=3)
            f = pruebas[(pruebas.familia == "principal") & (pruebas.nivel == niv) & (pruebas.columna == col)]
            if not f.empty and not math.isnan(f.iloc[0].ic_inf):
                ax.errorbar(i + 0.3, f.iloc[0].hl, yerr=[[f.iloc[0].hl - f.iloc[0].ic_inf],
                                                          [f.iloc[0].ic_sup - f.iloc[0].hl]],
                            fmt="D", color=COLOR["ALNS-C"], markersize=7, capsize=4, linewidth=2)
        ax.axhline(0, color=TINTA_2, linewidth=1)
        ax.set_xticks(range(3))
        ax.set_xticklabels(NIVELES_5D)
        estilo(ax, titulo, "diferencia por réplica")
    ejes[0].text(0.01, 0.02, "Bajo cero: ALNS-C mejor · rombo: Hodges-Lehmann e IC 95 %",
                 transform=ejes[0].transAxes, fontsize=8, color=TINTA_2)
    fig.tight_layout()
    fig.savefig(os.path.join(salida, "f5_diferencias_pareadas.png"))
    plt.close(fig)


def main():
    directorio = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "resultados")
    df = cargar(directorio)
    df.to_csv(os.path.join(directorio, "campana.csv"), index=False)
    pruebas = contrastes(df)
    pruebas.to_csv(os.path.join(directorio, "pruebas.csv"), index=False)
    tablas(df, pruebas, directorio)
    figuras = os.path.join(directorio, "figuras")
    os.makedirs(figuras, exist_ok=True)
    cajas_por_nivel(df, "pedidos_incumplidos", "Pedidos fuera de plazo en 5 días, por nivel",
                    "pedidos incumplidos por réplica", "f1_incumplidos.png", figuras)
    cajas_por_nivel(df, "costo_por_pedido", "Costo de operación por pedido entregado, por nivel",
                    "S/ por pedido entregado", "f2_costo_por_pedido.png", figuras)
    escalabilidad(df, figuras)
    colapso(df, series(directorio), figuras)
    diferencias(pruebas, df, figuras)
    print(f"{len(df)} corridas analizadas; tablas y figuras en {directorio}")


if __name__ == "__main__":
    main()
