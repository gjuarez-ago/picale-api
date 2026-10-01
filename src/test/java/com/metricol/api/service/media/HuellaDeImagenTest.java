package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La huella reconoce la misma foto aunque cambie de tamaño, y separa fotos distintas. */
class HuellaDeImagenTest {

    /** Una "foto": un degradado con un bloque en el sitio indicado. */
    private static byte[] foto(int ancho, int alto, int bloqueX, String formato) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < ancho; x++) {
            int v = 40 + x * 180 / ancho;
            g.setColor(new Color(v, v / 2, 255 - v));
            g.drawLine(x, 0, x, alto);
        }
        g.setColor(Color.WHITE);
        g.fillRect(bloqueX * ancho / 100, alto / 4, ancho / 5, alto / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("la misma foto en otro tamaño y otro formato es la misma")
    void mismaFoto() throws Exception {
        Long grande = HuellaDeImagen.de(foto(1200, 900, 20, "png"));
        Long chica = HuellaDeImagen.de(foto(400, 300, 20, "jpg"));
        assertThat(HuellaDeImagen.parecidas(grande, chica)).isTrue();
    }

    @Test
    @DisplayName("dos fotos distintas no se confunden")
    void distintas() throws Exception {
        Long una = HuellaDeImagen.de(foto(800, 600, 10, "png"));
        Long otra = HuellaDeImagen.de(foto(800, 600, 70, "png"));
        assertThat(HuellaDeImagen.parecidas(una, otra)).isFalse();
    }

    @Test
    @DisplayName("lo que no es una imagen no tiene huella")
    void noEsImagen() {
        assertThat(HuellaDeImagen.de("hola".getBytes())).isNull();
    }
}
