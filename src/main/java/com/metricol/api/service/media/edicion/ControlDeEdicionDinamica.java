package com.metricol.api.service.media.edicion;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metricol.api.config.EdicionDinamicaProperties;

@Component
public class ControlDeEdicionDinamica {
    public enum Decision {
        DISABLED, WORKSPACE_NOT_ENABLED, PROFILE_OFF, ALREADY_EDITED, ANALYSIS_ALLOWED
    }

    private final EdicionDinamicaProperties properties;

    public ControlDeEdicionDinamica(EdicionDinamicaProperties properties) {
        this.properties = properties;
    }

    /** Permite analizar/preparar, no indica disponibilidad del detector ni del render. */
    public Decision evaluar(UUID workspaceId, PerfilEdicionDinamica profile, boolean alreadyEdited) {
        if (!properties.isEnabled()) return Decision.DISABLED;
        if (workspaceId == null || properties.getPilotWorkspaces() == null
                || !properties.getPilotWorkspaces().contains(workspaceId)) {
            return Decision.WORKSPACE_NOT_ENABLED;
        }
        if (profile == null || profile == PerfilEdicionDinamica.OFF) return Decision.PROFILE_OFF;
        if (alreadyEdited) return Decision.ALREADY_EDITED;
        return Decision.ANALYSIS_ALLOWED;
    }
}
