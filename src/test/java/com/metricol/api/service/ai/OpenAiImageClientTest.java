package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.service.ai.OpenAiImageClient.Referencia;
import com.metricol.api.service.ai.OpenAiImageClient.Resultado;

class OpenAiImageClientTest {

    private static final String BASE = "https://api.openai.com/v1";

    private MockRestServiceServer servidor;
    private OpenAiImageClient cliente;

    @BeforeEach
    void preparar() {
        OpenAiProperties props = new OpenAiProperties();
        props.setApiKey("sk-de-prueba");
        props.setImageModel("gpt-image-1.5");

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new OpenAiImageClient(props, builder.build());
    }

    private static String imagenBase64() {
        return Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 });
    }

    private static String respuestaOk() {
        return "{\"data\":[{\"b64_json\":\"" + imagenBase64() + "\"}],"
                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":1056}}";
    }

    @Test
    @DisplayName("generar pide el modelo, el tamaño y la calidad, y devuelve la imagen y los tokens")
    void generar() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-de-prueba"))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-1.5\"")))
                .andExpect(content().string(containsString("\"size\":\"1024x1536\"")))
                .andExpect(content().string(containsString("\"quality\":\"medium\"")))
                .andExpect(content().string(containsString("\"n\":1")))
                .andRespond(withSuccess(respuestaOk(), MediaType.APPLICATION_JSON));

        Resultado resultado = cliente.generar("un taco", "1024x1536");

        assertThat(resultado.imagen()).containsExactly(1, 2, 3, 4);
        assertThat(resultado.tokensEntrada()).isEqualTo(50);
        assertThat(resultado.tokensSalida()).isEqualTo(1056);
        assertThat(resultado.modelo()).isEqualTo("gpt-image-1.5");
        servidor.verify();
    }

    @Test
    @DisplayName("un tamaño que gpt-image no acepta se cambia por el vertical")
    void tamanoInvalido() {
        assertThat(OpenAiImageClient.tamanoValido("800x800")).isEqualTo("1024x1536");
        assertThat(OpenAiImageClient.tamanoValido(null)).isEqualTo("1024x1536");
        assertThat(OpenAiImageClient.tamanoValido("1024x1024")).isEqualTo("1024x1024");
    }

    @Test
    @DisplayName("editar manda las fotos de referencia como multipart en image[]")
    void editar() {
        servidor.expect(requestTo(BASE + "/images/edits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-de-prueba"))
                .andExpect(header("Content-Type", containsString("multipart/form-data")))
                .andExpect(content().string(containsString("name=\"image[]\"")))
                .andExpect(content().string(containsString("filename=\"referencia.png\"")))
                .andExpect(content().string(containsString("name=\"prompt\"")))
                .andRespond(withSuccess(respuestaOk(), MediaType.APPLICATION_JSON));

        Resultado resultado = cliente.editar("mejora esto",
                List.of(new Referencia(new byte[] { 9, 9 }, "referencia.png", "image/png")), "1024x1536");

        assertThat(resultado.imagen()).hasSize(4);
        servidor.verify();
    }

    @Test
    @DisplayName("sin usage cuenta cero tokens, no revienta")
    void sinUsage() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + imagenBase64() + "\"}]}",
                        MediaType.APPLICATION_JSON));

        Resultado resultado = cliente.generar("x", null);

        assertThat(resultado.tokensEntrada()).isZero();
        assertThat(resultado.tokensSalida()).isZero();
    }

    @Test
    @DisplayName("una respuesta sin imagen es un error entendible")
    void sinImagen() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> cliente.generar("x", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no devolvió ninguna imagen");
    }

    @Test
    @DisplayName("contenido bloqueado por moderación: se le dice a la persona, que puede cambiarlo")
    void moderacion() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"moderation_blocked\",\"message\":\"safety system\"}}"));

        assertThatThrownBy(() -> cliente.generar("x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("políticas de contenido");
    }

    @Test
    @DisplayName("429: pide esperar un minuto")
    void limiteDeOpenAi() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"rate limit\"}}"));

        assertThatThrownBy(() -> cliente.generar("x", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("un minuto");
    }

    @Test
    @DisplayName("modelo no permitido o llave inválida: mensaje genérico, sin filtrar el detalle de OpenAI")
    void errorDeConfiguracionNoSeFiltra() {
        servidor.expect(requestTo(BASE + "/images/generations"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Project does not have access to model sk-secreto\"}}"));

        assertThatThrownBy(() -> cliente.generar("x", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No se pudo generar la imagen. Inténtalo de nuevo.");
    }

    @Test
    @DisplayName("sin OPENAI_API_KEY no está disponible y no llama a nadie")
    void sinLlave() {
        OpenAiImageClient sinLlave = new OpenAiImageClient(new OpenAiProperties());

        assertThat(sinLlave.disponible()).isFalse();
        assertThatThrownBy(() -> sinLlave.generar("x", null)).isInstanceOf(IllegalStateException.class);
    }
}
