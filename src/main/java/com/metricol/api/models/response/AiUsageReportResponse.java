package com.metricol.api.models.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que se gastó en IA en un periodo, workspace por workspace.
 *
 * @param desde      primer día del periodo, incluido
 * @param hasta      último día del periodo, incluido
 * @param llamadas   llamadas a OpenAI de todos los workspaces
 * @param costoUsd   gasto total del periodo
 * @param workspaces del que más gasta al que menos
 */
public record AiUsageReportResponse(
        LocalDate desde,
        LocalDate hasta,
        long llamadas,
        BigDecimal costoUsd,
        List<ConsumoWorkspace> workspaces) {

    /**
     * @param workspaceId       {@code null} para lo que no venía de ningún workspace
     * @param costoPorOperacion el gasto partido por operación (REDACTAR, AJUSTAR...)
     */
    public record ConsumoWorkspace(
            UUID workspaceId,
            String nombre,
            long llamadas,
            long tokensEntrada,
            long tokensSalida,
            BigDecimal costoUsd,
            Map<String, BigDecimal> costoPorOperacion) {
    }
}
