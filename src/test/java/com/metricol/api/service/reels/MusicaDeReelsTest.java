package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.ReelsProperties;
import com.metricol.api.entity.Workspace;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.storage.R2StorageService;

class MusicaDeReelsTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final MusicaDeReels musica = new MusicaDeReels(workspaces,
            new ReelMusicCatalog(new ReelsProperties(), mock(R2StorageService.class), new ObjectMapper()));
    private final UUID ws = UUID.randomUUID();
    private final Workspace espacio = new Workspace();

    {
        when(workspaces.findById(ws)).thenReturn(Optional.of(espacio));
        when(workspaces.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Sin tocar nada: con música y el ambiente lo pone el giro")
    void deFabrica() {
        assertThat(musica.ajustes(ws)).isEqualTo(new MusicaDeReels.Ajustes(true, null));
        assertThat(musica.ajustes(UUID.randomUUID())).isEqualTo(new MusicaDeReels.Ajustes(true, null));
    }

    @Test
    @DisplayName("Se guarda apagada o con ambiente; un ambiente inventado no se acepta")
    void guardar() {
        assertThat(musica.guardar(ws, new MusicaDeReels.Ajustes(false, "elegante")))
                .isEqualTo(new MusicaDeReels.Ajustes(false, "elegante"));
        assertThat(espacio.getMusicaReels()).isFalse();
        assertThat(musica.guardar(ws, new MusicaDeReels.Ajustes(true, " "))).isEqualTo(new MusicaDeReels.Ajustes(true, null));
        assertThatThrownBy(() -> musica.guardar(ws, new MusicaDeReels.Ajustes(true, "reguetón")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Un ambiente que salió del catálogo cuenta como automático")
    void ambienteViejo() {
        espacio.setMusicaAmbiente("polka");
        assertThat(musica.ajustes(ws).ambiente()).isNull();
    }
}
