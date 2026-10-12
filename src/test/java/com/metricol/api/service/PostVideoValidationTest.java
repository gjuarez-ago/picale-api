package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.VideoLimitsProperties;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.models.request.PostSaveRequest;
import com.metricol.api.service.publishing.FormatRulesService;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.repository.MediaAssetRepository;

class PostVideoValidationTest {
    private final VideoLimitsProperties limits = new VideoLimitsProperties();
    private final R2StorageService storage = mock(R2StorageService.class);
    private final PostService service = new PostService(null, null, mock(MediaAssetRepository.class), null,
            new MediaLimitsProperties(), limits, new FormatRulesService(), storage, null);

    private void validar(int seconds, PostFormat format, boolean publicar) {
        limits.setMaxSeconds(Map.of(Platform.FACEBOOK, 14400, Platform.INSTAGRAM, 900,
                Platform.TIKTOK, 600, Platform.LINKEDIN, 1800));
        String url = "https://media.example/video.mp4";
        when(storage.esNuestra(url)).thenReturn(true);
        var request = new PostSaveRequest();
        request.setMediaUrls(List.of(url));
        request.setVideoDurationSeconds(seconds);
        request.setPublishNow(publicar);
        var redes = (format == PostFormat.STORY ? List.of(Platform.FACEBOOK, Platform.INSTAGRAM)
                : List.of(Platform.FACEBOOK, Platform.INSTAGRAM, Platform.TIKTOK, Platform.LINKEDIN))
                .stream().map(p -> SocialAccount.builder().platform(p).build()).toList();
        ReflectionTestUtils.invokeMethod(service, "validarMedios", request, redes, format);
    }

    @Test void acepta99SegundosEnTodasLasRedes() {
        assertThatCode(() -> validar(99, PostFormat.REEL, true)).doesNotThrowAnyException();
    }
    @Test void conservaElMinimoYElLimiteDeHistorias() {
        assertThatThrownBy(() -> validar(2, PostFormat.REEL, true)).hasMessageContaining("al menos 3");
        assertThatCode(() -> validar(60, PostFormat.STORY, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validar(61, PostFormat.STORY, true)).hasMessageContaining("hasta 60");
    }
    @Test void unVideoDemasiadoLargoSePuedeGuardarComoBorrador() {
        assertThatCode(() -> validar(601, PostFormat.REEL, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validar(601, PostFormat.REEL, true)).hasMessageContaining("TikTok");
    }
}
