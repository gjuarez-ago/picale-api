package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.config.ReelsProperties;
import com.metricol.api.service.reels.FfmpegReelRenderer;
import com.metricol.api.service.reels.MusicaDeReels;
import com.metricol.api.service.reels.ReelMusicCatalog;
import com.metricol.api.service.storage.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** El recorrido de un video con subtítulos y música automáticos: nunca se queda atorado. */
class SubtitulosAutomaticosTest {

    private static final UUID WS = UUID.randomUUID();
    private MediaAssetRepository assets;
    private SubtitulosService subtitulos;
    private RecorteAutomatico recortes;
    private SubtitulosAutomaticos automaticos;
    private Workspace espacio;
    private MediaAsset video;

    @BeforeEach
    void preparar() {
        assets = mock(MediaAssetRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        subtitulos = mock(SubtitulosService.class);
        recortes = mock(RecorteAutomatico.class);
        ReelMusicCatalog catalogo = new ReelMusicCatalog(new ReelsProperties(), mock(R2StorageService.class),
                new ObjectMapper());
        automaticos = new SubtitulosAutomaticos(assets, workspaces, subtitulos,
                new MusicaDeReels(workspaces, catalogo), catalogo, recortes, null);
        espacio = new Workspace();
        espacio.setSubtitulosAuto(true);
        espacio.setSubtitulosEstilo("REBOTE");
        // Las pruebas de solo subtítulos apagan la música; las de música la encienden.
        espacio.setMusicaVideos(false);
        when(workspaces.findById(WS)).thenReturn(Optional.of(espacio));
        when(workspaces.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        video = MediaAsset.builder().id(UUID.randomUUID()).type(MediaType.VIDEO).status(MediaAssetStatus.READY)
                .subtitulosEstado(SubtitulosAutomaticos.ESPERANDO).build();
        video.setTenantId(WS.toString());
        when(assets.findById(video.getId())).thenReturn(Optional.of(video));
        when(subtitulos.palabras(video.getId(), WS)).thenReturn(HABLA);
    }

    private static final java.util.List<Palabra> HABLA = java.util.List.of(new Palabra("hola", 1, 1.5));

    @Test
    @DisplayName("Saca el texto y encarga la copia con el estilo de la cuenta; el agente sigue esperando")
    void encargaConElEstiloDeLaCuenta() {
        automaticos.procesar(video.getId());
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), eq(EstiloSubtitulos.REBOTE), eq(null), eq(HABLA),
                eq(null), eq(null), eq(null));
        verify(subtitulos, never()).yaTraeMusica(any(), any(), any());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.HACIENDO);
        assertThat(video.getSubtitulosIntentos()).isEqualTo(1);
    }

    @Test
    @DisplayName("Si ya traía subtítulos pegados, se suelta tal cual: no salen dobles")
    void yaTenia() {
        when(subtitulos.yaTraeSubtitulos(video.getId(), WS)).thenReturn(true);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.YA_TENIA);
        verify(subtitulos, never()).encargarEdicion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Si nadie habla, se suelta sin subtítulos a la primera")
    void sinVozSeSuelta() {
        when(subtitulos.palabras(video.getId(), WS)).thenReturn(java.util.List.of());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        verify(subtitulos, never()).encargarEdicion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Un fallo se reintenta, y a la tercera se suelta sin subtítulos")
    void tresFallosYSeSuelta() {
        doThrow(new IllegalStateException("OpenAI no contesta")).when(subtitulos).palabras(video.getId(), WS);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        automaticos.procesar(video.getId());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        assertThat(video.getSubtitulosIntentos()).isEqualTo(3);
    }

    @Test
    @DisplayName("Si apagaron todo mientras esperaba, el video se suelta tal cual")
    void apagados() {
        espacio.setSubtitulosAuto(false);
        espacio.setRecorteAuto(false);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isNull();
        verify(subtitulos, never()).palabras(any(), any());
    }

    @Test
    @DisplayName("Copia lista: se suelta con copia. Copia fallida: se reintenta, y a la tercera se suelta")
    void copiaTerminada() {
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(1);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(3);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), true);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);

        // Una copia pedida a mano no toca un video que no la esperaba: ni lo
        // devuelve a la fila de los automáticos si falla.
        video.setSubtitulosEstado(SubtitulosAutomaticos.LISTO);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Si la copia termina mientras se encarga, gana el listo: el video no se queda haciendo")
    void carreraConLaCola() {
        // La cola termina la copia dentro de pedir(), antes de que vuelva.
        when(subtitulos.encargarEdicion(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), true);
            return null;
        });
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Con la cola llena espera su turno sin gastar un intento")
    void colaLlena() {
        doThrow(new SubtitulosService.ColaLlena()).when(subtitulos).encargarEdicion(any(), any(), any(), any(),
                any(), any(), any(), any());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        assertThat(video.getSubtitulosIntentos()).isZero();
    }

    @Test
    @DisplayName("Un video haciendo sin copia en la cola se suelta: con su copia si ya está, o de vuelta a esperar")
    void atorado() {
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(1);
        when(assets.copiasConSubtitulos(video.getId())).thenReturn(java.util.List.of());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);

        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        when(assets.copiasConSubtitulos(video.getId())).thenReturn(java.util.List.of(new MediaAsset()));
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Con música y voz: subtítulos y música bajita en la misma copia")
    void musicaBajoLaVoz() {
        espacio.setMusicaVideos(true);
        espacio.setMusicaAmbiente("elegante");
        automaticos.procesar(video.getId());
        org.mockito.ArgumentCaptor<SubtitulosService.MusicaDeCopia> cancion =
                org.mockito.ArgumentCaptor.forClass(SubtitulosService.MusicaDeCopia.class);
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), eq(EstiloSubtitulos.REBOTE), eq(null), eq(HABLA),
                cancion.capture(), eq(null), eq(null));
        assertThat(cancion.getValue().volumen()).isEqualTo(FfmpegReelRenderer.MUSICA_QUE_SE_AGACHA);
        assertThat(video.getMusicaFondo()).isEqualTo(SubtitulosAutomaticos.MUSICA_PUESTA);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.HACIENDO);
    }

    @Test
    @DisplayName("Sin tocar el ajuste, la música va encendida")
    void musicaDeEntrada() {
        espacio.setMusicaVideos(null);
        automaticos.procesar(video.getId());
        verify(subtitulos).yaTraeMusica(video.getId(), WS, HABLA);
        assertThat(video.getMusicaFondo()).isEqualTo(SubtitulosAutomaticos.MUSICA_PUESTA);
    }

    @Test
    @DisplayName("Solo música y nadie habla: música completa, sin subtítulos")
    void soloMusicaSinVoz() {
        espacio.setSubtitulosAuto(false);
        espacio.setMusicaVideos(true);
        when(subtitulos.palabras(video.getId(), WS)).thenReturn(java.util.List.of());
        automaticos.procesar(video.getId());
        org.mockito.ArgumentCaptor<SubtitulosService.MusicaDeCopia> cancion =
                org.mockito.ArgumentCaptor.forClass(SubtitulosService.MusicaDeCopia.class);
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), any(), any(), eq(java.util.List.of()), cancion.capture(), eq(null), eq(null));
        assertThat(cancion.getValue().volumen()).isEqualTo(FfmpegReelRenderer.MUSICA_SOLA);
        verify(subtitulos, never()).yaTraeSubtitulos(any(), any());
    }

    @Test
    @DisplayName("Si ya trae música, no se le pone otra; si tampoco lleva subtítulos, se suelta tal cual")
    void yaTraeMusica() {
        espacio.setMusicaVideos(true);
        when(subtitulos.yaTraeMusica(video.getId(), WS, HABLA)).thenReturn(true);
        automaticos.procesar(video.getId());
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), any(), any(), eq(HABLA), eq(null), eq(null), eq(null));
        assertThat(video.getMusicaFondo()).isEqualTo(SubtitulosAutomaticos.YA_TENIA);

        video.setSubtitulosEstado(SubtitulosAutomaticos.ESPERANDO);
        when(subtitulos.yaTraeSubtitulos(video.getId(), WS)).thenReturn(true);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.YA_TENIA);
        assertThat(video.getMusicaFondo()).isEqualTo(SubtitulosAutomaticos.YA_TENIA);
    }

    @Test
    @DisplayName("Los ajustes se guardan; sin estilo ni color es al azar")
    void ajustes() {
        SubtitulosAutomaticos.Ajustes guardados = automaticos.guardar(WS,
                new SubtitulosAutomaticos.Ajustes(true, null, SubtitulosService.Color.AMARILLO, null));
        assertThat(guardados.activo()).isTrue();
        assertThat(guardados.estilo()).isNull();
        assertThat(guardados.color()).isEqualTo(SubtitulosService.Color.AMARILLO);
        assertThat(espacio.getSubtitulosEstilo()).isNull();
        // El recorte viene encendido y se apaga desde la misma ventana.
        assertThat(guardados.recortes()).isTrue();
        assertThat(automaticos.guardar(WS, new SubtitulosAutomaticos.Ajustes(true, null, null, false)).recortes())
                .isFalse();
        assertThat(automaticos.guardar(WS, new SubtitulosAutomaticos.Ajustes(true, null, null, null)).recortes())
                .isFalse();
    }

    @Test
    @DisplayName("Si el revisor aprueba un recorte, va en la misma copia; con el recorte apagado ni se pregunta")
    void recorte() {
        espacio.setSubtitulosAuto(false);
        RecorteAutomatico.Recorte r = new RecorteAutomatico.Recorte(1.5, 9, 95, "Le quité 2 s del principio.");
        when(subtitulos.segundos(video.getId(), WS)).thenReturn(10.0);
        when(recortes.decidir(video.getId(), HABLA, 10.0)).thenReturn(r);
        automaticos.procesar(video.getId());
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), any(), any(), eq(java.util.List.of()), eq(null), eq(r), eq(null));
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.HACIENDO);

        video.setSubtitulosEstado(SubtitulosAutomaticos.ESPERANDO);
        espacio.setRecorteAuto(false);
        espacio.setSubtitulosAuto(true);
        org.mockito.Mockito.clearInvocations(recortes);
        automaticos.procesar(video.getId());
        verify(recortes, never()).decidir(any(), any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("El logo va apagado de entrada; encendido, la copia lo lleva aunque no haya nada más que poner")
    void logo() {
        espacio.setSubtitulosAuto(false);
        espacio.setRecorteAuto(false);
        espacio.setLogoUrl("https://cdn/logo.png");
        automaticos.procesar(video.getId());
        // Apagado: sin subtítulos, música ni recorte, el video se suelta tal cual.
        verify(subtitulos, never()).encargarEdicion(any(), any(), any(), any(), any(),
                any(), any(), any());
        assertThat(automaticos.ajustes(WS).logo()).isFalse();
        assertThat(automaticos.ajustes(WS).hayLogo()).isTrue();

        video.setSubtitulosEstado(SubtitulosAutomaticos.ESPERANDO);
        automaticos.guardar(WS, new SubtitulosAutomaticos.Ajustes(false, null, null, null, true, null));
        assertThat(espacio.getRecorteAuto()).isFalse();
        automaticos.procesar(video.getId());
        verify(subtitulos).encargarEdicion(eq(video.getId()), eq(WS), any(), any(), eq(java.util.List.of()), eq(null), eq(null),
                eq("https://cdn/logo.png"));
    }

    @Test
    @DisplayName("Sin logo en la marca, el ajuste encendido no hace nada y la ventana lo dice")
    void sinLogo() {
        espacio.setSubtitulosAuto(false);
        espacio.setRecorteAuto(false);
        espacio.setLogoVideos(true);
        automaticos.procesar(video.getId());
        verify(subtitulos, never()).encargarEdicion(any(), any(), any(), any(), any(),
                any(), any(), any());
        assertThat(automaticos.ajustes(WS).hayLogo()).isFalse();
    }
}
