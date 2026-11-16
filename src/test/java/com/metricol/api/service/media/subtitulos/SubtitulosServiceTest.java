package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.VideoTrimDispatcher;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

class SubtitulosServiceTest {

    private static final UUID WS = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private MediaAssetRepository assets;
    private FfmpegImagen ffmpeg;
    private OpenAiClient ia;
    private VideoTrimDispatcher cola;
    private SubtitulosService service;
    private MediaAsset video;

    @BeforeEach
    void preparar() {
        assets = mock(MediaAssetRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        ffmpeg = mock(FfmpegImagen.class);
        ia = mock(OpenAiClient.class);
        R2StorageService storage = mock(R2StorageService.class);
        cola = mock(VideoTrimDispatcher.class);
        service = new SubtitulosService(assets, workspaces, ffmpeg, ia, storage, mock(StorageQuotaService.class),
                cola, mock(MediaService.class), json);

        video = MediaAsset.builder().id(UUID.randomUUID()).type(MediaType.VIDEO).status(MediaAssetStatus.READY)
                .url("https://cdn/v.mp4").fileName("reel.mp4").sizeBytes(1000L).build();
        video.setTenantId(WS.toString());
        when(assets.findById(video.getId())).thenReturn(Optional.of(video));
        when(assets.save(any(MediaAsset.class))).thenAnswer(i -> i.getArgument(0));
        when(ffmpeg.medirVideo(anyString())).thenReturn(new FfmpegImagen.MedidasVideo(1080, 1920, 20));
        when(ffmpeg.audio(anyString(), anyInt())).thenReturn(new byte[2048]);
        when(storage.claveNueva(any(UUID.class), anyString(), anyString())).thenReturn("media/k.mp4");
        when(cola.dispatch(any(), any())).thenReturn(true);
        Workspace espacio = new Workspace();
        espacio.setMarcaColores("#030202,#D4A858");
        when(workspaces.findById(WS)).thenReturn(Optional.of(espacio));
        when(ia.transcribirConTiempos(any(), anyString())).thenReturn(Map.of("words", List.of(
                Map.of("word", "Hola", "start", 0.1, "end", 0.4), Map.of("word", "amigos", "start", 0.4, "end", 0.9))));
    }

    @Test
    @DisplayName("Mira tres cuadros mientras se habla y dice si el video ya trae subtítulos")
    void yaTraeSubtitulos() {
        service.texto(video.getId(), WS);
        when(ffmpeg.cuadro(anyString(), org.mockito.ArgumentMatchers.anyDouble(), anyInt(), anyInt())).thenReturn(new byte[] { 1 });
        when(ia.describeImages(any(), anyString(), anyString(), any())).thenReturn("SI");
        assertThat(service.yaTraeSubtitulos(video.getId(), WS)).isTrue();
        verify(ffmpeg, times(3)).cuadro(anyString(), org.mockito.ArgumentMatchers.anyDouble(), anyInt(), anyInt());

        when(ia.describeImages(any(), anyString(), anyString(), any())).thenReturn(" No.");
        assertThat(service.yaTraeSubtitulos(video.getId(), WS)).isFalse();

        when(ffmpeg.cuadro(anyString(), org.mockito.ArgumentMatchers.anyDouble(), anyInt(), anyInt())).thenReturn(null);
        assertThatThrownBy(() -> service.yaTraeSubtitulos(video.getId(), WS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("El texto se saca una vez y se guarda: corregir o cambiar de estilo no vuelve a cobrar")
    void textoUnaSolaVez() {
        assertThat(service.texto(video.getId(), WS)).extracting(Frase::texto).containsExactly("Hola amigos");
        assertThat(video.getTranscripcion()).contains("amigos");
        assertThat(service.texto(video.getId(), WS)).hasSize(1);
        verify(ia, times(1)).transcribirConTiempos(any(), anyString());
    }

    @Test
    @DisplayName("Whisper guarda sólo su transcripción sin fusionar el video viejo que carece de portada")
    void transcribirNoSobrescribeLaPortadaConcurrente() {
        // Esta entidad se leyó antes de que el hilo de miniaturas guardara
        // su portada. Volver a guardar la entidad completa la borraría.
        video.setThumbnailUrl(null);
        service.texto(video.getId(), WS);
        verify(assets).ponerTranscripcionSiFalta(video.getId(), video.getTranscripcion());
        verify(assets, never()).save(any(MediaAsset.class));
    }

    @Test
    @DisplayName("Sin voz o sin sonido, se dice claro y no se guarda nada")
    void sinVoz() {
        when(ia.transcribirConTiempos(any(), anyString())).thenReturn(Map.of("words", List.of()));
        assertThatThrownBy(() -> service.texto(video.getId(), WS)).hasMessageContaining("No escuché");
        when(ffmpeg.audio(anyString(), anyInt())).thenReturn(null);
        assertThatThrownBy(() -> service.texto(video.getId(), WS)).hasMessageContaining("no tiene sonido");
        assertThat(video.getTranscripcion()).isNull();
    }

    @Test
    @DisplayName("Sin elegir, estilo y color salen al azar; la copia queda en cola y el original no se toca")
    void alAzar() throws Exception {
        service.texto(video.getId(), WS);
        service.azar = new Random(7);
        service.pedir(video.getId(), WS, null, null, null);

        ArgumentCaptor<MediaAsset> guardado = ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).save(guardado.capture());
        MediaAsset copia = guardado.getValue();
        assertThat(copia.getSourceAssetId()).isEqualTo(video.getId());
        assertThat(copia.getStatus()).isEqualTo(MediaAssetStatus.PROCESSING);
        assertThat(copia.getFileName()).isEqualTo("reel-subtitulado.mp4");
        Map<?, ?> encargo = json.readValue(copia.getSubtitulos(), Map.class);
        assertThat(encargo.get("estilo")).isIn("PALABRA", "REBOTE", "CLASICO");
        assertThat(encargo.get("color")).isIn("#D4A858", GuionAss.AMARILLO);
        assertThat(video.getStatus()).isEqualTo(MediaAssetStatus.READY);
        verify(cola).dispatch(any(), any());
    }

    @Test
    @DisplayName("Lo elegido se respeta, con el texto corregido; sin texto no se encarga nada")
    void elegidoYCorregido() throws Exception {
        service.pedir(video.getId(), WS, EstiloSubtitulos.REBOTE, SubtitulosService.Color.MARCA,
                List.of(new Frase(0, 1, "Hola SAT", List.of())));
        ArgumentCaptor<MediaAsset> guardado = ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).save(guardado.capture());
        Map<?, ?> encargo = json.readValue(guardado.getValue().getSubtitulos(), Map.class);
        assertThat(encargo.get("estilo")).isEqualTo("REBOTE");
        assertThat(encargo.get("color")).isEqualTo("#D4A858");
        assertThat(guardado.getValue().getSubtitulos()).contains("SAT");

        assertThatThrownBy(() -> service.pedir(video.getId(), WS, null, null, List.of(new Frase(0, 1, " ", null))))
                .hasMessageContaining("No quedó texto");
    }

    @Test
    @DisplayName("El guion de la copia sale a la medida del video")
    void guionDeLaCopia() {
        service.pedir(video.getId(), WS, EstiloSubtitulos.PALABRA, SubtitulosService.Color.AMARILLO,
                List.of(new Frase(0, 1, "Hola amigos", List.of())));
        ArgumentCaptor<MediaAsset> guardado = ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).save(guardado.capture());
        String ass = SubtitulosService.guion(json, guardado.getValue(), new FfmpegImagen.MedidasVideo(720, 1280, 5));
        assertThat(ass).contains("PlayResX: 720").contains("&H0000D4FF&");
        verify(ia, never()).transcribirConTiempos(any(), anyString());
    }

    @Test
    @DisplayName("Una copia con solo el logo se encarga, se llama con-logo y la biblioteca sabe que lo lleva")
    void soloLogo() {
        service.pedirEdicion(video.getId(), WS, null, null, false, null, null, "https://cdn/logo.png");
        ArgumentCaptor<MediaAsset> guardado = ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).save(guardado.capture());
        MediaAsset copia = guardado.getValue();
        assertThat(copia.getFileName()).isEqualTo("reel-con-logo.mp4");
        assertThat(SubtitulosService.logo(json, copia)).isEqualTo("https://cdn/logo.png");
        assertThat(SubtitulosService.llevaLogo(copia)).isTrue();
        assertThat(SubtitulosService.pintaSubtitulos(copia)).isFalse();

        service.pedir(video.getId(), WS, null, null, List.of(new Frase(0, 1, "Hola", List.of())));
        verify(assets, times(2)).save(guardado.capture());
        assertThat(SubtitulosService.llevaLogo(guardado.getValue())).isFalse();
        assertThat(SubtitulosService.logo(json, guardado.getValue())).isNull();
    }

    @Test
    @DisplayName("Si otra tarea borró la transcripción, la copia automática sale igual con subtítulos y se vuelve a poner")
    void transcripcionBorradaEnMedio() {
        // El 114023: Whisper ya lo escuchó, pero la fila llega sin transcripción.
        List<Palabra> escuchadas = List.of(new Palabra("Hola", 0.1, 0.4), new Palabra("amigos", 0.4, 0.9));
        assertThat(video.getTranscripcion()).isNull();

        service.encargarEdicion(video.getId(), WS, EstiloSubtitulos.REBOTE, null, escuchadas, null, null, null);

        ArgumentCaptor<MediaAsset> guardado = ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).save(guardado.capture());
        MediaAsset copia = guardado.getValue();
        assertThat(copia.getFileName()).isEqualTo("reel-subtitulado.mp4");
        assertThat(SubtitulosService.pintaSubtitulos(copia)).isTrue();
        assertThat(copia.getSubtitulos()).contains("amigos");
        verify(assets).ponerTranscripcionSiFalta(org.mockito.ArgumentMatchers.eq(video.getId()),
                org.mockito.ArgumentMatchers.contains("amigos"));
    }
}
