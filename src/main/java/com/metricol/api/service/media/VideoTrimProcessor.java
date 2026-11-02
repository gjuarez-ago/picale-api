package com.metricol.api.service.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;
import com.metricol.api.service.media.subtitulos.SubtitulosService;
import com.metricol.api.service.reels.ReelMusicCatalog;

/** Materializa una copia solicitada. Nunca modifica el video fuente. */
@Service
public class VideoTrimProcessor {
    private static final Logger log = LoggerFactory.getLogger(VideoTrimProcessor.class);

    private final MediaAssetRepository assets;
    private final R2StorageService storage;
    private final StorageQuotaService quota;
    private final FfmpegImagen ffmpeg;
    private final MiniaturasEnSegundoPlano thumbnails;
    private final ObjectMapper json;
    private final ReelMusicCatalog musica;

    public VideoTrimProcessor(MediaAssetRepository assets, R2StorageService storage,
            StorageQuotaService quota, FfmpegImagen ffmpeg, MiniaturasEnSegundoPlano thumbnails,
            ObjectMapper json, ReelMusicCatalog musica) {
        this.assets = assets;
        this.storage = storage;
        this.quota = quota;
        this.ffmpeg = ffmpeg;
        this.thumbnails = thumbnails;
        this.json = json;
        this.musica = musica;
    }

    public void process(UUID jobId) {
        LocalDateTime now = LocalDateTime.now();
        if (assets.claimVideoTrim(jobId, now, now.minusMinutes(20)) == 0) return;

        MediaAsset job = assets.findById(jobId).orElse(null);
        if (job == null || job.getSourceAssetId() == null) return;
        MediaAsset source = assets.findById(job.getSourceAssetId()).orElse(null);
        if (source == null || source.getStatus() != MediaAssetStatus.READY) {
            fail(job, "El video original ya no esta disponible.");
            return;
        }

        Path output = null;
        try {
            output = Files.createTempFile("picale-trim-", ".mp4");
            // La misma cola hace recortes y copias con subtítulos y/o música: un solo
            // ffmpeg pesado a la vez para no quitarle CPU a las publicaciones.
            boolean subtitulos = SubtitulosService.esDeSubtitulos(job);
            boolean listo = subtitulos
                    ? editar(source, job, output)
                    : ffmpeg.recortarVideo(source.getUrl(), output,
                            job.getTrimStartSeconds(), job.getTrimEndSeconds());
            if (!listo) {
                fail(job, subtitulos
                        ? "No pude editar el video. Intenta otra vez."
                        : "No pude crear el recorte. Intenta otra vez.");
                return;
            }
            long bytes = Files.size(output);
            long reserved = job.getSizeBytes() == null ? 0 : job.getSizeBytes();
            quota.verificarResultado(bytes, reserved, job.getFileName());
            storage.subirArchivo(job.getStorageKey(), output, "video/mp4");

            job.setSizeBytes(bytes);
            job.setStatus(MediaAssetStatus.READY);
            job.setProcessingError(null);
            assets.save(job);
            if (subtitulos) {
                SubtitulosAutomaticos.copiaTerminada(assets, source.getId(), true);
            }
            thumbnails.sacar(job.getId(), job.getTenantId());
        } catch (Exception ex) {
            storage.deleteByKey(job.getStorageKey());
            fail(job, mensaje(ex));
            log.warn("No se pudo recortar el video {}: {}", jobId, ex.toString());
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    log.debug("Quedo un video temporal sin borrar: {}", output);
                }
            }
        }
    }

    /**
     * Escribe el guion al tamaño real del video (si lleva subtítulos), prepara
     * la canción (si lleva música) y hace la copia en una sola pasada.
     */
    private boolean editar(MediaAsset source, MediaAsset job, Path output) throws IOException {
        FfmpegImagen.MedidasVideo medidas = ffmpeg.medirVideo(source.getUrl());
        if (medidas == null) {
            return false;
        }
        String texto = SubtitulosService.guion(json, job, medidas);
        SubtitulosService.MusicaDeCopia cancion = SubtitulosService.musica(json, job);
        SubtitulosService.TramoDeCopia tramo = SubtitulosService.tramo(json, job);
        Path carpeta = Files.createTempDirectory("picale-edicion-");
        try {
            Path guion = null;
            if (texto != null) {
                guion = carpeta.resolve("subtitulos.ass");
                Files.writeString(guion, texto, StandardCharsets.UTF_8);
            }
            Path pista = null;
            if (cancion != null) {
                ReelMusicCatalog.Selection elegida = musica.selection(cancion.cancion());
                if (elegida == null) {
                    throw new IllegalStateException("Esa canción ya no está en el catálogo.");
                }
                pista = musica.materialize(elegida, carpeta);
            }
            return ffmpeg.editarVideo(source.getUrl(), guion, pista,
                    cancion == null ? 1 : cancion.volumen(),
                    tramo == null ? 0 : tramo.inicio(), tramo == null ? medidas.segundos() : tramo.fin(),
                    pista != null && ffmpeg.tieneSonido(source.getUrl()), output);
        } finally {
            try (var archivos = Files.list(carpeta)) {
                archivos.forEach(a -> a.toFile().delete());
            }
            Files.deleteIfExists(carpeta);
        }
    }

    private void fail(MediaAsset job, String message) {
        job.setStatus(MediaAssetStatus.FAILED);
        job.setSizeBytes(0L);
        job.setProcessingError(message.length() <= 500 ? message : message.substring(0, 500));
        assets.save(job);
        if (SubtitulosService.esDeSubtitulos(job) && job.getSourceAssetId() != null) {
            SubtitulosAutomaticos.copiaTerminada(assets, job.getSourceAssetId(), false);
        }
    }

    private static String mensaje(Exception ex) {
        String value = ex.getMessage();
        return value == null || value.isBlank() ? "No pude crear el recorte." : value;
    }
}
