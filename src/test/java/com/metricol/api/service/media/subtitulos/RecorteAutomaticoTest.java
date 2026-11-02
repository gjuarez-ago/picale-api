package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.FfmpegImagen;

/** El recorte automático: solo lo que sobra en los extremos, y solo con 90% o más. */
class RecorteAutomaticoTest {

    private static Palabra p(String t, double i, double f) {
        return new Palabra(t, i, f);
    }

    /** Habla del 3 al 15 en un video de 20 s: silencio largo al principio y al final. */
    private static final List<Palabra> CON_SILENCIOS = List.of(p("Hola", 3, 3.4), p("soy", 3.5, 3.8),
            p("Juan", 3.9, 4.3), p("y", 6, 6.1), p("hoy", 6.2, 6.5), p("te", 8, 8.2), p("cuento", 8.3, 9),
            p("algo", 12, 12.5), p("bueno.", 14.5, 15));

    @Test
    @DisplayName("Quita los silencios de los extremos con un margen, sin meterse en ninguna palabra")
    void silencios() {
        RecorteAutomatico.Propuesta r = RecorteAutomatico.proponer(CON_SILENCIOS, 20);
        assertThat(r).isNotNull();
        assertThat(r.inicio()).isEqualTo(3 - RecorteAutomatico.MARGEN_ANTES);
        assertThat(r.fin()).isEqualTo(15 + RecorteAutomatico.MARGEN_DESPUES);
        assertThat(r.quitaInicio()).isEmpty();
        assertThat(r.quitaFinal()).isEmpty();
    }

    @Test
    @DisplayName("Un «¿ya?» al principio y un «ya, corta» al final, separados por una pausa clara, se proponen quitar")
    void arranqueYCierreFalsos() {
        List<Palabra> palabras = List.of(p("¿Ya?", 0.5, 0.9), p("Hola,", 3, 3.4), p("hoy", 3.5, 3.8),
                p("les", 4, 4.2), p("cuento", 4.3, 4.9), p("sobre", 5, 5.3), p("impuestos", 5.4, 6),
                p("y", 7, 7.1), p("algo", 7.2, 7.5), p("más.", 7.6, 8), p("muy", 15, 15.3), p("bien.", 15.4, 16),
                p("Ya,", 18.5, 18.8), p("corta.", 18.9, 19.3));
        RecorteAutomatico.Propuesta r = RecorteAutomatico.proponer(palabras, 20);
        assertThat(r).isNotNull();
        assertThat(r.quitaInicio()).isEqualTo("¿Ya?");
        assertThat(r.quitaFinal()).isEqualTo("Ya, corta.");
        assertThat(r.inicio()).isGreaterThan(0.9).isLessThan(3);
        assertThat(r.fin()).isGreaterThan(16).isLessThan(18.5);
    }

    @Test
    @DisplayName("Si casi no sobra nada, o lo que queda sería muy poco, no propone nada")
    void nadaQueQuitar() {
        assertThat(RecorteAutomatico.proponer(List.of(p("Todo", 0.3, 9.6)), 10)).isNull();
        assertThat(RecorteAutomatico.proponer(List.of(p("Hola", 8, 9)), 20)).isNull();
        assertThat(RecorteAutomatico.proponer(List.of(), 20)).isNull();
    }

    @Test
    @DisplayName("Una intro larga con música (CapCut) no se toca: más de 6 s por lado, o algo que suena, y va entero")
    void introConMusica() {
        List<Palabra> tarde = List.of(p("Hola", 9, 9.5), p("cómo", 10, 10.4), p("están", 10.5, 11),
                p("hoy", 15, 15.3), p("vemos", 16, 16.5), p("algo.", 20, 20.5));
        assertThat(RecorteAutomatico.proponer(tarde, 22)).isNull();

        RecorteAutomatico.Propuesta r = RecorteAutomatico.proponer(CON_SILENCIOS, 20);
        double[] niveles = new double[200];
        java.util.Arrays.fill(niveles, -80);
        assertThat(RecorteAutomatico.suenaLoQueSeQuita(niveles, r, 20)).isFalse();
        java.util.Arrays.fill(niveles, 0, 30, -20); // música en los primeros 3 s
        assertThat(RecorteAutomatico.suenaLoQueSeQuita(niveles, r, 20)).isTrue();
    }

    @Test
    @DisplayName("Los subtítulos se corren al nuevo inicio y se van las palabras que se quitaron")
    void corridas() {
        List<Palabra> r = RecorteAutomatico.corridas(List.of(p("¿Ya?", 0.5, 0.9), p("Hola", 3, 3.4)), 2.65, 10);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).inicio()).isCloseTo(0.35, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Recorta con 90 o más; con 89 el video va entero; sin nada que quitar ni se pregunta")
    void noventaPorCiento() {
        OpenAiClient ia = mock(OpenAiClient.class);
        FfmpegImagen ffmpeg = mock(FfmpegImagen.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        UUID id = UUID.randomUUID();
        MediaAsset video = new MediaAsset();
        video.setUrl("https://cdn.test/v.mp4");
        when(assets.findById(id)).thenReturn(Optional.of(video));
        when(ffmpeg.cuadro(anyString(), anyDouble(), anyInt(), anyInt())).thenReturn(new byte[] { 1, 2, 3 });
        double[] callado = new double[200];
        java.util.Arrays.fill(callado, -80);
        when(ffmpeg.nivelesDeAudio(anyString(), anyInt())).thenReturn(callado);
        RecorteAutomatico recorte = new RecorteAutomatico(assets, ffmpeg, ia);

        when(ia.describeImages(eq(AiOperacion.RECORTE_AUTOMATICO), anyString(), anyString(), any()))
                .thenReturn("{\"probabilidad\": 94, \"motivo\": \"solo silencio\"}");
        RecorteAutomatico.Recorte si = recorte.decidir(id, CON_SILENCIOS, 20);
        assertThat(si).isNotNull();
        assertThat(si.confianza()).isEqualTo(94);
        assertThat(si.nota()).isEqualTo("Le quité 3 s del principio y 4 s del final: lo que sobraba.");

        when(ia.describeImages(eq(AiOperacion.RECORTE_AUTOMATICO), anyString(), anyString(), any()))
                .thenReturn("{\"probabilidad\": 89, \"motivo\": \"se pierde el saludo\"}");
        assertThat(recorte.decidir(id, CON_SILENCIOS, 20)).isNull();

        when(ia.describeImages(eq(AiOperacion.RECORTE_AUTOMATICO), anyString(), anyString(), any()))
                .thenReturn("no entendí");
        assertThat(recorte.decidir(id, CON_SILENCIOS, 20)).isNull();

        org.mockito.Mockito.clearInvocations(ia);
        assertThat(recorte.decidir(id, List.of(p("Todo", 0.3, 9.6)), 10)).isNull();
        verify(ia, never()).describeImages(any(), anyString(), anyString(), any());
    }
}
