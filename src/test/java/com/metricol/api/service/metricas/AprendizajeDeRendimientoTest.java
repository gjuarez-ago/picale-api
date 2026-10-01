package com.metricol.api.service.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.metricas.AprendizajeDeRendimiento.Muestra;

/** Lo que una cuenta aprende de sus publicaciones medidas. */
class AprendizajeDeRendimientoTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    private static Muestra a(int dia, int hora, String texto, double puntaje) {
        return new Muestra(DIA.plusDays(dia).atTime(hora, 0), texto, puntaje);
    }

    @Test
    @DisplayName("con menos de 8 publicaciones medidas no concluye nada")
    void pocosDatos() {
        List<Muestra> siete = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            siete.add(a(i, 19, "#tacos", 100));
        }
        AprendizajeDeRendimiento.Aprendido r = AprendizajeDeRendimiento.de(siete);
        assertThat(r.suficiente()).isFalse();
        assertThat(r.horas()).isEmpty();
        assertThat(r.hashtagsBuenos()).isEmpty();
    }

    @Test
    @DisplayName("las horas que rinden por encima de lo normal de la cuenta salen primero")
    void mejoresHoras() {
        List<Muestra> m = new ArrayList<>();
        // En la noche le va el triple que en la mañana.
        for (int i = 0; i < 5; i++) {
            m.add(a(i, 19, "", 90));
            m.add(a(i, 20, "", 80));
            m.add(a(i, 9, "", 25));
            m.add(a(i, 11, "", 30));
        }
        AprendizajeDeRendimiento.Aprendido r = AprendizajeDeRendimiento.de(m);
        assertThat(r.suficiente()).isTrue();
        assertThat(r.horas()).isNotEmpty();
        assertThat(r.horas().get(0)).isIn(LocalTime.of(19, 0), LocalTime.of(20, 0));
        assertThat(r.horas()).doesNotContain(LocalTime.of(9, 0), LocalTime.of(11, 0));
    }

    @Test
    @DisplayName("una hora con una sola publicación no basta para llamarla buena")
    void unaSolaNoBasta() {
        List<Muestra> m = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            m.add(a(i, 12, "", 40));
        }
        m.add(a(10, 7, "", 500)); // una vez a las 7 le fue de maravilla
        assertThat(AprendizajeDeRendimiento.de(m).horas()).doesNotContain(LocalTime.of(7, 0));
    }

    @Test
    @DisplayName("los hashtags se juzgan contra la mediana de la cuenta, con al menos 3 usos")
    void hashtags() {
        List<Muestra> m = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            m.add(a(i, 12, "Pastor al 2x1 #TacosMerida #comida", 200));
            m.add(a(i, 13, "Hoy abrimos #lunes #comida", 30));
            m.add(a(i, 14, "Normal", 60));
        }
        m.add(a(5, 12, "#raro", 900)); // un solo uso: no se juzga
        AprendizajeDeRendimiento.Aprendido r = AprendizajeDeRendimiento.de(m);
        assertThat(r.hashtagsBuenos()).containsExactly("#tacosmerida");
        assertThat(r.hashtagsFlojos()).contains("#lunes");
        assertThat(r.hashtagsBuenos()).doesNotContain("#raro", "#comida");
    }

    @Test
    @DisplayName("una cuenta sin interacción no aprende nada, en vez de dividir entre cero")
    void todoEnCero() {
        List<Muestra> m = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            m.add(a(i, 18, "#x", 0));
        }
        AprendizajeDeRendimiento.Aprendido r = AprendizajeDeRendimiento.de(m);
        assertThat(r.horas()).isEmpty();
        assertThat(r.hashtagsBuenos()).isEmpty();
    }

    @Test
    @DisplayName("lo que se le pasa a quien escribe: los buenos para usar y los flojos para evitar")
    void paraElRedactor() {
        AprendizajeDeRendimiento.Aprendido a = new AprendizajeDeRendimiento.Aprendido(12, List.of(),
                List.of("#tacosmerida"), List.of("#lunes"));
        assertThat(LoQueFunciona.paraElRedactor(a)).contains("#tacosmerida").contains("evitalos").contains("#lunes");
        assertThat(LoQueFunciona.paraElRedactor(AprendizajeDeRendimiento.Aprendido.NADA)).isNull();
    }

    @Test
    @DisplayName("la lectura de métricas acepta los nombres de cada red y deja nulo lo que no viene")
    void lectura() {
        Map<String, Object> instagram = Map.of("success", true, "platforms", Map.of("instagram", Map.of(
                "success", true, "post_metrics", Map.of("likes", 340, "comments", 12, "views", 8500, "reach", 6200,
                        "saves", 45, "shares", 28))));
        LecturaDeMetricas.Metricas m = LecturaDeMetricas.leer(instagram, "INSTAGRAM");
        assertThat(m.meGusta()).isEqualTo(340);
        assertThat(m.vistas()).isEqualTo(8500);
        assertThat(m.guardados()).isEqualTo(45);

        // TikTok llama favorites a los guardados.
        Map<String, Object> tiktok = Map.of("platforms", Map.of("tiktok", Map.of("post_metrics",
                Map.of("views", 1200, "favorites", 9))));
        LecturaDeMetricas.Metricas t = LecturaDeMetricas.leer(tiktok, "tiktok");
        assertThat(t.guardados()).isEqualTo(9);
        assertThat(t.meGusta()).isNull();

        assertThat(LecturaDeMetricas.leer(Map.of("success", false), "instagram").vacias()).isTrue();
        assertThat(LecturaDeMetricas.leer(null, "instagram").vacias()).isTrue();
    }

    @Test
    @DisplayName("el puntaje pesa más comentar, compartir y guardar que un me gusta")
    void puntaje() {
        com.metricol.api.entity.PostTarget soloLikes = com.metricol.api.entity.PostTarget.builder().meGusta(30L)
                .metricasEn(LocalDateTime.now()).build();
        com.metricol.api.entity.PostTarget conversacion = com.metricol.api.entity.PostTarget.builder().meGusta(5L)
                .comentarios(5L).compartidos(4L).guardados(2L).metricasEn(LocalDateTime.now()).build();
        assertThat(conversacion.puntaje()).isGreaterThan(soloLikes.puntaje());
        assertThat(com.metricol.api.entity.PostTarget.builder().metricasEn(LocalDateTime.now()).build().puntaje())
                .as("medida sin ningún número").isNull();
    }
}
