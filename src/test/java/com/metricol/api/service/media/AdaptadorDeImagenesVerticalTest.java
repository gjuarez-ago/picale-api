package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Una historia o un reel piden 9:16 y la foto se deja así SOLA. Antes la app
 * la rechazaba en rojo ("Esta foto no es vertical 9:16... cámbiala") y no
 * dejaba publicar, aunque el servidor ya sabe encajar fotos con ffmpeg.
 */
class AdaptadorDeImagenesVerticalTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final String URL = "https://cdn.test/media/ws/a.jpg";
    private static final String CLAVE = "media/ws/a.jpg";

    private FfmpegImagen ffmpeg;
    private R2StorageService storage;
    private AdaptadorDeImagenes adaptador;

    @BeforeEach
    void preparar() {
        ffmpeg = mock(FfmpegImagen.class);
        storage = mock(R2StorageService.class);
        adaptador = new AdaptadorDeImagenes(new MediaAdaptProperties(), ffmpeg, storage);

        when(storage.claveDe(URL)).thenReturn(CLAVE);
        when(storage.consultar(anyString())).thenReturn(null);
        when(storage.consultar(CLAVE)).thenReturn(new R2StorageService.Consulta(500_000, "image/jpeg"));
        when(storage.descargar(anyString(), any(Path.class))).thenReturn(true);
        when(storage.urlDe(anyString())).thenReturn("https://cdn.test/derivada.jpg");
        when(storage.subirBytes(anyString(), any(byte[].class), anyString()))
                .thenReturn("https://cdn.test/derivada.jpg");
        when(ffmpeg.verificar(any(Path.class))).thenReturn("");
        when(ffmpeg.encajar(any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn(new byte[] { 1, 2, 3 });
    }

    private void fotoDe(int ancho, int alto) {
        when(ffmpeg.medir(any(Path.class))).thenReturn(new FfmpegImagen.Medidas(ancho, alto));
    }

    private int[] lienzoPedido() {
        ArgumentCaptor<Integer> ancho = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> alto = ArgumentCaptor.forClass(Integer.class);
        verify(ffmpeg).encajar(any(Path.class), ancho.capture(), alto.capture(), anyInt());
        return new int[] { ancho.getValue(), alto.getValue() };
    }

    @Test
    @DisplayName("una foto cuadrada en una historia se encaja a 9:16, sin pedirle nada a la persona")
    void cuadradaEnHistoria() {
        fotoDe(1000, 1000);

        var resultado = adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM), PostFormat.STORY);

        assertThat(resultado.urls()).containsExactly("https://cdn.test/derivada.jpg");
        int[] lienzo = lienzoPedido();
        assertThat((double) lienzo[0] / lienzo[1]).isBetween(0.5615, 0.5635);
    }

    @Test
    @DisplayName("una foto apaisada en una historia también, y baja al ancho de una historia")
    void apaisadaEnHistoria() {
        fotoDe(4000, 3000);

        adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM, Platform.FACEBOOK), PostFormat.STORY);

        int[] lienzo = lienzoPedido();
        assertThat(lienzo[0]).isLessThanOrEqualTo(1080);
        assertThat((double) lienzo[0] / lienzo[1]).isBetween(0.5615, 0.5635);
    }

    @Test
    @DisplayName("una foto que ya es 9:16 se queda como está: no se reencoda lo que ya sirve")
    void verticalNoSeToca() {
        fotoDe(1080, 1920);

        var resultado = adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM), PostFormat.STORY);

        assertThat(resultado.urls()).containsExactly(URL);
        verify(ffmpeg, never()).encajar(any(Path.class), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("1080 x 1919 cuenta como 9:16: no se reencoda por un píxel")
    void unPixelNoImporta() {
        fotoDe(1080, 1919);

        var resultado = adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM), PostFormat.STORY);

        assertThat(resultado.urls()).containsExactly(URL);
        verify(ffmpeg, never()).encajar(any(Path.class), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("una publicación normal sigue yendo al 4:5 de Instagram, no al 9:16")
    void laPublicacionNormalNoCambia() {
        fotoDe(1080, 1920);

        adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM), PostFormat.PHOTO);

        int[] lienzo = lienzoPedido();
        assertThat((double) lienzo[0] / lienzo[1]).isBetween(0.79, 0.81);
    }

    @Test
    @DisplayName("sin decir formato se comporta como siempre (foto)")
    void sinFormato() {
        fotoDe(1080, 1920);

        adaptador.adaptar(WORKSPACE, List.of(URL), List.of(Platform.INSTAGRAM));

        int[] lienzo = lienzoPedido();
        assertThat((double) lienzo[0] / lienzo[1]).isBetween(0.79, 0.81);
    }
}
