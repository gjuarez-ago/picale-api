package com.metricol.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricol.api.config.StripeProperties;
import com.metricol.api.service.billing.StripeEventProcessor;
import com.metricol.api.service.billing.StripeEventProcessor.Resultado;
import com.metricol.api.service.billing.StripeWebhookVerifier;

/** El punto de entrada de Stripe: sin firma válida no pasa nada, y lo que contesta decide si Stripe reintenta. */
class StripeWebhookControllerTest {

    private static final String SECRETO = "whsec_prueba";
    private static final String CUERPO = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";

    private StripeProperties props;
    private StripeEventProcessor procesador;
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        props = new StripeProperties();
        props.setSecretKey("sk_test_x");
        props.setWebhookSecret(SECRETO);
        procesador = mock(StripeEventProcessor.class);
        mvc = MockMvcBuilders.standaloneSetup(new StripeWebhookController(props, procesador)).build();
    }

    private static String firma(String cuerpo, long marca, String secreto) {
        byte[] hmac = StripeWebhookVerifier.firmar(marca, cuerpo.getBytes(StandardCharsets.UTF_8), secreto);
        return "t=" + marca + ",v1=" + HexFormat.of().formatHex(hmac);
    }

    private org.springframework.test.web.servlet.ResultActions enviar(String cuerpo, String encabezado) throws Exception {
        var peticion = post("/api/v1/billing/webhook").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        if (encabezado != null) {
            peticion = peticion.header("Stripe-Signature", encabezado);
        }
        return mvc.perform(peticion);
    }

    @Test
    @DisplayName("un aviso bien firmado se procesa y se contesta 200")
    void firmado() throws Exception {
        when(procesador.procesar(any(JsonNode.class))).thenReturn(Resultado.PROCESADO);

        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), SECRETO)).andExpect(status().isOk());

        verify(procesador).procesar(any(JsonNode.class));
    }

    @Test
    @DisplayName("sin firma no se procesa nada")
    void sinFirma() throws Exception {
        enviar(CUERPO, null).andExpect(status().isBadRequest());
        verify(procesador, never()).procesar(any());
    }

    @Test
    @DisplayName("con la firma de otro secreto no se procesa nada")
    void firmaFalsa() throws Exception {
        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), "whsec_de_un_atacante"))
                .andExpect(status().isBadRequest());
        verify(procesador, never()).procesar(any());
    }

    @Test
    @DisplayName("un aviso firmado pero con el cuerpo cambiado se rechaza")
    void cuerpoCambiado() throws Exception {
        String firmado = firma(CUERPO, Instant.now().getEpochSecond(), SECRETO);

        enviar(CUERPO.replace("invoice.paid", "invoice.free"), firmado).andExpect(status().isBadRequest());
        verify(procesador, never()).procesar(any());
    }

    @Test
    @DisplayName("un aviso viejo, aunque bien firmado, se rechaza")
    void viejo() throws Exception {
        long haceUnDia = Instant.now().getEpochSecond() - 86_400;

        enviar(CUERPO, firma(CUERPO, haceUnDia, SECRETO)).andExpect(status().isBadRequest());
        verify(procesador, never()).procesar(any());
    }

    @Test
    @DisplayName("sin cobros configurados el webhook contesta 503 y no procesa nada")
    void sinConfigurar() throws Exception {
        props.setWebhookSecret("");

        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), SECRETO))
                .andExpect(status().isServiceUnavailable());
        verify(procesador, never()).procesar(any());
    }

    @Test
    @DisplayName("si falta contexto contesta 409 para que Stripe lo reintente; un fallo nuestro, 500")
    void reintentos() throws Exception {
        when(procesador.procesar(any(JsonNode.class))).thenReturn(Resultado.REINTENTAR);
        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), SECRETO)).andExpect(status().isConflict());

        when(procesador.procesar(any(JsonNode.class))).thenThrow(new IllegalStateException("se cayó la base"));
        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), SECRETO)).andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("un aviso ignorado también se contesta 200: no se quiere que Stripe lo mande otra vez")
    void ignorado() throws Exception {
        when(procesador.procesar(any(JsonNode.class))).thenReturn(Resultado.IGNORADO);

        enviar(CUERPO, firma(CUERPO, Instant.now().getEpochSecond(), SECRETO)).andExpect(status().isOk());
    }
}
