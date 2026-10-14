package com.metricol.api.service.reels;

import java.nio.file.Path;
import java.util.List;

public interface ReelRenderer {

    Rendered render(List<Path> photos, int durationSeconds, String title, Path workDirectory);

    record Rendered(Path video, Path cover, ReelTechnicalMetadata metadata) {
    }

    record ReelTechnicalMetadata(int width, int height, String videoCodec, String pixelFormat,
            String fieldOrder, double framesPerSecond, boolean fastStart, long sizeBytes) {
    }
}
