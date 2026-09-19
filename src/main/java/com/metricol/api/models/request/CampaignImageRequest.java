package com.metricol.api.models.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda la app para generar una imagen de campaña.
 *
 * <p>El modelo, la calidad y la cantidad que también viajan en la petición son
 * informativos: el servidor decide los suyos. Se aceptan y se ignoran para que
 * una versión de la app que los manda no reciba un error por ello.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignImageRequest(
        Integer version,
        Format format,
        @Size(max = 5) List<@Size(max = 1000) String> resourceUrls,
        Brand brand,
        @NotBlank @Size(max = 1500) String brief,
        @Size(max = 80) String objective,
        @Size(max = 8) List<@Size(max = 60) String> visualStyle,
        @Size(max = 120) String tone,
        @Size(max = 200) String cta,
        /**
         * Las redes donde va a publicarse (INSTAGRAM, FACEBOOK, LINKEDIN...). Con ellas se crea una
         * versión por cada proporción distinta. Sin ellas (la app anterior) sale una sola imagen.
         */
        @Size(max = 5) List<@Size(max = 30) String> networks) {

    /** {@code code}: post, carousel o story. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Format(String code, String ratio, String outputSize) {
    }

    /**
     * {@code logoPosition}: TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT,
     * BOTTOM_CENTER, BOTTOM_RIGHT, o NONE para no poner logo. Sin valor, el
     * servidor elige según el formato.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Brand(String logoUrl, String logoPosition) {
    }
}
