package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.VideoLimitsProperties;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.models.request.PostSaveRequest;
import com.metricol.api.service.publishing.FormatRulesService;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.ReelRenderJobRepository;

class PostVideoValidationTest {
    private final VideoLimitsProperties limits = new VideoLimitsProperties();
    private final R2StorageService storage = mock(R2StorageService.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final ReelRenderJobRepository reelJobs = mock(ReelRenderJobRepository.class);
    private final PostService service = new PostService(null, null, assets, null,
            new MediaLimitsProperties(), limits, new FormatRulesService(), storage, null, reelJobs);

    private void validar(int seconds, PostFormat format, boolean publicar) {
        validar(seconds, format, publicar,
                format == PostFormat.STORY ? List.of(Platform.FACEBOOK, Platform.INSTAGRAM)
                        : List.of(Platform.FACEBOOK, Platform.INSTAGRAM, Platform.TIKTOK,
                                Platform.LINKEDIN));
    }

    private void validar(int seconds, PostFormat format, boolean publicar, List<Platform> enRedes) {
        limits.setMaxSeconds(Map.of(Platform.FACEBOOK, 14400, Platform.INSTAGRAM, 900,
                Platform.TIKTOK, 600, Platform.LINKEDIN, 1800));
        String url = "https://media.example/video.mp4";
        when(storage.esNuestra(url)).thenReturn(true);
        var request = new PostSaveRequest();
        request.setMediaUrls(List.of(url));
        request.setVideoDurationSeconds(seconds);
        request.setPublishNow(publicar);
        var redes = enRedes.stream()
                .map(p -> SocialAccount.builder().platform(p).build()).toList();
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
    }

    /**
     * Pasarse del tope de UNA red no tumba la publicacion: esa red se queda
     * fuera —su destino nace marcado con el motivo, ver aplicarDestinos— y las
     * otras tres reciben el video igual.
     *
     * <p>Antes esto lanzaba, y era peor de lo que parece: un video de 10 minutos
     * y un segundo con TikTok marcado dejaba a la persona sin publicar en
     * Facebook, Instagram ni LinkedIn, donde cabia de sobra.
     */
    @Test void unaRedQueSePasaNoTumbaLasDemas() {
        assertThatCode(() -> validar(601, PostFormat.REEL, true)).doesNotThrowAnyException();
    }

    /** Sin ninguna red que pueda recibirlo si se para: guardar eso no es publicar. */
    @Test void siNingunaRedPuedeRecibirloSeDice() {
        assertThatThrownBy(() -> validar(601, PostFormat.REEL, true, List.of(Platform.TIKTOK)))
                .hasMessageContaining("TikTok")
                .hasMessageContaining("No queda ninguna red");
    }

    @Test void exigeAprobacionHumanaParaUnReelGenerado() {
        String url = "https://media.example/generated.mp4";
        UUID id = UUID.randomUUID();
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setUrl(url);
        asset.setType(MediaType.VIDEO);
        when(storage.esNuestra(url)).thenReturn(true);
        when(assets.findByUrlIn(List.of(url))).thenReturn(List.of(asset));
        when(reelJobs.existsByResultAssetIdInAndApprovedAtIsNull(List.of(id))).thenReturn(true);

        var request = new PostSaveRequest();
        request.setMediaUrls(List.of(url));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "validarMedios", request, List.of(), PostFormat.REEL))
                .hasMessageContaining("aprueba el reel");
    }
}
