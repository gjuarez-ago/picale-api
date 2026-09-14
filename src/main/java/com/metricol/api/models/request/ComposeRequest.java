package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Lo que la persona dictó, lo que subió y dónde lo quiere publicar. */
@Getter
@Setter
public class ComposeRequest {

    /**
     * Lo que dictó: la INTENCIÓN, no el texto final.
     *
     * <p>"Anuncia el 2x1 de hoy, tono divertido", no un caption ya escrito.
     */
    @NotBlank
    private String brief;

    /** Las fotos de la publicación. Puede venir vacío: hay posts de solo texto. */
    private List<String> mediaUrls = new ArrayList<>();

    /** Nombres de Platform. Vacío se toma como Instagram, la más estricta. */
    private List<String> platforms = new ArrayList<>();
}
