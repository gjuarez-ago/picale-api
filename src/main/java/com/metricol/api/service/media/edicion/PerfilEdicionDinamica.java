package com.metricol.api.service.media.edicion;

/** Los límites se aplican en ventanas móviles de 60 segundos, no por bloques de minuto. */
public enum PerfilEdicionDinamica {
    OFF(0, 0, "off-v1"),
    SUBTLE(4, 1, "subtle-v1"),
    DYNAMIC(6, 2, "dynamic-v1");

    private final int maxEventsPerMinute;
    private final int maxCompositionsPerMinute;
    private final String policyVersion;

    PerfilEdicionDinamica(int maxEventsPerMinute, int maxCompositionsPerMinute, String policyVersion) {
        this.maxEventsPerMinute = maxEventsPerMinute;
        this.maxCompositionsPerMinute = maxCompositionsPerMinute;
        this.policyVersion = policyVersion;
    }

    public int maxEventsPerMinute() { return maxEventsPerMinute; }
    public int maxCompositionsPerMinute() { return maxCompositionsPerMinute; }
    public String policyVersion() { return policyVersion; }
}
