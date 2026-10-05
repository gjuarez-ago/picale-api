package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El acabado de diseñador sobre una foto real (CMRG, 5 oct 2026). */
class AcabadoDeMarcaTest {

    private static BufferedImage foto(int w, int h, Color c) {
        BufferedImage f = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = f.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return f;
    }

    /** Como el de CMRG: un círculo con anillo verde y blanco adentro, sobre una hoja blanca. */
    private static BufferedImage logoEnHojaBlanca() {
        BufferedImage l = foto(200, 200, Color.WHITE);
        Graphics2D g = l.createGraphics();
        g.setColor(new Color(30, 140, 60));
        g.fillOval(20, 20, 160, 160);
        g.setColor(Color.WHITE);
        g.fillOval(35, 35, 130, 130);
        g.setColor(new Color(16, 40, 92));
        g.fillRect(70, 90, 60, 20);
        g.dispose();
        return l;
    }

    private static BufferedImage leer(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    @DisplayName("el logo pierde la hoja blanca de afuera y conserva el blanco que encierra")
    void sinFondo() {
        BufferedImage l = AcabadoDeMarca.sinFondo(logoEnHojaBlanca());
        assertThat((l.getRGB(5, 5) >>> 24) & 0xFF).isZero();
        assertThat((l.getRGB(100, 50) >>> 24) & 0xFF).isEqualTo(255);
        assertThat(l.getRGB(100, 50) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    @DisplayName("limpio: sobre la foto no queda ningún cuadro blanco alrededor del logo")
    void limpioSinCuadro() throws Exception {
        BufferedImage salida = leer(AcabadoDeMarca.acabar(AcabadoDeMarca.png(foto(1200, 900, new Color(90, 85, 80))),
                AcabadoDeMarca.png(logoEnHojaBlanca()), new AcabadoDeMarca.Opciones(AcabadoDeMarca.Estilo.LIMPIO,
                        SelloDeLogo.Posicion.BOTTOM_LEFT, 0.25, null, null, null, false)));
        // La esquina del rectángulo del logo (fuera del círculo) sigue siendo la foto, no blanco.
        int x = (int) Math.round(1200 * 0.04) + 2;
        int y = 900 - (int) Math.round(900 * 0.035) - 2;
        assertThat(SelloDeLogo.luz(salida.getRGB(x, y))).isLessThan(140);
    }

    @Test
    @DisplayName("franja: el borde de abajo se oscurece con el color de la marca y arriba la foto sigue igual")
    void franja() throws Exception {
        BufferedImage salida = leer(AcabadoDeMarca.acabar(AcabadoDeMarca.png(foto(1000, 1000, new Color(200, 200, 200))),
                AcabadoDeMarca.png(logoEnHojaBlanca()), new AcabadoDeMarca.Opciones(AcabadoDeMarca.Estilo.FRANJA,
                        SelloDeLogo.Posicion.BOTTOM_LEFT, 0.25, null, "Señalización industrial", "CMRG", false)));
        assertThat(SelloDeLogo.luz(salida.getRGB(500, 100))).isGreaterThan(190);
        assertThat(SelloDeLogo.luz(salida.getRGB(990, 990))).isLessThan(90);
    }

    @Test
    @DisplayName("en historia no va franja (la interfaz de la red la taparía): va limpia")
    void historiaSinFranja() throws Exception {
        BufferedImage salida = leer(AcabadoDeMarca.acabar(AcabadoDeMarca.png(foto(900, 1600, new Color(200, 200, 200))),
                AcabadoDeMarca.png(logoEnHojaBlanca()), new AcabadoDeMarca.Opciones(AcabadoDeMarca.Estilo.FRANJA,
                        SelloDeLogo.Posicion.TOP_LEFT, 0.25, null, "Señalización", "CMRG", true)));
        assertThat(SelloDeLogo.luz(salida.getRGB(890, 1590))).isGreaterThan(190);
    }

    @Test
    @DisplayName("encuadre: recorta lo pedido, y uno que tiraría demasiado se ignora")
    void encuadre() throws Exception {
        byte[] f = AcabadoDeMarca.png(foto(1000, 800, Color.GRAY));
        AcabadoDeMarca.Opciones bueno = new AcabadoDeMarca.Opciones(AcabadoDeMarca.Estilo.LIMPIO,
                SelloDeLogo.Posicion.BOTTOM_LEFT, 0.25, new AcabadoDeMarca.Encuadre(0.1, 0.0, 0.8, 0.9), null, null, false);
        assertThat(leer(AcabadoDeMarca.acabar(f, null, bueno)).getWidth()).isEqualTo(800);
        AcabadoDeMarca.Opciones exagerado = new AcabadoDeMarca.Opciones(AcabadoDeMarca.Estilo.LIMPIO,
                SelloDeLogo.Posicion.BOTTOM_LEFT, 0.25, new AcabadoDeMarca.Encuadre(0.3, 0.3, 0.4, 0.4), null, null, false);
        assertThat(leer(AcabadoDeMarca.acabar(f, null, exagerado)).getWidth()).isEqualTo(1000);
    }

    @Test
    @DisplayName("marco: el borde toma el color de la marca y la foto entra completa por dentro")
    void marco() {
        BufferedImage m = AcabadoDeMarca.marco(foto(1000, 1000, new Color(220, 220, 220)), new Color(16, 40, 92));
        assertThat(SelloDeLogo.luz(m.getRGB(3, 500))).isLessThan(60);
        assertThat(SelloDeLogo.luz(m.getRGB(500, 500))).isGreaterThan(200);
    }
}
