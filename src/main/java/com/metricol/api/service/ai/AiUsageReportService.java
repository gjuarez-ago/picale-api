package com.metricol.api.service.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Workspace;
import com.metricol.api.models.response.AiUsageReportResponse;
import com.metricol.api.models.response.AiUsageReportResponse.ConsumoWorkspace;
import com.metricol.api.repository.AiUsageRepository;
import com.metricol.api.repository.WorkspaceRepository;

/** Arma el reporte de gasto en IA por workspace. */
@Service
public class AiUsageReportService {

    private final AiUsageRepository usos;
    private final WorkspaceRepository workspaces;

    public AiUsageReportService(AiUsageRepository usos, WorkspaceRepository workspaces) {
        this.usos = usos;
        this.workspaces = workspaces;
    }

    /** {@code desde} y {@code hasta} son días, los dos incluidos. */
    @Transactional(readOnly = true)
    public AiUsageReportResponse generar(LocalDate desde, LocalDate hasta) {
        LocalDateTime inicio = desde.atStartOfDay();
        LocalDateTime fin = hasta.plusDays(1).atStartOfDay();

        List<AiUsageRepository.PorWorkspace> filas = usos.gastoPorWorkspace(inicio, fin);

        // HashMap y no Map.of: la llave puede ser null (lo que no venía de
        // ningún workspace), y los mapas inmutables no la admiten.
        Map<UUID, Map<String, BigDecimal>> porOperacion = new HashMap<>();
        for (AiUsageRepository.PorOperacion fila : usos.gastoPorOperacion(inicio, fin)) {
            porOperacion.computeIfAbsent(fila.getWorkspaceId(), id -> new LinkedHashMap<>())
                    .put(fila.getOperacion().name(), dinero(fila.getCostoUsd()));
        }

        Map<UUID, String> nombres = new HashMap<>();
        List<UUID> ids = filas.stream()
                .map(AiUsageRepository.PorWorkspace::getWorkspaceId)
                .filter(Objects::nonNull)
                .toList();
        for (Workspace workspace : workspaces.findAllById(ids)) {
            nombres.put(workspace.getId(), workspace.getName());
        }

        List<ConsumoWorkspace> consumos = new ArrayList<>();
        long llamadas = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (AiUsageRepository.PorWorkspace fila : filas) {
            UUID id = fila.getWorkspaceId();
            BigDecimal costo = dinero(fila.getCostoUsd());
            consumos.add(new ConsumoWorkspace(
                    id,
                    nombreDe(id, nombres),
                    numero(fila.getLlamadas()),
                    numero(fila.getTokensEntrada()),
                    numero(fila.getTokensSalida()),
                    costo,
                    porOperacion.getOrDefault(id, Map.of())));
            llamadas += numero(fila.getLlamadas());
            total = total.add(costo);
        }

        return new AiUsageReportResponse(desde, hasta, llamadas, total, consumos);
    }

    private static String nombreDe(UUID id, Map<UUID, String> nombres) {
        if (id == null) {
            return "Sin workspace";
        }
        // Un workspace borrado sigue apareciendo: lo que gastó, lo gastó.
        return nombres.getOrDefault(id, "(workspace borrado)");
    }

    private static long numero(Long valor) {
        return valor == null ? 0 : valor;
    }

    private static BigDecimal dinero(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }
}
