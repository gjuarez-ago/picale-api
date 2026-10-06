package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.Workspace;

/** El retrato de Juan no es su logo (6 oct 2026). */
class RevisorDeLogoTest {

    private final RevisorDeLogo revisor = new RevisorDeLogo(null, null);

    @Test
    @DisplayName("lee qué es la imagen y descarta lo que no reconoce")
    void tipo() throws Exception {
        assertThat(revisor.tipo("{\"tipo\": \"foto_persona\"}")).isEqualTo("FOTO_PERSONA");
        assertThat(revisor.tipo("```json\n{\"tipo\": \"LOGO\"}\n```")).isEqualTo("LOGO");
        assertThat(revisor.tipo("{\"tipo\": \"AVATAR\"}")).isNull();
        assertThat(revisor.tipo("nada")).isNull();
    }

    @Test
    @DisplayName("una foto ya revisada no se pega como logo; un logotipo sí; si cambia el archivo, se revisa otra vez")
    void usable() {
        Workspace w = Workspace.builder().name("Juan").logoUrl("https://cdn/juan.jpg").build();
        w.setId(UUID.randomUUID());
        w.setLogoRevisado("https://cdn/juan.jpg");
        w.setLogoEsFoto(true);
        assertThat(revisor.usable(w)).isNull();

        w.setLogoEsFoto(false);
        assertThat(revisor.usable(w)).isEqualTo("https://cdn/juan.jpg");

        assertThat(revisor.usable(Workspace.builder().name("Sin logo").build())).isNull();
    }
}
