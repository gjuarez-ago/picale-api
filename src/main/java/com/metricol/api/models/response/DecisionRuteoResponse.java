package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.entity.DecisionRuteo;
import com.metricol.api.enums.Platform;

public record DecisionRuteoResponse(
        UUID id,
        UUID postId,
        UUID mediaId,
        Platform plataforma,
        String decision,
        String formatoResultante,
        String codigoRazon,
        String valor,
        String limite,
        String frase,
        String arregloOfrecido,
        String decididoPor,
        Double puntajeHistorico,
        Integer muestrasHistoricas,
        LocalDateTime cuando) {

    public static DecisionRuteoResponse de(DecisionRuteo d) {
        return new DecisionRuteoResponse(d.getId(), d.getPostId(), d.getMediaId(), d.getPlataforma(),
                d.getDecision(), d.getFormatoResultante(), d.getCodigoRazon(), d.getValor(), d.getLimite(),
                d.getFrase(), d.getArregloOfrecido(), d.getDecididoPor(), d.getPuntajeHistorico(),
                d.getMuestrasHistoricas(), d.getCreatedAt());
    }
}
