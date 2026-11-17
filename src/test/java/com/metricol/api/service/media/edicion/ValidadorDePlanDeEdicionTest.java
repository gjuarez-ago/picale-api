package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Evento;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Motivo;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.TipoEfecto;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

class ValidadorDePlanDeEdicionTest {
    private final ValidadorDePlanDeEdicion validador = new ValidadorDePlanDeEdicion();

    @Test
    void cambiosDelPlanificadorNoModificanUnPlanNiUnaEvidenciaYaCreados() {
        var eventos = new ArrayList<>(List.of(DatosDeEdicion.zoom(10)));
        var plan = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), eventos);
        eventos.clear();
        assertThat(plan.events()).hasSize(1);
        assertThatThrownBy(() -> plan.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        var intervalos = new ArrayList<>(List.of(new IntervaloHablante(0, 120, "person-1")));
        var evidencia = new EvidenciaDeHablante("evidence-v1", "test-detector-v1", true, intervalos);
        intervalos.clear();
        assertThat(evidencia.intervals()).hasSize(1);
        assertThatThrownBy(() -> evidencia.intervals().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void admiteTodosLosEfectosBasicosEnUnPlanModerado() {
        var eventos = List.of(
                DatosDeEdicion.evento(TipoEfecto.CARD_INTRO, 0, 2, 1, "person-1"),
                DatosDeEdicion.zoom(15),
                DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 61, 63, 1, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.LETTERBOX_WINDOW, 110, 112, 1, "person-1"));
        var plan = DatosDeEdicion.plan(PerfilEdicionDinamica.DYNAMIC, new Tramo(0, 120), eventos);
        assertThatNoException().isThrownBy(() -> validador.validar(plan, DatosDeEdicion.contexto()));
    }

    @Test
    void convierteEventosDeSalidaASegundosDelOriginalUnaSolaVez() {
        var tramo = new Tramo(30, 60);
        var evidencia = new EvidenciaDeHablante("evidence-v1", "test-detector-v1", true,
                List.of(new IntervaloHablante(35, 40, "person-1")));
        var contexto = DatosDeEdicion.contexto(tramo, evidencia);
        var correcto = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, tramo, List.of(DatosDeEdicion.zoom(5)));
        assertThatNoException().isThrownBy(() -> validador.validar(correcto, contexto));
        var equivocado = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, tramo, List.of(DatosDeEdicion.zoom(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(equivocado, contexto))
                .withMessageContaining("tramo verificado");
    }

    @Test
    void aceptaElLimiteDecimalSinRechazosPorRedondeoBinarioPeroRechazaUnExcesoReal() {
        var limite = DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 20.1, 20.4, 1.12, "person-1");
        assertThatNoException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(limite)),
                DatosDeEdicion.contexto()));
        var demasiadoCorto = DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 20.1, 20.399, 1.12, "person-1");
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(demasiadoCorto)),
                DatosDeEdicion.contexto()));
        var fueraDeSalida = DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 118, 120.001, 1.12, "person-1");
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(fueraDeSalida)),
                DatosDeEdicion.contexto()));
    }

    @Test
    void noExtiendeEfectosASilenciosNiCruzaUnCambioDePlano() {
        var evidencia = new EvidenciaDeHablante("evidence-v1", "test-detector-v1", true,
                List.of(new IntervaloHablante(0, 11, "person-1"), new IntervaloHablante(11, 120, "person-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(DatosDeEdicion.plan(),
                DatosDeEdicion.contexto(new Tramo(0, 120), evidencia))).withMessageContaining("tramo verificado");
    }

    @Test
    void unaCaraSinHablanteVerificadoNoBasta() {
        var evidencia = new EvidenciaDeHablante("evidence-v1", "test-detector-v1", false,
                List.of(new IntervaloHablante(0, 120, "person-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(DatosDeEdicion.plan(),
                DatosDeEdicion.contexto(new Tramo(0, 120), evidencia))).withMessageContaining("evidencia verificada");
    }

    @Test
    void evidenciaAusenteVaciaDesordenadaOSuperpuestaSeRechaza() {
        for (var intervalos : List.of(List.<IntervaloHablante>of(),
                List.of(new IntervaloHablante(20, 120, "person-1"), new IntervaloHablante(0, 20, "person-1")),
                List.of(new IntervaloHablante(0, 30, "person-1"), new IntervaloHablante(20, 120, "person-1")))) {
            var evidencia = new EvidenciaDeHablante("evidence-v1", "test-detector-v1", true, intervalos);
            assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(DatosDeEdicion.plan(),
                    DatosDeEdicion.contexto(new Tramo(0, 120), evidencia)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(DatosDeEdicion.plan(),
                DatosDeEdicion.contexto(new Tramo(0, 120), null)));
    }

    static Stream<Evento> eventosInvalidos() {
        return Stream.of(
                DatosDeEdicion.zoom(-1), DatosDeEdicion.zoom(119), DatosDeEdicion.zoom(Double.NaN),
                DatosDeEdicion.zoom(Double.POSITIVE_INFINITY),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 10, 1.12, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 10.1, 1.12, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 14, 1.12, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 12, 2, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 12, 1, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 12, Double.NaN, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 10, 12, 1.1, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.CARD_INTRO, 10, 12, 1, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 12, 1.12, "other-person"),
                DatosDeEdicion.evento(TipoEfecto.PUNCH_IN, 10, 12, 1.12, "https://otro/rostro"),
                new Evento(TipoEfecto.PUNCH_IN, 10, 12, 1.12, "no-existe", "person-1", Motivo.KEY_STATEMENT),
                new Evento(null, 10, 12, 1.12, "punch-in-v1", "person-1", Motivo.KEY_STATEMENT),
                new Evento(TipoEfecto.PUNCH_IN, 10, 12, 1.12, "punch-in-v1", "person-1", null));
    }

    @ParameterizedTest
    @MethodSource("eventosInvalidos")
    void rechazaTiemposEscalasPlantillasYRostrosNoAutorizados(Evento evento) {
        var plan = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(evento));
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(plan, DatosDeEdicion.contexto()));
    }

    @Test
    void limitaFrecuenciaEnVentanasMovilesYNoSoloAlCambiarDeMinuto() {
        var eventos = List.of(DatosDeEdicion.zoom(50), DatosDeEdicion.zoom(55), DatosDeEdicion.zoom(60),
                DatosDeEdicion.zoom(65), DatosDeEdicion.zoom(70));
        var sutil = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), eventos);
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(sutil, DatosDeEdicion.contexto()))
                .withMessageContaining("frecuencia");
        var dinamico = DatosDeEdicion.plan(PerfilEdicionDinamica.DYNAMIC, new Tramo(0, 120), eventos);
        assertThatNoException().isThrownBy(() -> validador.validar(dinamico, DatosDeEdicion.contexto()));
    }

    @Test
    void limitaComposicionesYRespetaElLimiteExactoDeSesentaSegundos() {
        var eventos = List.of(DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 0, 2, 1, "person-1"),
                DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 59, 61, 1, "person-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), eventos), DatosDeEdicion.contexto()));
        var separados = List.of(eventos.get(0), DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 60, 62, 1, "person-1"));
        assertThatNoException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), separados), DatosDeEdicion.contexto()));
    }

    @Test
    void rechazaSolapamientoDesordenYRitmoExcesivoInclusoEnPerfilDinamico() {
        var conjuntos = List.of(List.of(DatosDeEdicion.zoom(10), DatosDeEdicion.zoom(11)),
                List.of(DatosDeEdicion.zoom(20), DatosDeEdicion.zoom(10)),
                List.of(DatosDeEdicion.zoom(10), DatosDeEdicion.zoom(12)),
                List.of(DatosDeEdicion.evento(TipoEfecto.CAMERA_FRAME, 10, 12, 1, "person-1"),
                        DatosDeEdicion.evento(TipoEfecto.LETTERBOX_WINDOW, 17, 19, 1, "person-1")));
        for (var eventos : conjuntos) {
            assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(
                    DatosDeEdicion.plan(PerfilEdicionDinamica.DYNAMIC, new Tramo(0, 120), eventos), DatosDeEdicion.contexto()));
        }
    }

    @Test
    void listasInmutablesYPlanVacioNoEjecutable() {
        var eventos = new ArrayList<>(List.of(DatosDeEdicion.zoom(10)));
        var plan = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), eventos);
        eventos.clear();
        assertThat(plan.events()).hasSize(1);
        assertThatThrownBy(() -> plan.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> validador.validar(
                DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of()), DatosDeEdicion.contexto()));
    }
}
