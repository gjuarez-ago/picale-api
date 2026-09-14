package com.metricol.api.enums;

public enum Platform {
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    YOUTUBE("YouTube"),
    LINKEDIN("LinkedIn");

    /**
     * El nombre como lo escribe la red, para los mensajes que lee una
     * persona. Capitalizar {@code name()} a mano daba "Tiktok" y "Linkedin".
     */
    private final String label;

    Platform(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
