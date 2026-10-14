package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.service.storage.R2StorageService;

class AdaptadorDeImagenesIntegrationTest {
    @TempDir Path temp;

    @Test
    void createsARealStoryPreviewWithFfmpegWithoutChangingTheOriginal() throws Exception {
        Path original = temp.resolve("cuadrada.jpg");
        BufferedImage source = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(new Color(18, 91, 142));
            graphics.fillRect(0, 0, 900, 900);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(225, 225, 450, 450);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(source, "jpg", original.toFile());
        byte[] originalHash = sha256(Files.readAllBytes(original));

        String url = "https://cdn.test/media/ws/cuadrada.jpg";
        String key = "media/ws/cuadrada.jpg";
        String previewUrl = "https://cdn.test/derivados/ws/preview.jpg";
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        R2StorageService storage = mock(R2StorageService.class);
        when(storage.claveDe(url)).thenReturn(key);
        when(storage.consultar(anyString())).thenAnswer(inv -> key.equals(inv.getArgument(0))
                ? new R2StorageService.Consulta(Files.size(original), "image/jpeg") : null);
        when(storage.descargar(eq(key), any(Path.class))).thenAnswer(inv -> {
            Files.copy(original, inv.getArgument(1), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        });
        when(storage.subirBytes(anyString(), any(byte[].class), eq("image/jpeg"))).thenAnswer(inv -> {
            uploaded.set(inv.getArgument(1));
            return previewUrl;
        });
        MediaAdaptProperties properties = new MediaAdaptProperties();
        AdaptadorDeImagenes adapter = new AdaptadorDeImagenes(properties, new FfmpegImagen(properties), storage);

        AdaptadorDeImagenes.Adaptacion result = adapter.adaptarParaPreview(UUID.randomUUID(), List.of(url),
                List.of(Platform.INSTAGRAM), PostFormat.STORY);

        assertThat(result.huboFallo()).isFalse();
        assertThat(result.urls()).containsExactly(previewUrl);
        BufferedImage preview = ImageIO.read(new java.io.ByteArrayInputStream(uploaded.get()));
        assertThat(preview).isNotNull();
        assertThat((double) preview.getWidth() / preview.getHeight()).isBetween(0.5615, 0.5635);
        assertThat(sha256(Files.readAllBytes(original))).containsExactly(originalHash);
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
