package com.metricol.api.models.request;

import java.util.List;

import com.metricol.api.service.media.subtitulos.EstiloSubtitulos;
import com.metricol.api.service.media.subtitulos.Frase;
import com.metricol.api.service.media.subtitulos.SubtitulosService;

import jakarta.validation.constraints.Size;

/**
 * Ponerle subtítulos a un video.
 *
 * @param estilo nulo = al azar
 * @param color  nulo = al azar (el de la marca o amarillo)
 * @param frases el texto ya corregido; nulo = tal como se oyó
 */
public record SubtitulosRequest(
        EstiloSubtitulos estilo,
        SubtitulosService.Color color,
        @Size(max = 600) List<Frase> frases) {
}
