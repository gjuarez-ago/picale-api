package com.metricol.api.service.media.edicion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.PreparadorDePlanDeEdicion.PlanPreparado;

/** Render local revisable. Reutiliza FFmpeg existente; no reserva activos, encola ni publica. */
@Service
public class RenderizadorDeAcercamientos {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private final CompiladorDeAcercamientos compilador;
    private final FfmpegImagen ffmpeg;
    private final ControlDeEdicionDinamica control;

    public RenderizadorDeAcercamientos(CompiladorDeAcercamientos compilador, FfmpegImagen ffmpeg,
            ControlDeEdicionDinamica control) {
        this.compilador = compilador;
        this.ffmpeg = ffmpeg;
        this.control = control;
    }

    public record Resultado(String rendererVersion, String planKey, String renderKey, String renderedSha256,
            double durationSeconds, int width, int height,
            java.util.List<CompiladorDeAcercamientos.Movimiento> movimientos) { }

    public Resultado renderizar(Path original, Path destino, PlanPreparado plan, ContextoEdicionVideo contexto,
            InformeDeHablante informe, Path guion, Path musica, Path logo, double volumen) throws IOException {
        if (original == null || destino == null || contexto == null || plan == null || !Files.isRegularFile(original)) {
            throw new IllegalArgumentException("Falta el original local, el destino o el contexto verificado.");
        }
        if (control.evaluar(contexto.workspaceId(), plan.plan().profile(), contexto.alreadyEdited())
                != ControlDeEdicionDinamica.Decision.ANALYSIS_ALLOWED) {
            throw new IllegalStateException("El render dinámico no está habilitado para esta cuenta.");
        }
        original = original.toRealPath();
        destino = destino.toAbsolutePath().normalize();
        if (destino.equals(original) || Files.exists(destino)) {
            throw new IllegalArgumentException("El destino debe ser una copia nueva; no se sobrescribe ningún archivo.");
        }
        if (!Double.isFinite(volumen) || volumen < 0 || volumen > 1) {
            throw new IllegalArgumentException("Volumen de música no válido.");
        }
        String sourceHash = hash(original);
        if (!sourceHash.equals(contexto.sourceRevision())) {
            throw new IllegalArgumentException("El original local cambió desde el análisis.");
        }
        var medidas = ffmpeg.medirVideo(original.toString());
        if (medidas == null || !Double.isFinite(medidas.segundos())
                || Math.abs(medidas.segundos() - contexto.sourceDurationSeconds()) > .041
                || !ffmpeg.tienePixelesCuadrados(original.toString())) {
            throw new IllegalArgumentException("La geometría o duración del original no es compatible con este render.");
        }
        var composicion = compilador.compilar(plan, contexto, informe, medidas.ancho(), medidas.alto());
        Map<String, Object> specification = new TreeMap<>();
        specification.put("rendererVersion", CompiladorDeAcercamientos.VERSION);
        specification.put("planKey", plan.idempotencyKey());
        specification.put("filter", composicion.filtro());
        specification.put("codec", "libx264-veryfast-crf20-yuv420p-aac160k-vfr-source-timebase");
        specification.put("subtitleSha256", hashOpcional(guion));
        specification.put("musicSha256", hashOpcional(musica));
        specification.put("logoSha256", hashOpcional(logo));
        specification.put("musicVolume", musica == null ? 1.0 : volumen);
        String renderKey = digest(JSON.writeValueAsString(specification).getBytes(StandardCharsets.UTF_8));
        boolean sourceHasAudio = ffmpeg.tieneSonido(original.toString());
        Files.createDirectories(destino.getParent());
        Path temporal = Files.createTempFile(destino.getParent(), "picale-acercamiento-", ".mp4");
        try {
            if (!ffmpeg.editarVideoConAcercamientos(original.toString(), guion, musica, logo, volumen,
                    sourceHasAudio, temporal, composicion)) {
                throw new IOException("No se pudo renderizar el acercamiento.");
            }
            var salida = ffmpeg.medirVideo(temporal.toString());
            double expectedDuration = contexto.sourceRange().duration();
            if (salida == null || salida.ancho() != composicion.ancho() || salida.alto() != composicion.alto()
                    || !Double.isFinite(salida.segundos()) || Math.abs(salida.segundos() - expectedDuration) > .08
                    || ffmpeg.tieneSonido(temporal.toString()) != (sourceHasAudio || musica != null)) {
                throw new IOException("El resultado no conserva el formato, la duración o el audio esperado.");
            }
            if (!hash(original).equals(sourceHash)
                    || !hashOpcional(guion).equals(specification.get("subtitleSha256"))
                    || !hashOpcional(musica).equals(specification.get("musicSha256"))
                    || !hashOpcional(logo).equals(specification.get("logoSha256"))) {
                throw new IOException("Una fuente cambió durante el render; se descartó la copia.");
            }
            String outputHash = hash(temporal);
            Files.move(temporal, destino);
            return new Resultado(CompiladorDeAcercamientos.VERSION, plan.idempotencyKey(), renderKey, outputHash,
                    salida.segundos(), salida.ancho(), salida.alto(), composicion.movimientos());
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    private static String hashOpcional(Path path) throws IOException {
        if (path == null) return "";
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Falta un recurso de la edición.");
        return hash(path);
    }

    static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = new byte[64 * 1024];
                int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
