package com.metricol.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.MiniaturaDeVideo;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import com.metricol.api.service.storage.R2StorageService;

/** Al arrancar se rehacen las miniaturas que la fila apunta pero que ya no están en R2. */
class MiniaturasPerdidasTest {

    private static MediaAsset video(String clave) {
        MediaAsset a = new MediaAsset();
        a.setId(UUID.randomUUID());
        a.setTenantId("ws-1");
        a.setStorageKey(clave);
        a.setThumbnailUrl("https://cdn.ejemplo/" + MiniaturaDeVideo.claveDe(clave));
        return a;
    }

    @Test
    @DisplayName("la que falta en R2 se vuelve a sacar; la que está, no")
    void rehaceSoloLasPerdidas() {
        MediaAsset perdida = video("ws-1/a.mp4");
        MediaAsset viva = video("ws-1/b.mp4");
        MediaAssetRepository repo = mock(MediaAssetRepository.class);
        MiniaturasEnSegundoPlano miniaturas = mock(MiniaturasEnSegundoPlano.class);
        R2StorageService storage = mock(R2StorageService.class);
        when(repo.findVideosConMiniatura()).thenReturn(List.of(perdida, viva));
        when(storage.listarClaves(MiniaturaDeVideo.prefijo()))
                .thenReturn(List.of(MiniaturaDeVideo.claveDe("ws-1/b.mp4")));

        MiniaturasPendientes.rehacerLasPerdidas(repo, miniaturas, storage);

        verify(miniaturas).sacar(perdida.getId(), "ws-1", true);
        verify(miniaturas, never()).sacar(viva.getId(), "ws-1", true);
    }

    @Test
    @DisplayName("si R2 no contesta, no se toca nada ni se cae el arranque")
    void sinR2NoPasaNada() {
        MediaAssetRepository repo = mock(MediaAssetRepository.class);
        MiniaturasEnSegundoPlano miniaturas = mock(MiniaturasEnSegundoPlano.class);
        R2StorageService storage = mock(R2StorageService.class);
        when(storage.listarClaves(anyString())).thenThrow(new RuntimeException("sin red"));

        MiniaturasPendientes.rehacerLasPerdidas(repo, miniaturas, storage);

        verify(miniaturas, never()).sacar(any(), any(), anyBoolean());
    }
}
