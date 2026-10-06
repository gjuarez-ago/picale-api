package com.metricol.api.service.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Lo que responde upload-post por las métricas de una publicación (visto en producción el 5 oct 2026). */
class LecturaDeMetricasTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String s) throws Exception {
        return new ObjectMapper().readValue(s, Map.class);
    }

    @Test
    @DisplayName("Facebook con ceros: se leen como ceros, sin aviso")
    void facebook() throws Exception {
        Map<String, Object> r = json("""
                {"success":true,"platforms":{"facebook":{"success":true,
                 "post_metrics":{"reactions":0,"likes":0,"comments":0,"shares":0,"reach":0,"impressions":0}}}}""");
        LecturaDeMetricas.Metricas m = LecturaDeMetricas.leer(r, "facebook");
        assertThat(m.vistas()).isZero();
        assertThat(m.alcance()).isZero();
        assertThat(LecturaDeMetricas.aviso(r, "facebook")).isNull();
    }

    @Test
    @DisplayName("LinkedIn de perfil personal: sin números y con el porqué para la persona")
    void linkedinPersonal() throws Exception {
        Map<String, Object> r = json("""
                {"success":true,"platforms":{"linkedin":{"success":true,
                 "post_metrics_error":"LinkedIn post metrics are only available for posts published to a LinkedIn Page. Personal profile post analytics are not available through the LinkedIn API for this connection."}}}""");
        assertThat(LecturaDeMetricas.leer(r, "linkedin").vacias()).isTrue();
        assertThat(LecturaDeMetricas.aviso(r, "linkedin")).contains("LinkedIn solo da resultados").contains("Página");
    }
}
