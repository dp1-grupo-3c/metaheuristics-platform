# Resultados de la campaña experimental

Corridas: 120. Commit: 45890e5, 539370b. Presupuesto por llamada: 2000 ms.


## Tabla 1. Validez estructural y presupuesto (V-1, V-2, V-3)

| Escenario | Nivel | Variante | Corridas | Válidas (V-1 y V-2) | Llamadas | Llamadas dentro del presupuesto | ms mediana | ms P90 | ms máx |
|---|---|---|---|---|---|---|---|---|---|
| 5D | N1 | ALNS-C | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 965 |
| 5D | N1 | ALNS-F | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 958 |
| 5D | N1 | HGS | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 955 |
| 5D | N2 | ALNS-C | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 960 |
| 5D | N2 | ALNS-F | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 956 |
| 5D | N2 | HGS | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 955 |
| 5D | N3 | ALNS-C | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 956 |
| 5D | N3 | ALNS-F | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 955 |
| 5D | N3 | HGS | 10 | 10/10 | 2660 | 100.00 % | 1 950 | 1 950 | 1 954 |
| COLAPSO | N4 | ALNS-C | 10 | 10/10 | 1133 | 100.00 % | 1 950 | 1 951 | 1 956 |
| COLAPSO | N4 | ALNS-F | 10 | 10/10 | 1059 | 100.00 % | 1 950 | 1 951 | 1 956 |
| COLAPSO | N4 | HGS | 10 | 10/10 | 1314 | 100.00 % | 1 950 | 1 950 | 1 956 |

## Tabla 2. Cumplimiento de plazos en la simulación de 5 días

Mediana [mínimo–máximo] sobre las réplicas. "Sin incumplidos" cuenta las réplicas que terminaron los 5 días sin ningún pedido fuera de plazo; ">7200" indica que la mediana no llegó a incumplir.

| Nivel | Pedidos por réplica | Variante | Sin incumplidos | Incumplidos | % cumplimiento | Primer incumplido (min) | Pendientes al cierre |
|---|---|---|---|---|---|---|---|
| N1 | 494 | ALNS-C | 10/10 | 0 [0–0] | 100.00 | >7200 | 3 [2–5] |
| N1 | 494 | ALNS-F | 9/10 | 0 [0–1] | 100.00 | >7200 | 3 [2–5] |
| N1 | 494 | HGS | 7/10 | 0 [0–2] | 100.00 | >7200 | 3 [2–5] |
| N2 | 982 | ALNS-C | 5/10 | 0 [0–2] | 99.95 | 5 740 | 11 [6–14] |
| N2 | 982 | ALNS-F | 2/10 | 1 [0–1] | 99.90 | 4 878 | 10 [8–14] |
| N2 | 982 | HGS | 5/10 | 0 [0–4] | 99.95 | 6 951 | 11 [6–15] |
| N3 | 1 486 | ALNS-C | 1/10 | 3 [0–7] | 99.79 | 897 | 29 [22–33] |
| N3 | 1 486 | ALNS-F | 0/10 | 6 [4–10] | 99.55 | 840 | 32 [24–34] |
| N3 | 1 486 | HGS | 0/10 | 4 [1–5] | 99.69 | 904 | 34 [29–37] |

## Tabla 3. Costo de operación en la simulación de 5 días

