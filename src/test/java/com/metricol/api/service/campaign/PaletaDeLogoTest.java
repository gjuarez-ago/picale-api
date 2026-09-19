package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaletaDeLogoTest {

    private static byte[] png(BufferedImage imagen) throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", salida);
        return salida.toByteArray();
    }

    /** Como el logo de CMRG: hoja blanca, un bloque azul marino grande y uno verde más chico. */
    private static byte[] logoAzulYVerde() throws Exception {
        BufferedImage logo = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 400);
        g.setColor(new Color(11, 42, 91));
        g.fillRect(100, 100, 400, 200);
        g.setColor(new Color(31, 163, 74));
        g.fillRect(520, 100, 180, 200);
        g.dispose();
        return png(logo);
    }

    private static int[] rgb(String hex) {
        return new int[] { Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16) };
    }

    private static double cerca(String hex, int r, int g, int b) {
        int[] c = rgb(hex);
        return Math.sqrt(Math.pow(c[0] - r, 2) + Math.pow(c[1] - g, 2) + Math.pow(c[2] - b, 2));
    }

    @Test
    @DisplayName("saca el azul y el verde del logo, del más presente al menos, sin contar el papel blanco")
    void azulYVerde() throws Exception {
        List<String> paleta = PaletaDeLogo.dominantes(logoAzulYVerde(), 3);

        assertThat(paleta).hasSize(2);
        assertThat(cerca(paleta.get(0), 11, 42, 91)).isLessThan(20);
        assertThat(cerca(paleta.get(1), 31, 163, 74)).isLessThan(20);
    }

    @Test
    @DisplayName("respeta el máximo pedido")
    void maximo() throws Exception {
        assertThat(PaletaDeLogo.dominantes(logoAzulYVerde(), 1)).hasSize(1);
    }

    @Test
    @DisplayName("un logo transparente con letras de color ignora lo transparente")
    void transparente() throws Exception {
        BufferedImage logo = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(50, 30, 100, 40);
        g.dispose();

        List<String> paleta = PaletaDeLogo.dominantes(png(logo), 3);

        assertThat(paleta).hasSize(1);
        assertThat(cerca(paleta.get(0), 200, 30, 30)).isLessThan(20);
    }

    @Test
    @DisplayName("un logo en blanco y negro no inventa colores: los grises no son color de marca")
    void grises() throws Exception {
        BufferedImage logo = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.setColor(new Color(120, 120, 120));
        g.fillRect(50, 30, 100, 40);
        g.dispose();

        assertThat(PaletaDeLogo.dominantes(png(logo), 3)).isEmpty();
    }

    @Test
    @DisplayName("bytes que no son una imagen: lista vacía, sin reventar")
    void ilegible() {
        assertThat(PaletaDeLogo.dominantes(new byte[] { 1, 2, 3 }, 3)).isEmpty();
    }
}
