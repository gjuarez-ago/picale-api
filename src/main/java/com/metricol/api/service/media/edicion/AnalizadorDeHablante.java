package com.metricol.api.service.media.edicion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;
import com.metricol.api.service.storage.R2StorageService;

/** Frontera interna para el futuro trabajo durable. No se ejecuta al subir ni desde un endpoint. */
@Service
public class AnalizadorDeHablante {
    public record Resultado(ContextoEdicionVideo contexto, InformeDeHablante informe) {
        public boolean elegible() { return contexto.evidence().activeSpeakerVerified(); }
    }

    private final FuenteDeVideoHablante fuentes;
    private final R2StorageService storage;
    private final FfmpegImagen ffmpeg;
    private final EjecutorAnalisisHablante executor;
    private final ControlDeEdicionDinamica control;
    private final AnalisisHablanteProperties properties;

    public AnalizadorDeHablante(FuenteDeVideoHablante fuentes, R2StorageService storage, FfmpegImagen ffmpeg,
            EjecutorAnalisisHablante executor, ControlDeEdicionDinamica control, AnalisisHablanteProperties properties) {
        this.fuentes = fuentes;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.executor = executor;
        this.control = control;
        this.properties = properties;
    }

    public Resultado analizar(UUID assetId, UUID workspaceId, PerfilEdicionDinamica profile) throws IOException {
        if (control.evaluar(workspaceId, profile, false) != ControlDeEdicionDinamica.Decision.ANALYSIS_ALLOWED) {
            throw new IllegalStateException("El análisis dinámico no está habilitado para esta cuenta.");
        }
        var fuente = fuentes.cargar(assetId, workspaceId);
        if (!executor.disponible()) throw new IllegalStateException("El analizador local todavía no está instalado.");
        Path directory = Files.createTempDirectory("picale-speaker-").toAbsolutePath().normalize();
        try {
            Path original = directory.resolve("original.bin");
            if (!storage.descargarLimitado(fuente.storageKey(), original, properties.getMaxSourceBytes())
                    || Files.size(original) != fuente.sizeBytes()) {
                throw new IOException("El original no está disponible o cambió de tamaño.");
            }
            String sourceHash = hash(original);
            var medidas = ffmpeg.medirVideo(original.toString());
            if (medidas == null || medidas.segundos() > ValidadorDePlanDeEdicion.MAX_SOURCE_SECONDS) {
                throw new IllegalArgumentException("El video no se puede medir o excede el límite de análisis.");
            }
            InformeDeHablante informe = executor.analizar(original, directory);
            informe.validarContra(sourceHash, medidas.segundos());
            if (!hash(original).equals(sourceHash) || !fuente.equals(fuentes.cargar(assetId, workspaceId))) {
                throw new IllegalStateException("La fuente o transcripción cambió durante el análisis; hay que repetirlo.");
            }
            var evidencia = new EvidenciaDeHablante(informe.evidenceRevision(), informe.detectorVersion(),
                    informe.activeSpeakerVerified(), informe.intervals());
            var contexto = new ContextoEdicionVideo(workspaceId, assetId, sourceHash, fuente.transcriptRevision(),
                    medidas.segundos(), new Tramo(0, medidas.segundos()), false, evidencia);
            return new Resultado(contexto, informe);
        } finally {
            // Directorio creado por esta llamada. walk no sigue enlaces; cada destino se verifica antes de borrar.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) {
                        throw new IOException("La limpieza salió del directorio de análisis.");
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