| Nivel | Variante | Costo total (S/) | Costo por pedido entregado (S/) | Km recorridos | Tiempo de pared (min) |
|---|---|---|---|---|---|
| N1 | ALNS-C | 52 848 [51 098–57 176] | 108.06 [102.51–115.27] | 9 332 [9 147–10 024] | 8.3 |
| N1 | ALNS-F | 53 800 [50 782–58 632] | 109.59 [106.24–116.30] | 9 575 [9 067–10 301] | 8.2 |
| N1 | HGS | 51 153 [48 422–57 133] | 105.01 [97.62–113.13] | 8 867 [8 480–9 489] | 8.3 |
| N2 | ALNS-C | 89 698 [87 821–92 759] | 91.64 [89.27–95.56] | 15 310 [14 846–15 911] | 8.5 |
| N2 | ALNS-F | 90 860 [87 930–93 973] | 92.90 [90.88–97.28] | 15 574 [15 167–16 328] | 8.4 |
| N2 | HGS | 94 115 [90 723–101 937] | 96.15 [93.92–101.17] | 15 552 [14 823–16 795] | 8.4 |
| N3 | ALNS-C | 117 466 [115 423–123 776] | 81.50 [79.70–83.63] | 20 228 [19 620–21 132] | 8.5 |
| N3 | ALNS-F | 117 385 [114 450–121 658] | 81.60 [79.59–83.67] | 20 181 [19 558–20 986] | 8.5 |
| N3 | HGS | 133 182 [128 979–140 091] | 91.45 [89.17–95.04] | 22 027 [21 303–22 967] | 8.5 |

## Tabla 4. Escenario de colapso (N4)

| Variante | Réplicas | Minuto de colapso, mediana [mín–máx] | Día y hora mediana | Censuradas (sin colapso en 30 días) |
|---|---|---|---|---|
| ALNS-C | 10 | 4 250 [897–5 506] | día 3, 22:50 | 0 |
| ALNS-F | 10 | 3 952 [840–5 187] | día 3, 17:52 | 0 |
| HGS | 10 | 3 655 [840–10 570] | día 3, 12:55 | 0 |

## Tabla 5. Contrastes principales: ALNS-C frente a HGS

Diferencia = A − B por réplica. HL: estimador de Hodges-Lehmann con IC exacto del 95 %. p: Wilcoxon exacto bilateral; p Holm: ajustado dentro de la familia.

| Escenario | Nivel | Métrica | Mediana A | Mediana B | A mejor / B mejor / empates | HL [IC 95 %] | r biserial | p | p Holm | Lectura |
|---|---|---|---|---|---|---|---|---|---|---|
| 5D | N1 | Pedidos incumplidos | 0 | 0 | 3 / 0 / 7 | 0 [-1; 0] | -1.00 | 0.2500 | 1.0000 | no significativa |
| 5D | N1 | Minuto del primer incumplido | 7 200 | 7 200 | 3 / 0 / 7 | 0 [0; 3 152] | 1.00 | 0.2500 | 1.0000 | no significativa |
| 5D | N1 | Pendientes al cierre | 3 | 3 | 2 / 2 / 6 | 0 [0; 1] | 0.20 | 1.0000 | 1.0000 | no significativa |
| 5D | N1 | Costo por pedido entregado (S/) | 108.06 | 105.01 | 1 / 9 / 0 | 3.63 [0.81; 6.11] | 0.82 | 0.0195 | 0.1953 | no significativa |
| 5D | N2 | Pedidos incumplidos | 0 | 0 | 3 / 3 / 4 | 0 [-2; 1] | -0.05 | 1.0000 | 1.0000 | no significativa |
| 5D | N2 | Minuto del primer incumplido | 5 740 | 6 951 | 4 / 4 / 2 | 244 [-2 901; 3 201] | 0.11 | 0.8125 | 1.0000 | no significativa |
| 5D | N2 | Pendientes al cierre | 11 | 11 | 5 / 2 / 3 | 0 [-2; 0] | -0.46 | 0.3281 | 1.0000 | no significativa |
| 5D | N2 | Costo por pedido entregado (S/) | 91.64 | 96.15 | 9 / 1 / 0 | -4.74 [-7.11; -2.56] | -0.96 | 0.0039 | 0.0430 | significativa a favor de ALNS-C |
| 5D | N3 | Pedidos incumplidos | 3 | 4 | 3 / 3 / 4 | 0 [-2; 1] | -0.05 | 1.0000 | 1.0000 | no significativa |
| 5D | N3 | Minuto del primer incumplido | 897 | 904 | 5 / 4 / 1 | 72 [-179; 1 641] | 0.29 | 0.4961 | 1.0000 | no significativa |
| 5D | N3 | Pendientes al cierre | 29 | 34 | 10 / 0 / 0 | -6 [-8; -4] | -1.00 | 0.0020 | 0.0254 | significativa a favor de ALNS-C |
| 5D | N3 | Costo por pedido entregado (S/) | 81.50 | 91.45 | 10 / 0 / 0 | -10.45 [-11.15; -9.75] | -1.00 | 0.0020 | 0.0254 | significativa a favor de ALNS-C |
| COLAPSO | N4 | Minuto de colapso | 4 250 | 3 655 | 6 / 3 / 1 | 114 [-3 937; 1 799] | 0.16 | 0.7148 | 1.0000 | no significativa |

