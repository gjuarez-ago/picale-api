package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InformeDeHablanteTest {
    @Test
    void admiteEvidenciaCompletaDelMismoOriginal() {
        assertThatNoException().isThrownBy(() -> DatosDeInformeHablante.valido().validarContra("a".repeat(64), 4));
    }

    @Test
    void rechazaOtroOriginalYDuracionDistinta() {
        assertThatIllegalArgumentException().isThrownBy(() -> DatosDeInformeHablante.valido().validarContra("f".repeat(64), 4));
        assertThatIllegalArgumentException().isThrownBy(() -> DatosDeInformeHablante.valido().validarContra("a".repeat(64), 8));
    }

    static Stream<InformeDeHablante.Muestra> muestrasInvalidas() {
        return Stream.of(
                new InformeDeHablante.Muestra(0, 2, "person-1", 1, List.of(.2, .2, .4, .4), .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-2", 1, List.of(.2, .2, .4, .4), .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 2, List.of(.2, .2, .4, .4), .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, null, .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, List.of(.9, .2, .4, .4), .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, List.of(.2, .2, .4, .4), .1, .99, false, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, List.of(.2, .2, .4, .4), .1, .99, true, .5, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, List.of(.2, .2, .4, .4), .1, .99, true, .99, false),
                new InformeDeHablante.Muestra(Double.NaN, 1, "person-1", 1, List.of(.2, .2, .4, .4), .1, .99, true, .99, true),
                new InformeDeHablante.Muestra(0, 1, "person-1", 1, List.of(.2, .2, .4, .4), .1, 2, true, .99, true));
    }

    @ParameterizedTest
    @MethodSource("muestrasInvalidas")
    void unaMuestraContradictoriaInvalidaElIntervalo(InformeDeHablante.Muestra muestra) {
        var rows = new ArrayList<>(DatosDeInformeHablante.valido().samples());
        rows.set(0, muestra);
        assertThatIllegalArgumentException().isThrownBy(() -> DatosDeInformeHablante.conMuestras(rows)
                .validarContra("a".repeat(64), 4));
    }

    @Test
    void huecoDeMuestreoNoSePresentaComoEvidenciaContinua() {
        var rows = new ArrayList<>(DatosDeInformeHablante.valido().samples());
        rows.remove(50);
        assertThatIllegalArgumentException().isThrownBy(() -> DatosDeInformeHablante.conMuestras(rows)
                .validarContra("a".repeat(64), 4));
    }

    @Test
    void sinIntervalosEsUnResultadoNoElegibleValido() {
        var v = DatosDeInformeHablante.valido();
        var no = new InformeDeHablante(v.schemaVersion(), v.timeBase(), v.detectorVersion(), v.evidenceRevision(),
                v.sourceSha256(), v.durationSeconds(), v.normalization(), false, List.of(), v.samples(),
                v.modelRevisions(), v.elapsedSeconds());
        assertThatNoException().isThrownBy(() -> no.validarContra("a".repeat(64), 4));
        var incoherente = new InformeDeHablante(v.schemaVersion(), v.timeBase(), v.detectorVersion(), v.evidenceRevision(),
                v.sourceSha256(), v.durationSeconds(), v.normalization(), true, List.of(), v.samples(),
                v.modelRevisions(), v.elapsedSeconds());
        assertThatIllegalArgumentException().isThrownBy(() -> incoherente.validarContra("a".repeat(64), 4));
    }
}
