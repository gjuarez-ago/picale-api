package com.metricol.api.service.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;

/** La app se cae a media tanda: lo que ya llegó completo aparece en minutos, no en una hora. */
class RescateDeSubidasTest {

    @Test
    @DisplayName("confirma con la confirmación normal lo que llegó, y sigue aunque uno aún no esté completo")
    void rescataLasCompletas() {
        UUID completa = UUID.randomUUID();
        UUID subiendo = UUID.randomUUID();
        MediaAssetRepository repo = mock(MediaAssetRepository.class);
        MediaService media = mock(MediaService.class);
        when(repo.findPrefirmadosViejos(any(), anyInt())).thenReturn(List.of(
                new Object[] {subiendo.toString(), "ws-1"},
                new Object[] {completa.toString(), "ws-1"}));
        when(media.confirm(subiendo)).thenThrow(new IllegalStateException("El archivo no llegó a subirse."));

        MediaOrphanWorker worker = new MediaOrphanWorker(repo, mock(MediaAssetReconciler.class));
        worker.setMedia(media);
        ReflectionTestUtils.setField(worker, "rescateMinutos", 3L);
        ReflectionTestUtils.setField(worker, "tope", 100);

        worker.rescatarCompletas();

        verify(media).confirm(subiendo);
        verify(media).confirm(completa);
    }
}
