package com.metricol.api.service.media;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.metricol.api.repository.MediaAssetRepository;

/** Reanuda trabajos que quedaron sin hilo por reinicio o despliegue. */
@Service
public class VideoTrimRecoveryWorker {
    private final MediaAssetRepository assets;
    private final VideoTrimDispatcher dispatcher;

    public VideoTrimRecoveryWorker(MediaAssetRepository assets, VideoTrimDispatcher dispatcher) {
        this.assets = assets;
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void resume() {
        for (Object[] row : assets.videoTrimsToResume(LocalDateTime.now().minusMinutes(20), 20)) {
            dispatcher.dispatch(UUID.fromString(String.valueOf(row[0])), String.valueOf(row[1]));
        }
    }
}
