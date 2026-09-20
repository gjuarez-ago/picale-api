package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.BrandProfile;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.campaign.CampaignImageService.Negocio;

/**
 * La marca entra al prompt de la IMAGEN: qué vende, público, personalidad y qué evitar. El contacto NO: los modelos de
 * imagen deforman los números, y un teléfono mal escrito en una pieza publicada es peor que ninguno.
 */
class MarcaEnLaImagenTest {

    private static final MarcaDelNegocio MARCA = MarcaDelNegocio.de(new BrandProfile(
            "Tacos al pastor", "Familias", List.of("CERCANO"), "No hablar de precios", "+529991234567",
            "https://tacoselguero.com", "Calle 60 #123"));

    private static CampaignImageRequest pedido() {
        return new CampaignImageRequest(2, new CampaignImageRequest.Format("post", "4:5", "1024x1536"), List.of(), null,
                "Anuncia el 2x1", "Vender", List.of("Minimalista"), "Cercano", "Escríbenos", Boolean.FALSE, null);
    }

    private static String prompt(MarcaDelNegocio marca) {
        Negocio negocio = new Negocio(UUID.randomUUID(), "Tacos El Güero", "Restaurante", "Mérida", "Taquería", null, marca);
        return CampaignImageService.armarPrompt(negocio, pedido(), Lienzo.CUATRO_QUINTOS, 0, 1, 0, null);
    }

    @Test
    @DisplayName("la imagen recibe qué vende, el público, la personalidad y qué evitar")
    void laMarcaEntra() {
        assertThat(prompt(MARCA))
                .contains("What it sells or highlights: Tacos al pastor")
                .contains("Audience: Familias")
                .contains("Brand personality: warm and friendly")
                .contains("Avoid: No hablar de precios");
    }

    @Test
    @DisplayName("el teléfono, el sitio web y la dirección NUNCA llegan al prompt de la imagen")
    void elContactoNoEntra() {
        assertThat(prompt(MARCA))
                .doesNotContain("529991234567")
                .doesNotContain("tacoselguero.com")
                .doesNotContain("Calle 60")
                .doesNotContainIgnoringCase("whatsapp");
    }

    @Test
    @DisplayName("sin marca el prompt queda como antes")
    void sinMarca() {
        assertThat(prompt(MarcaDelNegocio.VACIA))
                .doesNotContain("What it sells").doesNotContain("Audience:").doesNotContain("Brand personality")
                .doesNotContain("Avoid:");
    }
}
