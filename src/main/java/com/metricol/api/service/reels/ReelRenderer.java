package com.metricol.api.service.reels;

import java.nio.file.Path;
import java.util.List;

public interface ReelRenderer {

    Rendered render(List<Path> photos, int durationSeconds, String title, Path workDirectory);

    /**
     * Contrato ampliado sin romper implementaciones ni pruebas silenciosas.
     * La pista ya fue seleccionada y materializada por el worker.
     */
    default Rendered render(List<Path> photos, int durationSeconds, String title,
            Path workDirectory, Path music) {
        return render(photos, durationSeconds, title, workDirectory);
    }

    default Rendered render(List<Path> photos, int durationSeconds, String title,
            Path workDirectory, Path music, double musicVolume) {
        return render(photos, durationSeconds, title, workDirectory, music);
    }

    record Rendered(Path video, Path cover, ReelTechnicalMetadata metadata) {
    }

    record ReelTechnicalMetadata(int width, int height, String videoCodec, String pixelFormat,
            String fieldOrder, double framesPerSecond, boolean fastStart, long sizeBytes) {
    }
}
