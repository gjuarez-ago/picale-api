package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class AssistantToolResultTest {
    final ObjectMapper json = new ObjectMapper();
    static String photo() throws Exception {
        var image = new java.awt.image.BufferedImage(180, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(17);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) image.setRGB(x, y, random.nextInt());
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test void separaLaImagenCompletaDelTextoSinMutarElResultado() throws Exception {
        String data = photo();
        assertThat(data.length()).isGreaterThan(40_000);
        var result = json.createObjectNode();
        result.putArray("content").addObject().put("type", "image").put("mimeType", "image/png").put("data", data);
        var message = AssistantToolResult.message(json, "call-1", result);
        assertThat(message.path(AssistantToolResult.IMAGES).get(0).asText()).isEqualTo("data:image/png;base64," + data);
        assertThat(message.path("content").asText()).doesNotContain(data).contains("entrada visual");
        assertThat(result.at("/content/0/data").asText()).isEqualTo(data);
    }
    @Test void rechazaContenidoCorruptoSinFingirQueSeVioLaImagen() throws Exception {
        var result = json.readTree("{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"YWJj\"}]}");
        var message = AssistantToolResult.message(json, "call-2", result);
        assertThat(message.has(AssistantToolResult.IMAGES)).isFalse();
        assertThat(message.path("content").asText()).contains("no disponible").doesNotContain("YWJj");
    }
}
