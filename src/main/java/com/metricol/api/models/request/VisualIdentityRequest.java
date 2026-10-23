package com.metricol.api.models.request;

import java.util.List;
import java.util.UUID;
import com.metricol.api.entity.VisualIdentity.LogoTreatment;
import com.metricol.api.entity.VisualIdentity.LogoBackground;
import jakarta.validation.constraints.*;

/** PATCH semántico: null conserva; lista vacía limpia. Revisión evita perder cambios concurrentes. */
public record VisualIdentityRequest(
        @NotNull @Min(0) Integer expectedRevision,
        @AssertTrue(message = "Confirma la propuesta visual antes de guardarla.") boolean confirmed,
        @Size(max = 6) List<@Pattern(regexp = "#[0-9a-fA-F]{6}") String> colors,
        @Size(max = 600) String style,
        @Size(max = 4) List<@NotNull UUID> approvedReferences,
        @Size(max = 12) List<@NotNull UUID> rejectedReferences,
        @Size(max = 3) List<@NotNull UUID> chispaReferences,
        @Size(max = 500) String avoid, @Size(max = 500) String preserve, @Size(max = 500) String adaptable,
        LogoTreatment logoTreatment, LogoBackground logoBackground, Boolean allowShapeChange) {}
