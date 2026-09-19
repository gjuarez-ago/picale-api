package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lo que la pantalla de Facturación necesita: si hay cobros, cómo va la licencia
 * del espacio, cuántos créditos quedan y qué se puede comprar.
 */
public record BillingSummaryResponse(
        /** Con {@code false} no hay licencias ni créditos: todo funciona como siempre. */
        boolean enabled,
        /** «PRUEBA» o «REAL»: con cuál modo de Stripe se está hablando. Nulo si no hay cobros. */
        String mode,
        String currency,
        /** El precio de una licencia, tal como está en Stripe. Nulo si no se pudo leer. */
        Price licensePrice,
        /** El espacio en el que se está trabajando. */
        WorkspaceBilling workspace,
        /** Todas las licencias de la organización. Solo para quien la administra; si no, vacío. */
        List<LicenseView> licenses,
        List<Pack> packs) {

    public record Price(long amountMinor, String currency, String interval) {
    }

    public record WorkspaceBilling(UUID workspaceId, String name, LicenseView license, Credits credits) {
    }

    /** Una licencia, lista para pintarse. {@code usable} ya trae la cuenta hecha. */
    public record LicenseView(
            UUID workspaceId,
            String workspaceName,
            /** TRIALING, ACTIVE, PAST_DUE o ENDED. */
            String status,
            boolean usable,
            LocalDateTime trialEndsAt,
            LocalDateTime currentPeriodEnd,
            LocalDateTime graceUntil,
            /** No se renueva: se usa hasta {@code currentPeriodEnd}. */
            boolean cancelAtPeriodEnd,
            boolean hasSubscription) {
    }

    public record Credits(int monthly, int pack, int total, LocalDateTime monthlyExpiresAt) {
    }

    /** Un paquete de créditos; {@code price} es el de Stripe (nulo si no se pudo leer). */
    public record Pack(String code, String name, int credits, Price price) {
    }
}
