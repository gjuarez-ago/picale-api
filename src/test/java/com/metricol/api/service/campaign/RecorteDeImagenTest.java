package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecorteDeImagenTest {

    static byte[] png(int ancho, int alto) throws Exception {
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", salida);
        return salida.toByteArray();
    }

    static BufferedImage leer(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("un lienzo 2:3 se recorta a 4:5 quitando alto, no ancho")
    void aCuatroQuintos() throws Exception {
        BufferedImage resultado = leer(RecorteDeImagen.recortar(png(1024, 1536), 4, 5, 0.9f));

        assertThat(resultado.getWidth()).isEqualTo(1024);
        assertThat(resultado.getHeight()).isEqualTo(1280);
    }

    @Test
    @DisplayName("un lienzo 2:3 se recorta a 9:16 quitando ancho, no alto")
    void aNueveDieciseisavos() throws Exception {
        BufferedImage resultado = leer(RecorteDeImagen.recortar(png(1024, 1536), 9, 16, 0.9f));

        assertThat(resultado.getWidth()).isEqualTo(864);
        assertThat(resultado.getHeight()).isEqualTo(1536);
    }

    @Test
    @DisplayName("recorta desde el centro: lo que queda es la franja del medio")
    void recortaDesdeElCentro() throws Exception {
        // Rojo arriba, verde en medio, azul abajo. Cuadrado desde 100x150:
        // se pierden 25 filas arriba y 25 abajo.
        BufferedImage imagen = new BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 150; y++) {
            int color = y < 50 ? 0xFF0000 : y < 100 ? 0x00FF00 : 0x0000FF;
            for (int x = 0; x < 100; x++) {
                imagen.setRGB(x, y, color);
            }
        }
        ByteArrayOutputStream origen = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", origen);

        BufferedImage resultado = leer(RecorteDeImagen.recortar(origen.toByteArray(), 1, 1, 1f));

        assertThat(resultado.getWidth()).isEqualTo(100);
        assertThat(resultado.getHeight()).isEqualTo(100);
        assertThat((resultado.getRGB(50, 50) >> 8) & 0xFF).as("verde en el centro").isGreaterThan(200);
        // La franja roja original ocupaba y<50; tras quitar 25 filas queda hasta y<25.
        assertThat((resultado.getRGB(50, 10) >> 16) & 0xFF).as("rojo arriba").isGreaterThan(200);
        assertThat(resultado.getRGB(50, 90) & 0xFF).as("azul abajo").isGreaterThan(200);
    }

    @Test
    @DisplayName("un PNG con transparencia sale sobre blanco, no en negro")
    void transparenciaSobreBlanco() throws Exception {
        BufferedImage resultado = leer(RecorteDeImagen.recortar(png(100, 100), 1, 1, 0.95f));

        assertThat(resultado.getRGB(10, 10) & 0xFF).isGreaterThan(240);
    }

    @Test
    @DisplayName("bytes que no son una imagen: error claro, no un NullPointer")
    void noEsUnaImagen() {
        assertThatThrownBy(() -> RecorteDeImagen.recortar(new byte[] { 1, 2, 3 }, 4, 5, 0.9f))
                .isInstanceOf(IllegalStateException.class);
    }
}
