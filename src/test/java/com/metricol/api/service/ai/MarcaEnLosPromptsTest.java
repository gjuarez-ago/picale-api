package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.entity.BrandProfile;
import com.metricol.api.enums.Platform;
import com.metricol.api.service.ai.ArtDirector.Contexto;

/**
 * La marca llega a los prompts de la IA: qué vende, a quién le habla, cómo suena, qué evitar y, solo en los textos,
 * cómo contactarla.
 */
class MarcaEnLosPromptsTest {

    private static final MarcaDelNegocio MARCA = MarcaDelNegocio.de(new BrandProfile(
            "Tacos al pastor y aguas frescas", "Familias y oficinistas", List.of("CERCANO", "DIVERTIDO", "NO_EXISTE"),
            "No hablar de precios", "+529991234567", "https://tacoselguero.com", "Calle 60 #123, Centro"));

    // ------------------------------------------------------------------ MarcaDelNegocio

    @Test
    @DisplayName("traduce los tonos a frases para la IA, en español y en inglés, y descarta los que no existen")
    void tonos() {
        assertThat(MARCA.personalidadEs()).isEqualTo("cercano y amable, divertido y con humor");
        assertThat(MARCA.personalidadEn()).isEqualTo("warm and friendly, playful and funny");
        assertThat(MarcaDelNegocio.VACIA.personalidadEs()).isEmpty();
    }

    @Test
    @DisplayName("nunca pasan de tres tonos, aunque el perfil guardado traiga más")
    void maximoTres() {
        MarcaDelNegocio m = MarcaDelNegocio.de(new BrandProfile(null, null,
                List.of("CERCANO", "DIVERTIDO", "ELEGANTE", "DIRECTO"), null, null, null, null));
        assertThat(m.tono()).hasSize(3);
    }

    @Test
    @DisplayName("el contacto se arma solo con lo que hay")
    void contacto() {
        assertThat(MARCA.contactoEs()).isEqualTo("WhatsApp +529991234567, sitio web https://tacoselguero.com, direccion Calle 60 #123, Centro");
        assertThat(MarcaDelNegocio.de(new BrandProfile(null, null, List.of(), null, "+529991234567", null, null)).contactoEs())
                .isEqualTo("WhatsApp +529991234567");
        assertThat(MarcaDelNegocio.VACIA.hayContacto()).isFalse();
        assertThat(MarcaDelNegocio.de(null)).isSameAs(MarcaDelNegocio.VACIA);
    }

    // ------------------------------------------------------------------ Redactor (texto)

    @Test
    @DisplayName("el Redactor recibe la marca, incluido el contacto con su límite de uso")
    void redactor() {
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.completeJson(any(), anyString(), anyString()))
                .thenReturn("{\"guion\": \"x\", \"textos\": {\"INSTAGRAM\": \"hola\"}}");
        Redactor redactor = new Redactor(client);

        redactor.redactar("anuncia el 2x1", List.of(), List.of(Platform.INSTAGRAM),
                new Redactor.Negocio("Tacos El Güero", "Restaurante", "Mérida", "Taquería", null, MARCA));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeJson(any(), anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("Que vende o que destaca: Tacos al pastor y aguas frescas")
                .contains("A quien le habla: Familias y oficinistas")
                .contains("Como suena su marca (escribe asi): cercano y amable, divertido y con humor")
                .contains("Nunca digas ni hagas esto: No hablar de precios")
                .contains("WhatsApp +529991234567")
                .contains("solo si el texto invita a escribir o visitar");
    }

    @Test
    @DisplayName("sin perfil de marca el prompt del Redactor queda como antes: ni una línea de más")
    void redactorSinMarca() {
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.completeJson(any(), anyString(), anyString()))
                .thenReturn("{\"guion\": \"x\", \"textos\": {\"INSTAGRAM\": \"hola\"}}");

        new Redactor(client).redactar("algo", List.of(), List.of(Platform.INSTAGRAM),
                new Redactor.Negocio("Tacos", "Restaurante", "Mérida", "Taquería", null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeJson(any(), anyString(), prompt.capture());
        assertThat(prompt.getValue()).doesNotContain("Que vende").doesNotContain("Como suena").doesNotContain("contactarlo");
    }

    // ------------------------------------------------------------------ Director de arte

    @Test
    @DisplayName("el director de arte recibe la marca y se le prohíbe dibujar el contacto en la imagen")
    void director() {
        Contexto c = new Contexto("Tacos", "Restaurante", "Mérida", "Taquería", null, "Anunciar el 2x1", "Vender",
                "Cercano", "Minimalista", "Escríbenos", List.of(), List.of(), "post", List.of("https://cdn/x.jpg"),
                List.of("INSTAGRAM"), MARCA);

        String pedido = ArtDirector.pedidoDelUsuario(c);

        assertThat(pedido)
                .contains("What the business sells or highlights: Tacos al pastor y aguas frescas")
                .contains("Who it speaks to: Familias y oficinistas")
                .contains("Brand personality (write and compose in this voice): warm and friendly, playful and funny")
                .contains("Never say or do this: No hablar de precios")
                .contains("Contact details (use them ONLY inside the captions")
                .contains("NEVER draw them as text in the image")
                .contains("WhatsApp +529991234567");
    }

    @Test
    @DisplayName("el director sin marca no recibe ninguna línea de marca")
    void directorSinMarca() {
        Contexto c = new Contexto("Tacos", null, null, null, null, "Anunciar el 2x1", null, null, null, null, List.of(),
                List.of(), "post", List.of(), List.of());

        assertThat(ArtDirector.pedidoDelUsuario(c)).doesNotContain("sells or highlights").doesNotContain("Brand personality")
                .doesNotContain("Contact details");
    }
}
