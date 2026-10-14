package com.metricol.api.service.reels;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.ReelsProperties;

/** Render determinista de fotografías con FFmpeg y validación posterior. */
@Component
public class FfmpegReelRenderer implements ReelRenderer {

    static final int WIDTH = 1080;
    static final int HEIGHT = 1920;
    static final int FPS = 30;
    private static final int TITLE_SECONDS = 3;

    private final ReelsProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public FfmpegReelRenderer(ReelsProperties properties) {
        this.properties = properties;
    }

    @Override
    public Rendered render(List<Path> photos, int durationSeconds, String title, Path workDirectory) {
        if (photos == null || photos.isEmpty()) {
            throw new ReelRenderException("El reel no tiene fotografías.");
        }
        Path output = workDirectory.resolve("reel.mp4");
        Path cover = workDirectory.resolve("portada.jpg");
        try {
            List<String> command = buildCommand(photos, durationSeconds, title, workDirectory, output);
            runProcess(command, properties.getTimeoutSeconds(), "FFmpeg");
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new ReelRenderException("FFmpeg no produjo el video.");
            }
            ReelTechnicalMetadata metadata = probe(output);
            validate(metadata);
            runProcess(List.of(properties.getFfmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                    "-ss", "0.5", "-i", output.toString(), "-frames:v", "1",
                    "-vf", "scale=480:-2", "-q:v", "3", cover.toString()),
                    Math.min(properties.getTimeoutSeconds(), 45), "FFmpeg (portada)");
            if (!Files.isRegularFile(cover) || Files.size(cover) == 0) {
                throw new ReelRenderException("No se pudo generar una portada válida.");
            }
            return new Rendered(output, cover, metadata);
        } catch (IOException ex) {
            throw new ReelRenderException("No se pudo preparar el render.", ex);
        }
    }

    List<String> buildCommand(List<Path> photos, int durationSeconds, String title,
            Path workDirectory, Path output) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpeg());
        command.addAll(List.of("-hide_banner", "-loglevel", "error", "-y"));

        double secondsPerPhoto = durationSeconds / (double) photos.size();
        for (Path photo : photos) {
            command.addAll(List.of("-loop", "1", "-framerate", String.valueOf(FPS),
                    "-t", decimal(secondsPerPhoto), "-i", photo.toString()));
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < photos.size(); i++) {
            filter.append('[').append(i).append(":v]")
                    .append("split=2[bg").append(i).append("][fg").append(i).append("];")
                    .append("[bg").append(i).append("]scale=").append(WIDTH).append(':').append(HEIGHT)
                    .append(":force_original_aspect_ratio=increase,crop=").append(WIDTH).append(':').append(HEIGHT)
                    .append(",gblur=sigma=28[blur").append(i).append("];")
                    .append("[fg").append(i).append("]scale=").append(WIDTH).append(':').append(HEIGHT)
                    .append(":force_original_aspect_ratio=decrease[fit").append(i).append("];")
                    .append("[blur").append(i).append("][fit").append(i).append("]")
                    .append("overlay=(W-w)/2:(H-h)/2,")
                    .append("scale=1200:2134,zoompan=z='min(zoom+0.00012,1.08)':")
                    .append("x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=")
                    .append(WIDTH).append('x').append(HEIGHT).append(":fps=").append(FPS).append(',')
                    .append("trim=duration=").append(decimal(secondsPerPhoto)).append(",setpts=PTS-STARTPTS,")
                    .append("setsar=1[v").append(i).append("];");
        }
        for (int i = 0; i < photos.size(); i++) {
            filter.append("[v").append(i).append(']');
        }
        filter.append("concat=n=").append(photos.size()).append(":v=1:a=0[joined]");

        String outputLabel = "joined";
        if (title != null && !title.isBlank()) {
            Path titleFile = workDirectory.resolve("titulo.txt");
            Files.writeString(titleFile, wrapTitle(title), StandardCharsets.UTF_8);
            Path font = workDirectory.resolve("Montserrat-Bold.ttf");
            try (var input = new ClassPathResource("fuentes/Montserrat-Bold.ttf").getInputStream()) {
                Files.copy(input, font, StandardCopyOption.REPLACE_EXISTING);
            }
            filter.append(";[joined]drawtext=fontfile='").append(filterPath(font))
                    .append("':textfile='").append(filterPath(titleFile))
                    .append("':fontcolor=white:fontsize=58:line_spacing=12:")
                    .append("x=(w-text_w)/2:y=170:box=1:boxcolor=black@0.58:boxborderw=28:")
                    .append("enable='between(t,0,").append(Math.min(TITLE_SECONDS, durationSeconds)).append(")'[titled]");
            outputLabel = "titled";
        }

        // Los JPEG suelen declarar rango completo y FFmpeg lo propaga como
        // yuvj420p aunque se pida -pix_fmt. La conversión explícita a rango TV
        // hace que el contrato comprobable sea realmente yuv420p.
        filter.append(";[").append(outputLabel)
                .append("]scale=in_range=auto:out_range=tv,format=yuv420p,setrange=limited[outv]");
        outputLabel = "outv";

        command.addAll(List.of("-filter_complex", filter.toString(), "-map", "[" + outputLabel + "]",
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-profile:v", "high", "-level:v", "4.0", "-pix_fmt", "yuv420p",
                "-r", String.valueOf(FPS), "-g", "60", "-flags", "+cgop",
                "-movflags", "+faststart", "-t", String.valueOf(durationSeconds), output.toString()));
        return command;
    }

    private ReelTechnicalMetadata probe(Path output) throws IOException {
        Path report = output.getParent().resolve("ffprobe.json");
        runToFile(List.of(properties.getFfprobe(), "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,pix_fmt,field_order,avg_frame_rate",
                "-of", "json", output.toString()), 30, "ffprobe", report);
        JsonNode stream;
        try {
            stream = json.readTree(report.toFile()).path("streams").path(0);
        } catch (Exception ex) {
            throw new ReelRenderException("ffprobe devolvió una respuesta inválida.", ex);
        }
        String rate = stream.path("avg_frame_rate").asText("0/1");
        String[] parts = rate.split("/");
        double fps = parts.length == 2 && Double.parseDouble(parts[1]) != 0
                ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]) : 0;
        return new ReelTechnicalMetadata(
                stream.path("width").asInt(), stream.path("height").asInt(),
                stream.path("codec_name").asText(), stream.path("pix_fmt").asText(),
                stream.path("field_order").asText(), fps, isFastStart(output), Files.size(output));
    }

    static void validate(ReelTechnicalMetadata metadata) {
        if (metadata.width() != WIDTH || metadata.height() != HEIGHT) {
            throw new ReelRenderException("El video no quedó en 1080x1920.");
        }
        if (!"h264".equals(metadata.videoCodec()) || !"yuv420p".equals(metadata.pixelFormat())) {
            throw new ReelRenderException("El video no cumple H.264/yuv420p.");
        }
        if (!"progressive".equalsIgnoreCase(metadata.fieldOrder())) {
            throw new ReelRenderException("El video no es progresivo.");
        }
        if (Math.abs(metadata.framesPerSecond() - FPS) > 0.01) {
            throw new ReelRenderException("El video no quedó en 30 fps.");
        }
        if (!metadata.fastStart()) {
            throw new ReelRenderException("El MP4 no tiene faststart.");
        }
    }

    void runProcess(List<String> command, int timeoutSeconds, String name) {
        Path log = null;
        try {
            log = Files.createTempFile("picale-reel-process-", ".log");
            runToFile(command, timeoutSeconds, name, log);
        } catch (IOException ex) {
            throw new ReelRenderException("No se pudo ejecutar " + name + ".", ex);
        } finally {
            delete(log);
        }
    }

    private void runToFile(List<String> command, int timeoutSeconds, String name, Path output) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ReelRenderException(name + " excedió el límite de " + timeoutSeconds + " segundos.");
            }
            if (process.exitValue() != 0) {
                String message = Files.readString(output).strip();
                if (message.length() > 450) {
                    message = message.substring(0, 447) + "...";
                }
                throw new ReelRenderException(name + " terminó con código " + process.exitValue() + ": " + message);
            }
        } catch (IOException ex) {
            throw new ReelRenderException("No se pudo ejecutar " + name + ".", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ReelRenderException(name + " fue interrumpido.", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static String wrapTitle(String title) {
        String clean = title.strip().replaceAll("\\s+", " ");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : clean.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > 26) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.size() > 3) {
            throw new IllegalArgumentException("El titular es demasiado largo para la zona segura (máximo 3 líneas).");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String filterPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace(":", "\\:").replace("'", "\\'");
    }

    private static String decimal(double number) {
        return String.format(Locale.US, "%.6f", number);
    }

    private static boolean isFastStart(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(1024 * 1024);
        }
        int moov = indexOf(bytes, "moov".getBytes(StandardCharsets.US_ASCII));
        int mdat = indexOf(bytes, "mdat".getBytes(StandardCharsets.US_ASCII));
        return moov >= 0 && (mdat < 0 || moov < mdat);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void delete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // El directorio de trabajo se limpia también en el runner.
            }
        }
    }
}
