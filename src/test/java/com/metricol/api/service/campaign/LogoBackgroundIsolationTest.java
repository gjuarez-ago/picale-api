package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class LogoBackgroundIsolationTest {
    @Test void quitaSoloElFondoExteriorYConservaHuecosInteriores() {
        var source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(Color.BLUE); g.fillRect(0,0,100,100);
        g.setColor(Color.RED); g.fillRect(20,20,60,60);
        g.setColor(Color.BLUE); g.fillRect(40,40,20,20); g.dispose();
        var derived = LogoBackgroundIsolation.removeSolid(source);
        assertThat(derived.getRGB(0,0) >>> 24).isZero();
        assertThat(derived.getRGB(50,50)).isEqualTo(Color.BLUE.getRGB());
        assertThat(derived.getRGB(30,30)).isEqualTo(Color.RED.getRGB());
        assertThat(source.getRGB(0,0)).isEqualTo(Color.BLUE.getRGB());
    }
    @Test void noEliminaUnArchivoQueEsSoloFondo() {
        var source = new BufferedImage(30,30,BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(() -> LogoBackgroundIsolation.removeSolid(source)).hasMessageContaining("separar");
    }
}
