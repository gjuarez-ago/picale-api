package com.metricol.api.service.ai;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.AiUsageRepository;
import com.metricol.api.service.limits.LimitesConfigurables;

/**
 * El tope de llamadas a la IA por workspace y día.
 *
 * <p>Cada llamada a OpenAI cuesta dinero, y hasta ahora solo se anotaba: un
 * cliente —o un bucle en una app con un bug— podía quemar la cuenta en una
 * tarde sin que nada lo frenara. Esto se pregunta antes de cada llamada y dice
 * no cuando el workspace ya gastó lo suyo hoy.
 *
 * <p>Cuenta llamadas, no dólares, a propósito: el costo depende del modelo y
 * del precio del momento, y un tope en dólares se movería con ellos; cien
 * llamadas son cien llamadas. Y usa la tabla {@code ai_usage} que ya existe
 * en vez de un contador aparte: para esto no hace falta atomicidad —pasarse
 * por una llamada en una carrera no bloquea a nadie— y así no hay dos cuentas
 * que puedan separarse.
 */
@Component
public class AiQuotaGuard {

    private final AiUsageRepository usos;
    private final LimitesConfigurables limites;
    private final TenantIdentifierResolver tenants;

    public AiQuotaGuard(AiUsageRepository usos, LimitesConfigurables limites, TenantIdentifierResolver tenants) {
        this.usos = usos;
        this.limites = limites;
        this.tenants = tenants;
    }

    /** Lanza {@link QuotaExceededException} si el workspace actual ya agotó su día. */
    public void exigirCupo() {
        int tope = limites.maxIaPorDia();
        if (tope <= 0) {
            return;
        }

        UUID workspace = workspaceActual();
        if (workspace == null) {
            return; // Sin workspace no hay a quién contarle; no pasa en una petición autenticada.
        }

        LocalDate hoy = LocalDate.now();
        long hechas = usos.countByWorkspaceIdAndCreatedAtBetween(
                workspace, hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay());

        if (hechas >= tope) {
            throw new QuotaExceededException("AI_QUOTA_EXCEEDED",
                    "Llegaste al tope de " + tope + " usos de la IA por hoy. "
                            + "Mañana se reinicia; mientras, puedes escribir el texto tú.");
        }
    }

    private UUID workspaceActual() {
        String tenant = tenants.resolveCurrentTenantIdentifier();
        if (tenant == null || TenantIdentifierResolver.GLOBAL_TENANT.equals(tenant)) {
            return null;
        }
        try {
            return UUID.fromString(tenant);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
