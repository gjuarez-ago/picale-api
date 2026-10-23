package com.metricol.api.service.campaign;

import java.util.List;
import com.metricol.api.entity.VisualIdentity;

/** Contrato compartido por imágenes y diapositivas; se construye antes de gastar en dibujar. */
public record CreativeSpecification(
        String objective, String audience, String message, String layout, String scene,
        String exactText, List<String> palette, String style, String avoid, String preserve, String adaptable,
        VisualIdentity.LogoTreatment logoTreatment, VisualIdentity.LogoBackground logoBackground,
        boolean allowShapeChange, int identityRevision,
        int slide, int totalSlides, List<ReferenceRole> references) {
    public record ReferenceRole(int image, String role) {}
}
