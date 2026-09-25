package com.metricol.api.models.response.root;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Una organización con todo lo que la administración de la plataforma puede
 * ver de ella: quién es de ella, qué espacios tiene, y de cada espacio su
 * licencia, sus créditos y quién entra.
 */
public record OrganizacionDetalleResponse(
        UUID id,
        String name,
        boolean sinLimites,
        int maxWorkspaces,
        String stripeCustomerId,
        LocalDateTime createdAt,
        List<Miembro> miembros,
        List<Espacio> espacios) {

    /** Quien pertenece a la organización, con su papel en ella. */
    public record Miembro(UUID userId, String name, String email, String orgRole, List<String> orgPermissions,
            boolean root, UUID workspaceActivoId, LocalDateTime desde) {
    }

    public record Espacio(UUID id, String name, String giro, String ciudad, String logoUrl, LocalDateTime createdAt,
            LocalDateTime archivedAt, boolean perfilCompleto, Licencia licencia, Creditos creditos,
            List<MiembroDeEspacio> miembros) {
    }

    /**
     * La licencia tal cual está, sin resumir: quien administra la plataforma
     * necesita ver las tres fechas para saber cuál mover.
     *
     * @param vigenteHasta la fecha que manda según el estado (fin de prueba,
     *                     fin de periodo o fin de gracia)
     * @param hasSubscription si la lleva Stripe; entonces lo que se cambie a
     *                     mano puede pisarlo el siguiente aviso de Stripe
     */
    public record Licencia(UUID id, String status, boolean usable, LocalDateTime trialEndsAt,
            LocalDateTime currentPeriodEnd, LocalDateTime graceUntil, boolean cancelAtPeriodEnd,
            boolean hasSubscription, boolean pricedAsExtra, boolean archivedBySweep, LocalDateTime vigenteHasta,
            LocalDateTime updatedAt) {
    }

    public record Creditos(int monthly, int pack, int total, LocalDateTime monthlyExpiresAt) {
    }

    public record MiembroDeEspacio(UUID userId, String name, String email, String role, List<String> permisos) {
    }
}
