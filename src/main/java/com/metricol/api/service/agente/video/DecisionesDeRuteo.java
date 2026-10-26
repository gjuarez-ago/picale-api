package com.metricol.api.service.agente.video;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.DecisionRuteo;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.DecisionRuteoRepository;

@Service
public class DecisionesDeRuteo {

    public record Nueva(Platform plataforma, String decision, String formato, String codigo,
            String valor, String limite, String frase, String arreglo,
            Double puntajeHistorico, Integer muestrasHistoricas) {
    }

    private final DecisionRuteoRepository repository;

    public DecisionesDeRuteo(DecisionRuteoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void guardar(UUID workspaceId, UUID postId, UUID mediaId, List<Nueva> decisiones) {
        repository.saveAll(decisiones.stream().map(d -> DecisionRuteo.builder()
                .workspaceId(workspaceId).postId(postId).mediaId(mediaId).plataforma(d.plataforma())
                .decision(d.decision()).formatoResultante(d.formato()).codigoRazon(d.codigo())
                .valor(d.valor()).limite(d.limite()).frase(d.frase()).arregloOfrecido(d.arreglo())
                .decididoPor("AGENTE").puntajeHistorico(d.puntajeHistorico())
                .muestrasHistoricas(d.muestrasHistoricas()).build()).toList());
    }

    public List<DecisionRuteo> dePublicacion(UUID workspaceId, UUID postId) {
        return repository.findByWorkspaceIdAndPostIdOrderByPlataforma(workspaceId, postId);
    }

    public List<DecisionRuteo> recientes(UUID workspaceId, int dias, int limite) {
        int d = Math.max(1, Math.min(365, dias));
        int l = Math.max(1, Math.min(500, limite));
        return repository.findByWorkspaceIdAndCreatedAtAfterOrderByCreatedAtDesc(
                workspaceId, LocalDateTime.now().minusDays(d)).stream().limit(l).toList();
    }
}
