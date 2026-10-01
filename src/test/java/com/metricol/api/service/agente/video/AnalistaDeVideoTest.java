package com.metricol.api.service.agente.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.agente.RevisorDeMarca.Veredicto;

/** Cómo se lee lo que contesta la IA al mirar un video. */
class AnalistaDeVideoTest {

    private final AnalistaDeVideo analista = new AnalistaDeVideo(null, null, null);
    private static final List<Double> VISTOS = List.of(15.0, 69.0, 123.0, 177.0, 231.0, 285.0);

    @Test
    @DisplayName("seis momentos repartidos del 5 % al 95 %")
    void momentos() {
        List<Double> m = AnalistaDeVideo.momentos(300);
        assertThat(m).hasSize(6);
        for (int i = 0; i < 6; i++) {
            assertThat(m.get(i)).isCloseTo(VISTOS.get(i), org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    @DisplayName("la portada y las tomas llegan por número de cuadro y se convierten a su segundo")
    void cuadrosASegundos() throws Exception {
        var a = analista.interpretar("{\"veredicto\":\"VA\",\"tipo\":\"recorrido\",\"calidad\":4,"
                + "\"portada\":3,\"tomas\":[{\"cuadro\":2,\"nota\":5,\"que\":\"la vista\"},{\"cuadro\":9,\"nota\":1}],"
                + "\"tramo\":{\"inicio\":40,\"fin\":125}}", VISTOS, 300, "hola", true);
        assertThat(a.veredicto()).isEqualTo(Veredicto.VA);
        assertThat(a.tipo()).isEqualTo("RECORRIDO");
        assertThat(a.portadaSegundo()).isEqualTo(123.0);
        // La toma del cuadro 9 no existe: se ignora.
        assertThat(a.tomas()).extracting(AnalisisDeVideo.Toma::segundo).containsExactly(69.0);
        assertThat(a.tramoInicio()).isEqualTo(40);
        assertThat(a.tramoFin()).isEqualTo(125);
        assertThat(a.hayVoz()).isTrue();
    }

    @Test
    @DisplayName("un tramo de más de 90 s se acorta a 90, y uno al revés se rehace desde su inicio")
    void tramoDentroDeLoPermitido() throws Exception {
        var largo = analista.interpretar("{\"veredicto\":\"VA\",\"tramo\":{\"inicio\":10,\"fin\":250}}",
                VISTOS, 300, "", true);
        assertThat(largo.tramoFin() - largo.tramoInicio()).isEqualTo(90);
        var alReves = analista.interpretar("{\"veredicto\":\"VA\",\"tramo\":{\"inicio\":100,\"fin\":20}}",
                VISTOS, 300, "", true);
        assertThat(alReves.tramoInicio()).isEqualTo(100);
        assertThat(alReves.tramoFin()).isEqualTo(190);
    }

    @Test
    @DisplayName("con la marca incompleta no descarta: a observación")
    void sinMarcaNoDescarta() throws Exception {
        assertThat(analista.interpretar("{\"veredicto\":\"DESCARTADA\"}", VISTOS, 300, "", false).veredicto())
                .isEqualTo(Veredicto.OBSERVACION);
    }
}
