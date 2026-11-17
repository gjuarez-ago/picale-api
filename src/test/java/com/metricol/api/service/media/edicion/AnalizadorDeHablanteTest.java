package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.storage.R2StorageService;

class AnalizadorDeHablanteTest {
    private final UUID ws = UUID.randomUUID(), id = UUID.randomUUID();
    private final FuenteDeVideoHablante fuentes = mock(FuenteDeVideoHablante.class);
    private final R2StorageService storage = mock(R2StorageService.class);
    private final FfmpegImagen ffmpeg = mock(FfmpegImagen.class);
    private final EjecutorAnalisisHablante executor = mock(EjecutorAnalisisHablante.class);
    private final AtomicReference<Path> downloaded = new AtomicReference<>();
    private final EdicionDinamicaProperties config = new EdicionDinamicaProperties();
    private final FuenteDeVideoHablante.Fuente fuente = new FuenteDeVideoHablante.Fuente(
            ws, id, "media/" + ws + "/original.mp4", 4, "a".repeat(64));
    private AnalizadorDeHablante analizador;

    @BeforeEach
    void preparar() throws Exception {
        config.setEnabled(true);
        config.setPilotWorkspaces(Set.of(ws));
        analizador = new AnalizadorDeHablante(fuentes, storage, ffmpeg, executor,
                new ControlDeEdicionDinamica(config), new AnalisisHablanteProperties());
        when(fuentes.cargar(id, ws)).thenReturn(fuente);
        when(executor.disponible()).thenReturn(true);
        when(storage.descargarLimitado(anyString(), any(), anyLong())).thenAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            downloaded.set(file);
            Files.write(file, new byte[]{1, 2, 3, 4});
            return true;
        });
        when(ffmpeg.medirVideo(anyString())).thenReturn(new FfmpegImagen.MedidasVideo(360, 640, 4));
        var v = DatosDeInformeHablante.valido();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1, 2, 3, 4}));
        when(executor.analizar(any(), any())).thenReturn(new InformeDeHablante(v.schemaVersion(), v.timeBase(),
                v.detectorVersion(), v.evidenceRevision(), hash, v.durationSeconds(), v.normalization(),
                v.activeSpeakerVerified(), v.intervals(), v.samples(), v.modelRevisions(), v.elapsedSeconds()));
    }

    @Test
    void creaContextoConHuellaRealYEliminaElOriginalTemporal() throws Exception {
        var resultado = analizador.analizar(id, ws, PerfilEdicionDinamica.SUBTLE);
        assertThat(resultado.elegible()).isTrue();
        assertThat(resultado.contexto().workspaceId()).isEqualTo(ws);
        assertThat(resultado.contexto().sourceRevision()).isEqualTo(resultado.informe().sourceSha256());
        assertThat(resultado.contexto().transcriptRevision()).isEqualTo(fuente.transcriptRevision());
        assertThat(downloaded.get()).doesNotExist();
        assertThat(downloaded.get().getParent()).doesNotExist();
    }

    @Test
    void controlApagadoNoConsultaNiDescarga() {
        config.setEnabled(false);
        assertThatIllegalStateException().isThrownBy(() -> analizador.analizar(id, ws, PerfilEdicionDinamica.SUBTLE));
        verifyNoInteractions(fuentes, storage, ffmpeg, executor);
    }

    @Test
    void evidenciaDeOtroArchivoNoSeAceptaYLimpiaTemporales() throws Exception {
        when(executor.analizar(any(), any())).thenReturn(DatosDeInformeHablante.valido());
        assertThatIllegalArgumentException().isThrownBy(() -> analizador.analizar(id, ws, PerfilEdicionDinamica.SUBTLE));
        assertThat(downloaded.get().getParent()).doesNotExist();
    }

    @Test
    void noEntregaContextoSiElTextoCambioDuranteElAnalisis() {
        when(fuentes.cargar(id, ws)).thenReturn(fuente, new FuenteDeVideoHablante.Fuente(
                ws, id, fuente.storageKey(), 4, "b".repeat(64)));
        assertThatIllegalStateException().isThrownBy(() -> analizador.analizar(id, ws, PerfilEdicionDinamica.SUBTLE))
                .withMessageContaining("cambió");
        assertThat(downloaded.get().getParent()).doesNotExist();
    }

    @Test
    void noEntregaContextoSiElObjetoCambioDeTamano() throws Exception {
        doAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            downloaded.set(file);
            Files.write(file, new byte[]{1, 2, 3});
            return true;
        }).when(storage).descargarLimitado(anyString(), any(), anyLong());
        org.assertj.core.api.Assertions.assertThatIOException()
                .isThrownBy(() -> analizador.analizar(id, ws, PerfilEdicionDinamica.SUBTLE));
        assertThat(downloaded.get().getParent()).doesNotExist();
    }
}
