package com.metricol.api.controller;

import java.util.List;

/** Abre a las pruebas de otros paquetes el encargo de {@link AiController}. */
public final class AiControllerEncargo {

    private AiControllerEncargo() {
    }

    public static String de(String brief, List<String> queSeVe) {
        return AiController.encargoDe(brief, queSeVe);
    }
}
