package com.metricol.api.service.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.models.request.CampaignImageRequest;

/**
 * La bisagra entre hablar y crear.
 *
 * <p>Aquí es donde se nota si la conversación entendió mal, y es lo último
 * antes de gastar créditos: una ficha mal traducida son 5 créditos y una
 * imagen que no era.
 */
class PeticionDesdeFichaTest {

    private static FichaDeImagen ficha(String queSeAnuncia, String formato) {
        return FichaDeImagen.vacia().con(new FichaDeImagen(
                queSeAnuncia, formato, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("lo mínimo ya se puede crear")
    void loMinimo() {
        CampaignImageRequest p = PeticionDesdeFicha.armar(ficha("2x1 en tacos al pastor", "PUBLICACION"));

        assertThat(p.brief()).isEqualTo("2x1 en tacos al pastor");
        assertThat(p.format().code()).isEqualTo("post");
        assertThat(p.networks()).isNull();
        assertThat(p.resourceUrls()).isNull();
    }

    @Test
    @DisplayName("sin lo imprescindible no se crea nada: no se gastan créditos a medias")
    void sinLoImprescindibleNoCrea() {
        assertThatThrownBy(() -> PeticionDesdeFicha.armar(FichaDeImagen.vacia()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falta");

        assertThatThrownBy(() -> PeticionDesdeFicha.armar(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cada formato llega con el nombre que el generador entiende")
    void formatos() {
        assertThat(PeticionDesdeFicha.formato("PUBLICACION")).isEqualTo("post");
        assertThat(PeticionDesdeFicha.formato("HISTORIA")).isEqualTo("story");
        assertThat(PeticionDesdeFicha.formato("CARRUSEL")).isEqualTo("carousel");
        // Lo raro cae en el más común en vez de tirar la conversación entera.
        assertThat(PeticionDesdeFicha.formato("lo que sea")).isEqualTo("post");
        assertThat(PeticionDesdeFicha.formato(null)).isEqualTo("post");
    }

    @Test
    @DisplayName("el texto que va DENTRO de la imagen viaja entrecomillado y aparte")
    void elTextoDeLaImagen() {
        FichaDeImagen f = FichaDeImagen.vacia().con(new FichaDeImagen(
                "2x1 en tacos", "PUBLICACION", null, null, null, "2x1", "viernes", null, null));

        String brief = PeticionDesdeFicha.brief(f);

        assertThat(brief).contains("tal cual: \"2x1\"");
        assertThat(brief).contains("Cuándo: viernes");
        // Una línea por cosa: un párrafo corrido se malinterpreta.
        assertThat(brief.lines().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("las fotos propias y las redes viajan tal cual")
    void fotosYRedes() {
        FichaDeImagen f = FichaDeImagen.vacia()
                .con(new FichaDeImagen("tacos", "CARRUSEL", List.of("INSTAGRAM", "FACEBOOK"),
                        null, null, null, null, null, null))
                .conFotos(List.of("https://cdn/1.jpg", "https://cdn/2.jpg"));

        CampaignImageRequest p = PeticionDesdeFicha.armar(f);

        assertThat(p.resourceUrls()).containsExactly("https://cdn/1.jpg", "https://cdn/2.jpg");
        assertThat(p.networks()).containsExactly("INSTAGRAM", "FACEBOOK");
        assertThat(p.format().code()).isEqualTo("carousel");
    }

    @Test
    @DisplayName("el tono y los colores NO se mandan: los pone el perfil de la marca")
    void laMarcaManda() {
        CampaignImageRequest p = PeticionDesdeFicha.armar(ficha("tacos", "PUBLICACION"));

        assertThat(p.tone()).isNull();
        assertThat(p.visualStyle()).isNull();
        assertThat(p.brand()).isNull();
    }

    @Test
    @DisplayName("si ya eligió una versión, se afina esa y no se empieza de cero")
    void afinaLaElegida() {
        FichaDeImagen f = ficha("tacos", "PUBLICACION").conPieza("https://cdn/elegida.jpg");

        assertThat(PeticionDesdeFicha.brief(f)).contains("no empieces de cero");
    }

    @Test
    @DisplayName("un encargo larguísimo se recorta en vez de que lo rechace el servidor")
    void seRecorta() {
        FichaDeImagen f = FichaDeImagen.vacia().con(new FichaDeImagen(
                "a".repeat(3000), "PUBLICACION", null, null,
                "b".repeat(300), null, null, null, null));

        CampaignImageRequest p = PeticionDesdeFicha.armar(f);

        assertThat(p.brief()).hasSize(PeticionDesdeFicha.MAX_BRIEF);
        assertThat(p.objective()).hasSize(PeticionDesdeFicha.MAX_OBJETIVO);
    }
}