## Tabla 6. Efecto del arranque: ALNS-C frente a ALNS-F

Diferencia = A − B por réplica. HL: estimador de Hodges-Lehmann con IC exacto del 95 %. p: Wilcoxon exacto bilateral; p Holm: ajustado dentro de la familia.

| Escenario | Nivel | Métrica | Mediana A | Mediana B | A mejor / B mejor / empates | HL [IC 95 %] | r biserial | p | p Holm | Lectura |
|---|---|---|---|---|---|---|---|---|---|---|
| 5D | N1 | Pedidos incumplidos | 0 | 0 | 1 / 0 / 9 | 0 [0; 0] | -1.00 | 1.0000 | 1.0000 | no significativa |
| 5D | N1 | Minuto del primer incumplido | 7 200 | 7 200 | 1 / 0 / 9 | 0 [0; 1 454] | 1.00 | 1.0000 | 1.0000 | no significativa |
| 5D | N1 | Pendientes al cierre | 3 | 3 | 2 / 1 / 7 | 0 [-1; 0] | -0.50 | 0.7500 | 1.0000 | no significativa |
| 5D | N1 | Costo por pedido entregado (S/) | 108.06 | 109.59 | 6 / 4 / 0 | -1.82 [-4.67; 0.67] | -0.49 | 0.1934 | 1.0000 | no significativa |
| 5D | N2 | Pedidos incumplidos | 0 | 1 | 3 / 1 / 6 | 0 [0; 0] | -0.50 | 0.6250 | 1.0000 | no significativa |
| 5D | N2 | Minuto del primer incumplido | 5 740 | 4 878 | 4 / 1 / 5 | 249 [-608; 1 691] | 0.33 | 0.6250 | 1.0000 | no significativa |
| 5D | N2 | Pendientes al cierre | 11 | 10 | 3 / 4 / 3 | 0 [-1; 1] | 0.00 | 1.0000 | 1.0000 | no significativa |
| 5D | N2 | Costo por pedido entregado (S/) | 91.64 | 92.90 | 9 / 1 / 0 | -1.43 [-2.20; -0.08] | -0.78 | 0.0273 | 0.3281 | no significativa |
| 5D | N3 | Pedidos incumplidos | 3 | 6 | 10 / 0 / 0 | -3 [-4; -2] | -1.00 | 0.0020 | 0.0254 | significativa a favor de ALNS-C |
| 5D | N3 | Minuto del primer incumplido | 897 | 840 | 5 / 1 / 4 | 279 [0; 3 180] | 0.90 | 0.0625 | 0.6875 | no significativa |
| 5D | N3 | Pendientes al cierre | 29 | 32 | 8 / 2 / 0 | -2 [-4; 0] | -0.53 | 0.1543 | 1.0000 | no significativa |
| 5D | N3 | Costo por pedido entregado (S/) | 81.50 | 81.60 | 5 / 5 / 0 | 0.18 [-0.48; 0.98] | 0.16 | 0.6953 | 1.0000 | no significativa |
| COLAPSO | N4 | Minuto de colapso | 4 250 | 3 952 | 3 / 4 / 3 | 0 [-1 648; 2 236] | 0.07 | 0.9375 | 1.0000 | no significativa |
