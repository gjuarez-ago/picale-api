package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.campaign.SelloDeLogo.Colocado;
import com.metricol.api.service.campaign.SelloDeLogo.Posicion;

/** El logo sobre una foto real, sin placa cuando se puede (CMRG, 5 oct 2026). */
class SelloSobreFotoTest {

    private static final int W = 1280;
    private static final int H = 960;
    private static final Color MARINO = new Color(16, 40, 92);
    private static final Color VERDE = new Color(40, 170, 70);

    private static byte[] png(BufferedImage imagen) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", bytes);
        return bytes.toByteArray();
    }

    private static byte[] foto(Color color) throws Exception {
        BufferedImage foto = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = foto.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return png(foto);
    }

    /** Como el de CMRG: letras en azul marino y una "M" verde, sobre transparente. */
    private static byte[] logo(Color letras) throws Exception {
        BufferedImage logo = new BufferedImage(600, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(VERDE);
        g.fillRect(20, 30, 140, 140);
        g.setColor(letras);
        g.fillRect(200, 50, 380, 60);
        g.fillRect(200, 130, 300, 20);
        g.dispose();
        return png(logo);
    }

    private static BufferedImage leer(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static double luz(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    /** Cuántos píxeles de la mitad de abajo a la izquierda cumplen la condición. */
    private static int contar(BufferedImage imagen, java.util.function.IntPredicate condicion) {
        int n = 0;
        for (int y = H / 2; y < H; y++) {
            for (int x = 0; x < W / 2; x++) {
                if (condicion.test(imagen.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean verde(int rgb) {
        return ((rgb >> 8) & 0xFF) > 130 && ((rgb >> 16) & 0xFF) < 90;
    }

    @Test
    @DisplayName("sobre grava oscura: sin placa, las letras oscuras pasan a blanco y el verde se queda")
    void fondoOscuro() throws Exception {
        Colocado c = SelloDeLogo.colocarSobreFoto(foto(new Color(60, 58, 55)), logo(MARINO), Posicion.BOTTOM_LEFT,
                0.30, false);
        BufferedImage salida = leer(c.imagen());
        assertThat(c.posicion()).isEqualTo(Posicion.BOTTOM_LEFT);
        assertThat(contar(salida, rgb -> luz(rgb) > 230)).isGreaterThan(5000);
        assertThat(contar(salida, SelloSobreFotoTest::verde)).isGreaterThan(2000);
        // Sin placa: nada azul marino quedó, y no hay un parche claro alrededor.
        assertThat(contar(salida, rgb -> (rgb & 0xFF) > 80 && luz(rgb) < 50)).isZero();
    }

    @Test
    @DisplayName("sobre una pared clara: el logo va directo, tal cual, sin placa blanca")
    void fondoClaro() throws Exception {
        Colocado c = SelloDeLogo.colocarSobreFoto(foto(new Color(200, 196, 188)), logo(MARINO), Posicion.BOTTOM_LEFT,
                0.30, false);
        BufferedImage salida = leer(c.imagen());
        assertThat(contar(salida, rgb -> luz(rgb) < 60)).isGreaterThan(5000);
        assertThat(contar(salida, rgb -> luz(rgb) > 240)).isZero();
    }

    @Test
    @DisplayName("un logo claro sobre fondo claro no se leería: lleva su placa")
    void claroSobreClaro() throws Exception {
        Colocado c = SelloDeLogo.colocarSobreFoto(foto(new Color(225, 225, 225)), logo(new Color(245, 245, 245)),
                Posicion.BOTTOM_LEFT, 0.30, false);
        BufferedImage salida = leer(c.imagen());
        // La placa oscura de los logos claros.
        assertThat(contar(salida, rgb -> luz(rgb) < 50)).isGreaterThan(5000);
    }

    @Test
    @DisplayName("del tamaño que pidió el director")
    void tamano() throws Exception {
        BufferedImage chico = leer(SelloDeLogo.colocarSobreFoto(foto(new Color(200, 196, 188)), logo(MARINO),
                Posicion.BOTTOM_LEFT, 0.22, false).imagen());
        BufferedImage grande = leer(SelloDeLogo.colocarSobreFoto(foto(new Color(200, 196, 188)), logo(MARINO),
                Posicion.BOTTOM_LEFT, 0.38, false).imagen());
        assertThat(contar(grande, rgb -> luz(rgb) < 60)).isGreaterThan(2 * contar(chico, rgb -> luz(rgb) < 60));
    }
}
