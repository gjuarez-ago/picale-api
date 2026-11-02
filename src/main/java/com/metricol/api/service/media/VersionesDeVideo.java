package com.metricol.api.service.media;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.PostService;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;

/**
 * Un video tiene una sola propuesta del agente, con su versión más nueva.
 *
 * <p>Antes, un recorte hecho a mano salía como otro video más: el agente lo
 * proponía aparte y quedaban dos propuestas del mismo video, una con el
 * original sin recortar. Y volver a editar un video dejaba la propuesta con la
 * versión vieja. Aquí se ordena (pedido de Gabriel, 2 nov 2026):
 * <ul>
 * <li><b>Recorte a mano:</b> pasa a ser el video. Las propuestas pendientes
 * del original (y de sus versiones) se descartan, y el recorte pasa por la
 * edición automática —subtítulos y música, sin volver a recortarlo— antes de
 * que el agente lo proponga, una sola vez.</li>
 * <li><b>Versión final nueva:</b> la propuesta pendiente pasa a usarla y las
 * versiones finales viejas se archivan. Nada se borra.</li>
 * </ul>
 * Solo se tocan propuestas del agente que esperan aprobación: lo aprobado o
 * programado por la persona no cambia a sus espaldas.
 */
@Service
public class VersionesDeVideo {

    private static final Logger log = LoggerFactory.getLogger(VersionesDeVideo.class);

    private final MediaAssetRepository assets;
    private final WorkspaceRepository workspaces;
    private final PostService posts;

    /** Perezoso: PostService llega hasta la cola de video, que a su vez usa esta clase. */
    public VersionesDeVideo(MediaAssetRepository assets, WorkspaceRepository workspaces, @Lazy PostService posts) {
        this.assets = assets;
        this.workspaces = workspaces;
        this.posts = posts;
    }

    /** Terminó un recorte hecho a mano. Lo devuelve con su estado ya puesto; quien llama lo guarda. */
    public void alTerminarRecorte(MediaAsset recorte) {
        MediaAsset original = recorte.getSourceAssetId() == null ? null
                : assets.findById(recorte.getSourceAssetId()).orElse(null);
        if (original == null) {
            return;
        }
        // Las versiones que había: el original, sus versiones finales y otros recortes suyos.
        List<MediaAsset> anteriores = new ArrayList<>();
        anteriores.add(original);
        anteriores.addAll(assets.copiasEditadas(original.getId()));
        assets.recortesDe(original.getId()).stream()
                .filter(r -> !r.getId().equals(recorte.getId()))
                .forEach(anteriores::add);
        int descartadas = 0;
        for (MediaAsset a : anteriores) {
            if (a.getUrl() != null) {
                descartadas += posts.descartarPropuestasQueUsan(a.getUrl());
            }
            if (a.getAgenteEtapa() == EtapaAgente.PROPUESTA) {
                a.setAgenteEtapa(EtapaAgente.DESCARTADA);
                a.setAgenteMotivo("Lo cambiaste por tu recorte: el agente propone ese.");
                assets.save(a);
            }
        }
        if (descartadas > 0) {
            log.info("Recorte {}: {} propuesta(s) del original descartada(s); va el recorte.", recorte.getId(),
                    descartadas);
        }
        // Que pase por la edición automática antes de que el agente lo tome.
        Workspace espacio = recorte.getTenantId() == null ? null
                : workspaces.findById(UUID.fromString(recorte.getTenantId())).orElse(null);
        // Sin ajustes a la mano cuenta lo de entrada (música encendida); si al
        // final no hay nada que ponerle, la edición lo suelta tal cual.
        if (espacio == null || Boolean.TRUE.equals(espacio.getSubtitulosAuto())
                || !Boolean.FALSE.equals(espacio.getMusicaVideos())) {
            recorte.setSubtitulosEstado(SubtitulosAutomaticos.ESPERANDO);
        }
    }

    /** Terminó una versión final (subtítulos y/o música): las propuestas pasan a ella y las viejas se archivan. */
    public void alTerminarVersionFinal(MediaAsset nueva) {
        if (nueva.getSourceAssetId() == null || nueva.getUrl() == null) {
            return;
        }
        for (MediaAsset vieja : assets.copiasEditadas(nueva.getSourceAssetId())) {
            if (vieja.getId().equals(nueva.getId()) || vieja.getArchivedAt() != null
                    || vieja.getStatus() != MediaAssetStatus.READY) {
                continue;
            }
            int cambiadas = vieja.getUrl() == null ? 0
                    : posts.cambiarArchivoDePropuestas(vieja.getUrl(), nueva.getUrl(), nueva.getThumbnailUrl());
            vieja.setArchivedAt(LocalDateTime.now());
            assets.save(vieja);
            log.info("Versión final {} reemplaza a {}: {} propuesta(s) cambiada(s); la vieja se archiva.",
                    nueva.getId(), vieja.getId(), cambiadas);
        }
    }
}
