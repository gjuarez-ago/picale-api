package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

class CompiladorDeAcercamientosTest {
    private final CompiladorDeAcercamientos compilador = new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion());

    @Test
    void entraYRegresaAlPlanoOriginalSinSaltosYUsaPtsReales() throws Exception {
        var c = DatosDeAcercamientos.crear();
        var render = compilador.compilar(c.preparado(), c.contexto(), c.informe(), 320, 240);
        var m = render.movimientos().get(0);
        assertThat(m.anchorX()).isEqualTo(.4);
        assertThat(m.anchorY()).isEqualTo(.4);
        assertThat(m.escalaEn(.5)).isEqualTo(1);
        assertThat(m.escalaEn(.8)).isEqualTo(1.12);
        assertThat(m.escalaEn(1.5)).isEqualTo(1.12);
        assertThat(m.escalaEn(2.5)).isEqualTo(1);
        assertThat(m.escalaEn(3)).isEqualTo(1);
        assertThat(m.escalaEn(.50001) - 1).isLessThan(.000001);
        assertThat(render.filtro()).contains("eval=frame", "crop=w=320:h=240", "setsar=1").doesNotContain("zoompan", "fps=");
    }

    @Test
    void noAceptaUnaIdentidadPreparadaFalsificada() throws Exception {
        var c = DatosDeAcercamientos.crear();
        var fake = new PreparadorDePlanDeEdicion.PlanPreparado(c.preparado().plan(), "{}", "a".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(() -> compilador.compilar(fake, c.contexto(), c.informe(), 320, 240))
                .withMessageContaining("identidad");
    }

    @Test
    void rostroJuntoAlBordeNoSeRecortaParaCumplirLaReceta() throws Exception {
        var v = DatosDeInformeHablante.valido();
        var rows = v.samples().stream().map(s -> new InformeDeHablante.Muestra(s.time(), s.scene(), s.track(),
                s.faceCount(), List.of(.2, .01, .4, .4), s.mouthOpening(), s.voiceProbability(), s.speech(),
                s.activeSpeakerProbability(), s.mouthMotion())).toList();
        var c = DatosDeAcercamientos.crear(DatosDeInformeHablante.conMuestras(rows), new Tramo(0, 4),
                List.of(DatosDeEdicion.zoom(.5)));
        assertThatIllegalArgumentException().isThrownBy(() -> compilador.compilar(c.preparado(), c.contexto(), c.informe(), 320, 240))
                .withMessageContaining("margen");
    }

    @Test
    void noSimulaOtrosEfectosQueTodaviaNoPuedeEjecutar() throws Exception {
        var event = DatosDeEdicion.evento(PlanDeEdicionVideo.TipoEfecto.CARD_INTRO, 0, 2, 1, "person-1");
        var c = DatosDeAcercamientos.crear("a".repeat(64), new Tramo(0, 4), List.of(event));
        assertThatIllegalArgumentException().isThrownBy(() -> compilador.compilar(c.preparado(), c.contexto(), c.informe(), 320, 240))
                .withMessageContaining("solo admite");
    }

    @Test
    void unRecorteNoSumaDosVecesElDesplazamientoDeLosEventos() throws Exception {
        var c = DatosDeAcercamientos.crear("a".repeat(64), new Tramo(.04, 3.54), List.of(DatosDeEdicion.zoom(.5)));
        var render = compilador.compilar(c.preparado(), c.contexto(), c.informe(), 320, 240);
        assertThat(render.inicioFuente()).isEqualTo(.04);
        assertThat(render.movimientos().get(0).start()).isEqualTo(.5);
    }

    @Test
    void rechazaDimensionesNoCodificablesYDesbordeDeRecursos() throws Exception {
        var c = DatosDeAcercamientos.crear();
        for (int[] size : List.of(new int[]{319, 240}, new int[]{0, 240}, new int[]{7680, 4320})) {
            assertThatIllegalArgumentException().isThrownBy(() -> compilador.compilar(c.preparado(), c.contexto(),
                    c.informe(), size[0], size[1]));
        }
    }
}
