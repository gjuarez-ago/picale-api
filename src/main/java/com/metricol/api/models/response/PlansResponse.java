package com.metricol.api.models.response;

import java.util.List;

/**
 * Lo que enseña la página pública de planes: precios de lista, la prueba y los
 * paquetes de créditos. No lleva nada de nadie, así que se puede pedir sin
 * sesión, y sale de la tabla de ajustes: cambiar un precio no requiere desplegar.
 */
public record PlansResponse(
        String currency,
        /** Los precios ya incluyen el IVA (es lo que paga el cliente). Lo cambia un ajuste, no el código. */
        boolean taxIncluded,
        /** Días gratis al registrarse. 0 = sin prueba. */
        int trialDays,
        /** Créditos de imagen que trae cada licencia cada mes. */
        int monthlyCredits,
        /** Días que se sigue usando un espacio después de un cobro fallido, antes de archivarlo. */
        int graceDays,
        /** Primer negocio, en la unidad menor de la moneda (34900 = $349.00). */
        int licenseMinor,
        /** Cada negocio adicional. */
        int extraMinor,
        List<CreditPackPlan> packs) {

    public record CreditPackPlan(String code, String name, int credits, int priceMinor) {
    }
}
