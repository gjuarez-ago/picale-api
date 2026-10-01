package com.metricol.api.service.agente.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.PostFormat;
import com.metricol.api.service.agente.DecisorDelAgente.Intencion;
import com.metricol.api.service.agente.RevisorDeMarca.Veredicto;
import com.metricol.api.service.agente.video.DecisorDeVideo.Tratamiento;

/** Qué se hace con cada video ya analizado. */
class DecisorDeVideoTest {

    private static AnalisisDeVideo video(String tipo, int calidad, boolean arreglable, boolean efimero,
            double portada, double inicio, double fin, String voz) {
        return new AnalisisDeVideo(Veredicto.VA, "va", "Se ve el producto", "Presumir el producto", tipo, calidad,
                calidad <= 2 ? "movido" : "", arreglable, efimero, false, Intencion.VENDER, portada,
                List.of(new AnalisisDeVideo.Toma(portada, 5, "el producto de cerca")), inicio, fin, voz);
    }

    @Test
    @DisplayName("una demostración corta y buena va como Reel, con la portada en la mejor toma")
    void reelConPortada() {
        var d = DecisorDeVideo.decidir(video("DEMOSTRACION", 4, true, false, 12.4, 0, 40, "Este es el 2x1"),
                40, true, false);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.PUBLICAR);
        assertThat(d.formato()).isEqualTo(PostFormat.REEL);
        assertThat(d.portadaMs()).isEqualTo(12400);
        assertThat(d.explicacion()).contains("Lo vi completo y escuché lo que se dice: es una demostración de 40 s")
                .contains("De portada, el segundo 12");
    }

    @Test
    @DisplayName("un detrás de cámaras corto va de historia si hay dónde; si no, como Reel y lo dice")
    void historia() {
        var detras = video("DETRAS_DE_CAMARAS", 3, true, true, 5, 0, 25, "");
        assertThat(DecisorDeVideo.decidir(detras, 25, true, false).formato()).isEqualTo(PostFormat.STORY);
        var sinHistorias = DecisorDeVideo.decidir(detras, 25, false, false);
        assertThat(sinHistorias.formato()).isEqualTo(PostFormat.REEL);
        assertThat(sinHistorias.explicacion()).contains("no tienes redes que las publiquen");
    }

    @Test
    @DisplayName("más largo que un Reel y sin editor: a Observación con el tramo exacto para recortarlo")
    void largoSinEditor() {
        var d = DecisorDeVideo.decidir(video("RECORRIDO", 4, true, false, 70, 42, 125, ""), 300, true, false);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.OBSERVACION);
        assertThat(d.explicacion()).contains("Dura 5 min 00 s").contains("del 0:42 al 2:05");
    }

    @Test
    @DisplayName("más largo que un Reel con editor: se pide recortar el mejor tramo, y la portada cuenta desde ahí")
    void largoConEditor() {
        var d = DecisorDeVideo.decidir(video("RECORRIDO", 4, true, false, 70, 42, 125, ""), 300, true, true);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.RECORTAR);
        assertThat(d.inicio()).isEqualTo(42);
        assertThat(d.fin()).isEqualTo(125);
        assertThat(d.portadaMs()).isEqualTo(28000);
    }

    @Test
    @DisplayName("movido y sin arreglo: a Observación, mejor grabarlo otra vez")
    void movido() {
        var d = DecisorDeVideo.decidir(video("EVENTO", 1, false, false, 5, 0, 30, ""), 30, true, false);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.OBSERVACION);
        assertThat(d.explicacion()).contains("está movido").contains("grábalo otra vez");
    }

    @Test
    @DisplayName("una portada fuera del tramo se mueve al principio del tramo")
    void portadaFuera() {
        var d = DecisorDeVideo.decidir(video("PRODUCTO", 4, true, false, 200, 0, 30, ""), 30, true, false);
        assertThat(d.portadaMs()).isEqualTo(1000);
    }
}
