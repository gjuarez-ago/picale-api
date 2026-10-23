package com.metricol.api.entity;

import java.util.List;
import java.util.UUID;

/** Preferencias confirmadas; los originales se referencian, nunca se sobrescriben. */
public record VisualIdentity(
        List<String> colors, String style, List<UUID> approvedReferences, List<UUID> rejectedReferences,
        List<UUID> chispaReferences, String avoid, String preserve, String adaptable,
        LogoTreatment logoTreatment, LogoBackground logoBackground, boolean allowShapeChange,
        int revision, String confirmedAt, UUID confirmedBy) {
    public enum LogoTreatment { ORIGINAL, INTEGRATED }
    public enum LogoBackground { PRESERVE, REMOVE_SOLID }
    public static final VisualIdentity EMPTY = new VisualIdentity(List.of(), null, List.of(), List.of(),
            List.of(), null, null, null, LogoTreatment.ORIGINAL, LogoBackground.PRESERVE, false, 0, null, null);
    public VisualIdentity {
        colors = colors == null ? List.of() : List.copyOf(colors);
        approvedReferences = approvedReferences == null ? List.of() : List.copyOf(approvedReferences);
        rejectedReferences = rejectedReferences == null ? List.of() : List.copyOf(rejectedReferences);
        chispaReferences = chispaReferences == null ? List.of() : List.copyOf(chispaReferences);
        logoTreatment = logoTreatment == null ? LogoTreatment.ORIGINAL : logoTreatment;
        logoBackground = logoBackground == null ? LogoBackground.PRESERVE : logoBackground;
    }
}
