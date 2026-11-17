package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;

class FuenteDeVideoHablanteTest {
    @Test
    void soloCargaOriginalListoDeLaCuentaYSuPrefijo() {
        UUID ws = UUID.randomUUID(), id = UUID.randomUUID();
        var repository = mock(MediaAssetRepository.class);
        var asset = MediaAsset.builder().id(id).type(MediaType.VIDEO).status(MediaAssetStatus.READY)
                .storageKey("media/" + ws + "/original.mp4").sizeBytes(100L).build();
        asset.setTenantId(ws.toString());
        when(repository.findById(id)).thenReturn(Optional.of(asset));
        var loader = new FuenteDeVideoHablante(repository, new AnalisisHablanteProperties());
        var fuente = loader.cargar(id, ws);
        assertThat(fuente.transcriptRevision()).matches("[a-f0-9]{64}");
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, UUID.randomUUID()));
        asset.setStorageKey("media/" + UUID.randomUUID() + "/original.mp4");
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, ws));
        asset.setStorageKey("media/" + ws + "/../otra.mp4");
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, ws));
    }

    @Test
    void noAdmiteCopiasEnProcesoODeTamanoNoValidoYDetectaRevisionDeTexto() {
        UUID ws = UUID.randomUUID(), id = UUID.randomUUID();
        var repository = mock(MediaAssetRepository.class);
        var asset = MediaAsset.builder().id(id).type(MediaType.VIDEO).status(MediaAssetStatus.READY)
                .storageKey("media/" + ws + "/original.mp4").sizeBytes(100L).transcripcion("texto 1").build();
        asset.setTenantId(ws.toString());
        when(repository.findById(id)).thenReturn(Optional.of(asset));
        var loader = new FuenteDeVideoHablante(repository, new AnalisisHablanteProperties());
        var antes = loader.cargar(id, ws);
        asset.setTranscripcion("texto 2");
        assertThat(loader.cargar(id, ws).transcriptRevision()).isNotEqualTo(antes.transcriptRevision());
        asset.setSourceAssetId(UUID.randomUUID());
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, ws));
        asset.setSourceAssetId(null);
        asset.setStatus(MediaAssetStatus.PROCESSING);
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, ws));
        asset.setStatus(MediaAssetStatus.READY);
        asset.setSizeBytes(536870913L);
        assertThatIllegalArgumentException().isThrownBy(() -> loader.cargar(id, ws));
    }
}
