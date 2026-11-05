package org.kindbox.service.dto;

import java.time.LocalDateTime;
import org.kindbox.core.simulacion.MetricasSimulacion;
import org.kindbox.core.simulacion.PlanVigente;

// Ultima planificacion estable de una corrida u operacion; plan es nulo si aun no se planifico nada.
public record ReportePlan(String id, String escenario, String estado, boolean terminado, LocalDateTime fechaHora,
                          PlanVigente plan, MetricasSimulacion metricas) {
}
