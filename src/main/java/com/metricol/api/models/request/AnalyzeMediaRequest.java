package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Las imágenes que hay que mirar, cuanto antes.
 *
 * <p>Va aparte de {@link ComposeRequest} a propósito, y es toda la idea de
 * velocidad de esta función: la app la llama en cuanto se agrega la foto, sin
 * esperar a que la persona termine de dictar. Los dos segundos que tarda
 * mirar una imagen se gastan mientras alguien habla, no mientras espera.
 */
@Getter
@Setter
public class AnalyzeMediaRequest {
    private List<String> mediaUrls = new ArrayList<>();
}
