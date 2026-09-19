package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La firma de los avisos de Stripe: lo único que separa un pago de verdad de
 * alguien que le manda "pagué" a la dirección del webhook.
 */
class StripeWebhookVerifierTest {

    private static final String SECRETO = "whsec_prueba_secreto";
    private static final byte[] CUERPO = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant AHORA = Instant.parse("2026-09-19T20:00:00Z");

    private static String encabezado(long marca, byte[] cuerpo, String secreto) {
        return "t=" + marca + ",v1=" + HexFormat.of().formatHex(StripeWebhookVerifier.firmar(marca, cuerpo, secreto));
    }

    private static boolean valido(String encabezado) {
        return StripeWebhookVerifier.valido(CUERPO, encabezado, SECRETO, 300, AHORA);
    }

    @Test
    @DisplayName("una firma correcta y reciente vale")
    void firmaCorrecta() {
        assertThat(valido(encabezado(AHORA.getEpochSecond(), CUERPO, SECRETO))).isTrue();
    }

    @Test
    @DisplayName("una firma hecha con otro secreto no vale")
    void otroSecreto() {
        assertThat(valido(encabezado(AHORA.getEpochSecond(), CUERPO, "whsec_de_otro"))).isFalse();
    }

    @Test
    @DisplayName("si el cuerpo cambió un solo carácter, la firma ya no vale")
    void cuerpoAlterado() {
        String firmado = encabezado(AHORA.getEpochSecond(), CUERPO, SECRETO);
        byte[] alterado = "{\"id\":\"evt_1\",\"type\":\"invoice.PAID\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(StripeWebhookVerifier.valido(alterado, firmado, SECRETO, 300, AHORA)).isFalse();
    }

    @Test
    @DisplayName("un aviso viejo no vale aunque su firma sea auténtica: no se puede reenviar días después")
    void avisoViejo() {
        long haceUnaHora = AHORA.getEpochSecond() - 3600;
        assertThat(valido(encabezado(haceUnaHora, CUERPO, SECRETO))).isFalse();
    }

    @Test
    @DisplayName("tampoco vale uno del futuro")
    void avisoDelFuturo() {
        long enUnaHora = AHORA.getEpochSecond() + 3600;
        assertThat(valido(encabezado(enUnaHora, CUERPO, SECRETO))).isFalse();
    }

    @Test
    @DisplayName("dentro de la tolerancia vale")
    void dentroDeLaTolerancia() {
        long haceUnMinuto = AHORA.getEpochSecond() - 60;
        assertThat(valido(encabezado(haceUnMinuto, CUERPO, SECRETO))).isTrue();
    }

    @Test
    @DisplayName("con varias firmas (rotación del secreto) basta con que una coincida")
    void variasFirmas() {
        long marca = AHORA.getEpochSecond();
        String buena = HexFormat.of().formatHex(StripeWebhookVerifier.firmar(marca, CUERPO, SECRETO));
        String vieja = HexFormat.of().formatHex(StripeWebhookVerifier.firmar(marca, CUERPO, "whsec_anterior"));

        assertThat(valido("t=" + marca + ",v1=" + vieja + ",v1=" + buena)).isTrue();
    }

    @Test
    @DisplayName("encabezados rotos, vacíos o sin firma no valen y no lanzan")
    void encabezadosRotos() {
        long marca = AHORA.getEpochSecond();
        assertThat(valido(null)).isFalse();
        assertThat(valido("")).isFalse();
        assertThat(valido("basura")).isFalse();
        assertThat(valido("t=abc,v1=00")).isFalse();
        assertThat(valido("t=" + marca)).isFalse();
        assertThat(valido("v1=" + "00".repeat(32))).isFalse();
        assertThat(valido("t=" + marca + ",v1=no_es_hexadecimal")).isFalse();
    }

    @Test
    @DisplayName("sin secreto configurado nada vale")
    void sinSecreto() {
        String firmado = encabezado(AHORA.getEpochSecond(), CUERPO, SECRETO);
        assertThat(StripeWebhookVerifier.valido(CUERPO, firmado, "", 300, AHORA)).isFalse();
        assertThat(StripeWebhookVerifier.valido(CUERPO, firmado, null, 300, AHORA)).isFalse();
    }
}
