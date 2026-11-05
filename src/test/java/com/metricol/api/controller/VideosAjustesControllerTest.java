package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.media.subtitulos.EstiloSubtitulos;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;
import com.metricol.api.service.media.subtitulos.SubtitulosService;
import com.metricol.api.service.reels.MusicaDeReels;
import com.metricol.api.service.reels.ReelMusicCatalog;

/** Un toque en la app cambia solo lo que tocó. */
class VideosAjustesControllerTest {

    private static final UUID WS = UUID.randomUUID();
    private SubtitulosAutomaticos automaticos;
    private MusicaDeReels musica;
    private VideosAjustesController controller;
    private User persona;

    @BeforeEach
    void preparar() {
        automaticos = mock(SubtitulosAutomaticos.class);
        musica = mock(MusicaDeReels.class);
        ReelMusicCatalog catalogo = mock(ReelMusicCatalog.class);
        when(catalogo.ambientes()).thenReturn(Set.of("alegre", "elegante"));
        controller = new VideosAjustesController(automaticos, musica, catalogo, mock(PermissionService.class));
        persona = mock(User.class);
        Workspace espacio = mock(Workspace.class);
        when(espacio.getId()).thenReturn(WS);
        when(persona.getWorkspace()).thenReturn(espacio);
        when(automaticos.ajustes(WS)).thenReturn(new SubtitulosAutomaticos.Ajustes(true, EstiloSubtitulos.REBOTE,
                SubtitulosService.Color.AMARILLO, true, false, true));
        when(musica.ajustes(WS)).thenReturn(new MusicaDeReels.Ajustes(true, "elegante", true));
    }

    private static VideosAjustesController.Ajustes cambio(Boolean subtitulos, String estilo, Boolean logo,
            Boolean musicaVideos, String ambiente) {
        return new VideosAjustesController.Ajustes(subtitulos, estilo, null, null, logo, null, musicaVideos, null,
                ambiente, null);
    }

    @Test
    @DisplayName("Encender el logo no le borra el estilo ni el color, y la música ni se toca")
    void soloElLogo() {
        controller.cambiar(persona, cambio(null, null, true, null, null));
        verify(automaticos).guardar(eq(WS), eq(new SubtitulosAutomaticos.Ajustes(true, EstiloSubtitulos.REBOTE,
                SubtitulosService.Color.AMARILLO, null, true, null)));
        verify(musica, never()).guardar(any(), any());
    }

    @Test
    @DisplayName("Apagar la música de los videos deja el ambiente y los reels como estaban")
    void soloLaMusica() {
        controller.cambiar(persona, cambio(null, null, null, false, null));
        verify(musica).guardar(WS, new MusicaDeReels.Ajustes(true, "elegante", false));
        verify(automaticos, never()).guardar(any(), any());
    }

    @Test
    @DisplayName("Vacío es 'que vaya cambiando'; una opción inventada se rechaza con palabras claras")
    void vacioEsAlAzar() {
        controller.cambiar(persona, cambio(null, "", null, null, null));
        verify(automaticos).guardar(eq(WS), eq(new SubtitulosAutomaticos.Ajustes(true, null,
                SubtitulosService.Color.AMARILLO, null, null, null)));

        assertThatThrownBy(() -> controller.cambiar(persona, cambio(null, "NEON", null, null, null)))
                .hasMessage("Esa opción no existe.");
    }

    @Test
    @DisplayName("Lo que se lee junta subtítulos y música en una sola respuesta")
    void leerTodo() {
        VideosAjustesController.Ajustes a = controller.ver(persona).getBody().getResult();
        assertThat(a.subtitulos()).isTrue();
        assertThat(a.estilo()).isEqualTo("REBOTE");
        assertThat(a.hayLogo()).isTrue();
        assertThat(a.musicaVideos()).isTrue();
        assertThat(a.ambiente()).isEqualTo("elegante");
        assertThat(a.ambientes()).contains("alegre");
    }
}
