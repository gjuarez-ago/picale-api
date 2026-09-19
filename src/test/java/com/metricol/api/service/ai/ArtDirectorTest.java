package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.service.ai.ArtDirector.Brief;
import com.metricol.api.service.ai.ArtDirector.Contexto;

class ArtDirectorTest {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private MockRestServiceServer servidor;
    private AiUsageRecorder usos;
    private ArtDirector director;
    private OpenAiProperties props;

    @BeforeEach
    void preparar() {
        props = new OpenAiProperties();
        props.setApiKey("sk-de-prueba");
        props.setDirectorModel("gpt-5.5");
        props.setDirectorReasoningEffort("low");

        usos = mock(AiUsageRecorder.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        servidor = MockRestServiceServer.bindTo(builder).build();
        director = new ArtDirector(props, usos, builder.build());
    }

    private static Contexto contexto(List<String> fotos) {
        return new Contexto("CMRG", "Mantenimiento industrial", "Manzanillo, Colima", "Obra y mantenimiento",
                "VENDER", "Anunciar nuestro servicio de montaje", "Vender", "Profesional", "Minimalista",
                "Escríbenos por WhatsApp", List.of("#0B2A5B", "#1FA34A"), List.of("Llegamos a tiempo."), "post", fotos);
    }

    private static String respuesta(String plan) {
        String escapado = plan.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"model\":\"gpt-5.5-2026\",\"choices\":[{\"message\":{\"content\":\"" + escapado + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":250}}";
    }

    private static final String PLAN = "{\"layout\":\"photo_top_title\",\"hero_photo\":2,"
            + "\"scene\":\"Warm afternoon light, natural grade.\",\"headline\":\"Montaje seguro y a tiempo\","
            + "\"subtitle\":\"Manzanillo, Colima\",\"cta\":\"Cotiza hoy\","
            + "\"caption\":\"Levantamos y nivelamos.\\nEscríbenos por WhatsApp.\"}";

    @Test
    @DisplayName("manda el modelo, las fotos y la respuesta cerrada, y devuelve el plan")
    void plan() {
        servidor.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer sk-de-prueba"))
                .andExpect(content().string(containsString("\"model\":\"gpt-5.5\"")))
                .andExpect(content().string(containsString("\"response_format\":{\"type\":\"json_object\"}")))
                .andExpect(content().string(containsString("\"reasoning_effort\":\"low\"")))
                .andExpect(content().string(containsString("https://cdn.test/a.jpg")))
                .andExpect(content().string(containsString("https://cdn.test/b.jpg")))
                .andExpect(content().string(containsString("Anunciar nuestro servicio de montaje")))
                // Los modelos que razonan rechazan estos dos: no deben ir.
                .andExpect(content().string(not(containsString("temperature"))))
                .andExpect(content().string(not(containsString("max_tokens"))))
                .andRespond(withSuccess(respuesta(PLAN), MediaType.APPLICATION_JSON));

        Optional<Brief> resultado = director.dirigir(
                contexto(List.of("https://cdn.test/a.jpg", "https://cdn.test/b.jpg")));

        assertThat(resultado).isPresent();
        Brief brief = resultado.get();
        assertThat(brief.layout()).isEqualTo("photo_top_title");
        assertThat(brief.fotoProtagonista()).isEqualTo(2);
        assertThat(brief.titular()).isEqualTo("Montaje seguro y a tiempo");
        assertThat(brief.subtitulo()).isEqualTo("Manzanillo, Colima");
        assertThat(brief.cta()).isEqualTo("Cotiza hoy");
        assertThat(brief.caption()).isEqualTo("Levantamos y nivelamos.\nEscríbenos por WhatsApp.");
        assertThat(brief.escena()).contains("Warm afternoon light");
        verify(usos).registrarDirector("gpt-5.5-2026", 900, 250);
        servidor.verify();
    }

    @Test
    @DisplayName("el pedido lleva lo que sabe el negocio: perfil, idea, colores y captions anteriores")
    void pedidoCompleto() {
        String pedido = ArtDirector.pedidoDelUsuario(contexto(List.of("https://cdn.test/a.jpg")));

        assertThat(pedido)
                .contains("CMRG")
                .contains("Mantenimiento industrial")
                .contains("Manzanillo, Colima")
                .contains("Anunciar nuestro servicio de montaje")
                .contains("Escríbenos por WhatsApp")
                .contains("#0B2A5B, #1FA34A")
                .contains("Llegamos a tiempo.")
                .contains("1 attached photo(s)");
    }

    @Test
    @DisplayName("una composición que no existe cae a la de siempre, y una foto fuera de rango a 'sin preferencia'")
    void valoresCerrados() {
        String plan = "{\"layout\":\"collage_loco\",\"hero_photo\":9,\"scene\":\"x\",\"headline\":\"Obra\","
                + "\"subtitle\":\"\",\"cta\":\"\",\"caption\":\"c\"}";
        servidor.expect(requestTo(URL)).andRespond(withSuccess(respuesta(plan), MediaType.APPLICATION_JSON));

        Brief brief = director.dirigir(contexto(List.of("https://cdn.test/a.jpg"))).orElseThrow();

        assertThat(brief.layout()).isEqualTo(ArtDirector.LAYOUT_POR_DEFECTO);
        assertThat(brief.fotoProtagonista()).isZero();
    }

    @Test
    @DisplayName("comillas, barras y saltos en el titular se limpian: acaba entre comillas en otro prompt")
    void limpiaTextos() {
        String plan = "{\"layout\":\"framed_photo\",\"hero_photo\":0,\"scene\":\"s\","
                + "\"headline\":\"Obra \\\"segura\\\"\\ny \\\\ rapida\",\"subtitle\":\"\",\"cta\":\"\",\"caption\":\"\"}";
        servidor.expect(requestTo(URL)).andRespond(withSuccess(respuesta(plan), MediaType.APPLICATION_JSON));

        Brief brief = director.dirigir(contexto(List.of())).orElseThrow();

        assertThat(brief.titular()).isEqualTo("Obra segura y rapida");
    }

    @Test
    @DisplayName("un plan sin titular no sirve: se sigue sin director")
    void sinTitular() {
        String plan = "{\"layout\":\"framed_photo\",\"hero_photo\":1,\"scene\":\"s\",\"headline\":\"  \","
                + "\"subtitle\":\"\",\"cta\":\"\",\"caption\":\"c\"}";
        servidor.expect(requestTo(URL)).andRespond(withSuccess(respuesta(plan), MediaType.APPLICATION_JSON));

        assertThat(director.dirigir(contexto(List.of("https://cdn.test/a.jpg")))).isEmpty();
    }

    @Test
    @DisplayName("una respuesta que no es JSON no rompe nada")
    void noEsJson() {
        servidor.expect(requestTo(URL)).andRespond(withSuccess(respuesta("hola, no soy un plan"), MediaType.APPLICATION_JSON));

        assertThat(director.dirigir(contexto(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("si el modelo no acepta reasoning_effort se reintenta sin él y no se vuelve a mandar")
    void modeloQueNoRazona() {
        servidor.expect(requestTo(URL))
                .andExpect(content().string(containsString("reasoning_effort")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Unrecognized request argument: reasoning_effort\"}}"));
        servidor.expect(requestTo(URL))
                .andExpect(content().string(not(containsString("reasoning_effort"))))
                .andRespond(withSuccess(respuesta(PLAN), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL))
                .andExpect(content().string(not(containsString("reasoning_effort"))))
                .andRespond(withSuccess(respuesta(PLAN), MediaType.APPLICATION_JSON));

        assertThat(director.dirigir(contexto(List.of("https://cdn.test/a.jpg")))).isPresent();
        assertThat(director.dirigir(contexto(List.of("https://cdn.test/a.jpg")))).isPresent();
        servidor.verify();
    }

    @Test
    @DisplayName("modelo no permitido o cualquier otro error: vacío, y la campaña sigue sin director")
    void falla() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Project does not have access to model gpt-5.5\"}}"));

        assertThat(director.dirigir(contexto(List.of("https://cdn.test/a.jpg")))).isEmpty();
        verify(usos, never()).registrarDirector(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("sin llave o sin modelo no está disponible y no llama a nadie")
    void noDisponible() {
        OpenAiProperties sinLlave = new OpenAiProperties();
        ArtDirector apagado = new ArtDirector(sinLlave, usos, RestClient.builder().build());

        assertThat(apagado.disponible()).isFalse();
        assertThat(apagado.dirigir(contexto(List.of()))).isEmpty();

        props.setDirectorModel("");
        assertThat(director.disponible()).isFalse();
    }

    @Test
    @DisplayName("limpio() deja una sola línea sin comillas y respeta el máximo")
    void limpio() {
        assertThat(ArtDirector.limpio("  Hola \"mundo\"\n\tcruel  ", 50)).isEqualTo("Hola mundo cruel");
        assertThat(ArtDirector.limpio("abcdefghij", 5)).isEqualTo("abcde");
        assertThat(ArtDirector.limpio(null, 5)).isEmpty();
    }
}
