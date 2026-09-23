package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class SelloDeLogoTest {

    private static final int W = 1024;
    private static final int H = 1280;
    private static final Color AZUL = new Color(11, 42, 91);
    private static final Color GRIS = new Color(128, 128, 128);

    private static byte[] png(BufferedImage imagen) throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", salida);
        return salida.toByteArray();
    }

    private static BufferedImage gris(int ancho, int alto) {
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setColor(GRIS);
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        return imagen;
    }

    /** Una foto lisa gris: cualquier cosa que no sea gris viene del sello. */
    private static byte[] fondoGris(int ancho, int alto) throws Exception {
        return png(gris(ancho, alto));
    }

    /**
     * "Letras" sobre la foto: barras blancas finas y seguidas en un rectángulo,
     * que es lo que un titular le hace a la medida de ocupación.
     */
    private static void letras(BufferedImage foto, int x0, int y0, int x1, int y1) {
        Graphics2D g = foto.createGraphics();
        g.setColor(Color.WHITE);
        for (int x = x0; x < x1; x += 12) {
            g.fillRect(x, y0, 6, y1 - y0);
        }
        g.dispose();
    }

    /** Como el logo de un negocio: hoja blanca de 1600x800 con el dibujo azul en el centro. */
    private static byte[] logoConAire() throws Exception {
        BufferedImage logo = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1600, 800);
        g.setColor(AZUL);
        g.fillRect(500, 300, 600, 200);
        g.dispose();
        return png(logo);
    }

    /** Un logo con fondo de color de marca: azul de borde a borde y el dibujo blanco en el centro. */
    private static byte[] logoSobreAzul() throws Exception {
        BufferedImage logo = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(AZUL);
        g.fillRect(0, 0, 800, 400);
        g.setColor(Color.WHITE);
        g.fillRect(200, 100, 400, 200);
        g.dispose();
        return png(logo);
    }

    private static BufferedImage leer(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static boolean esAzul(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return b > 60 && b < 130 && r < 50 && g < 80;
    }

    private static boolean esBlanco(int rgb) {
        return ((rgb >> 16) & 0xFF) > 220 && ((rgb >> 8) & 0xFF) > 220 && (rgb & 0xFF) > 220;
    }

    /** Ancho, centro y cuántos píxeles azules hay dentro de la franja de filas dada. */
    private record Mancha(int cuantos, double centroX, double centroY, int minX, int maxX, int minY, int maxY) {
    }

    private static Mancha azules(BufferedImage imagen, int desdeY, int hastaY) {
        return mancha(imagen, desdeY, hastaY, true);
    }

    private static Mancha blancos(BufferedImage imagen, int desdeY, int hastaY) {
        return mancha(imagen, desdeY, hastaY, false);
    }

    private static Mancha mancha(BufferedImage imagen, int desdeY, int hastaY, boolean azul) {
        int n = 0;
        long sx = 0;
        long sy = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = desdeY; y < hastaY; y++) {
            for (int x = 0; x < imagen.getWidth(); x++) {
                int rgb = imagen.getRGB(x, y);
                if (azul ? esAzul(rgb) : esBlanco(rgb)) {
                    n++;
                    sx += x;
                    sy += y;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new Mancha(n, n == 0 ? 0 : (double) sx / n, n == 0 ? 0 : (double) sy / n, minX, maxX, minY, maxY);
    }

    @Test
    @DisplayName("abajo al centro: el logo queda centrado y en la parte baja")
    void abajoAlCentro() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.BOTTOM_CENTER, false));

        assertThat(salida.getWidth()).isEqualTo(W);
        assertThat(salida.getHeight()).isEqualTo(H);
        Mancha abajo = azules(salida, H / 2, H);
        assertThat(abajo.cuantos()).isGreaterThan(1000);
        assertThat(abajo.centroX()).isBetween(W / 2.0 - 12, W / 2.0 + 12);
        assertThat(azules(salida, 0, H / 2).cuantos()).as("nada en la mitad de arriba").isZero();
    }

    @Test
    @DisplayName("arriba a la izquierda: el logo queda en esa esquina")
    void arribaIzquierda() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.TOP_LEFT, false));

        Mancha arriba = azules(salida, 0, H / 2);
        assertThat(arriba.cuantos()).isGreaterThan(1000);
        assertThat(arriba.centroX()).isLessThan(W / 2.0);
        assertThat(azules(salida, H / 2, H).cuantos()).isZero();
    }

    @Test
    @DisplayName("abajo a la derecha: el logo queda en esa esquina")
    void abajoDerecha() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.BOTTOM_RIGHT, false));

        Mancha abajo = azules(salida, H / 2, H);
        assertThat(abajo.centroX()).isGreaterThan(W / 2.0);
        assertThat(abajo.maxX()).isLessThan(W);
    }

    @Test
    @DisplayName("se le quita el aire al logo: una hoja blanca con un dibujo chico no sale diminuta")
    void recortaElAire() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.BOTTOM_CENTER, false));

        Mancha m = azules(salida, H / 2, H);
        // El dibujo ocupa solo 600 de 1600 px del archivo; sin recorte saldría
        // en un tercio de este tamaño.
        assertThat(m.maxX() - m.minX()).isGreaterThan((int) (W * 0.25));
    }

    @Test
    @DisplayName("un logo sobre hoja blanca conserva su fondo blanco alrededor del dibujo")
    void placaBlanca() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.BOTTOM_CENTER, false));

        Mancha m = azules(salida, H / 2, H);
        // Un poco a la izquierda del dibujo, dentro del sello: casi blanco.
        int pixel = salida.getRGB(m.minX() - 8, (int) m.centroY());
        assertThat(pixel & 0xFF).isGreaterThan(220);
    }

    @Test
    @DisplayName("el borde es angosto y las esquinas apenas redondeadas: se ve el logo, no una tarjeta")
    void bordeAngostoYEsquinasSuaves() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoConAire(), Posicion.TOP_LEFT, false));

        Mancha m = azules(salida, 0, H / 2);
        int y = (int) m.centroY();
        // A 30 px del dibujo ya es foto: el blanco alrededor mide menos que eso. Antes
        // la placa sobresalía más de 40 px por cada lado.
        assertThat(esBlanco(salida.getRGB(m.minX() - 8, y))).as("aire propio del logo").isTrue();
        assertThat(esBlanco(salida.getRGB(m.minX() - 30, y))).as("foto, no tarjeta").isFalse();

        // El sello empieza en el margen (4 % y 3.5 %). Su esquina exacta queda fuera del
        // redondeo —se ve la foto—, y unos píxeles hacia adentro ya es el sello.
        int x0 = (int) Math.round(W * 0.04);
        int y0 = (int) Math.round(H * 0.035);
        assertThat(esBlanco(salida.getRGB(x0, y0))).as("esquina redondeada").isFalse();
        assertThat(esBlanco(salida.getRGB(x0 + 8, y))).as("dentro del sello").isTrue();
    }

    @Test
    @DisplayName("un logo con fondo de color de marca lleva ese color alrededor, no una placa blanca")
    void bordeDelColorDelLogo() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), logoSobreAzul(), Posicion.BOTTOM_CENTER, false));

        Mancha dibujo = blancos(salida, H / 2, H);
        assertThat(dibujo.cuantos()).isGreaterThan(500);
        int y = (int) dibujo.centroY();
        // Junto al dibujo blanco: el azul del propio logo.
        assertThat(esAzul(salida.getRGB(dibujo.minX() - 6, y))).isTrue();
        // Y fuera del sello no hay ningún blanco: el único blanco de la mitad de abajo es el dibujo.
        Mancha azul = azules(salida, H / 2, H);
        assertThat(esBlanco(salida.getRGB(azul.minX() - 3, y))).isFalse();
        assertThat(dibujo.minX()).isGreaterThan(azul.minX());
        assertThat(dibujo.maxX()).isLessThan(azul.maxX());
    }

    @Test
    @DisplayName("un logo claro sobre transparente lleva placa oscura, o no se vería")
    void logoClaroLlevaPlacaOscura() throws Exception {
        BufferedImage claro = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = claro.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(100, 100, 400, 100);
        g.dispose();

        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(W, H), png(claro), Posicion.BOTTOM_CENTER, false));

        // La placa asoma por encima del logo: debe ser más oscura que el fondo gris.
        int y = H - (int) Math.round(H * 0.035) - 4;
        int pixel = salida.getRGB(W / 2, y);
        assertThat(pixel & 0xFF).isLessThan(100);
    }

    @Test
    @DisplayName("en una historia el logo queda lejos del borde de abajo: la red pone su interfaz ahí")
    void historiaDejaAire() throws Exception {
        BufferedImage salida = leer(SelloDeLogo.poner(fondoGris(864, 1536), logoConAire(), Posicion.BOTTOM_CENTER, true));

        // La caja de respuesta de la red ocupa el último 20%: nada del logo ahí.
        assertThat(azules(salida, (int) (1536 * 0.80), 1536).cuantos()).isZero();
        assertThat(azules(salida, 768, 1536).cuantos()).isGreaterThan(500);
    }

    // ------------------------------------------------------------ sitio limpio

    @Test
    @DisplayName("si el rincón pedido tiene letras debajo, el logo se pasa al otro lado de la misma fila")
    void seMueveSiElRinconEstaOcupado() throws Exception {
        BufferedImage foto = gris(W, H);
        // Un titular arriba a la izquierda, justo donde iría el logo.
        letras(foto, 0, 60, W / 2, 260);

        Colocado colocado = SelloDeLogo.colocar(png(foto), logoConAire(), Posicion.TOP_LEFT, false);

        assertThat(colocado.posicion()).isEqualTo(Posicion.TOP_RIGHT);
        BufferedImage salida = leer(colocado.imagen());
        Mancha arriba = azules(salida, 0, H / 2);
        assertThat(arriba.cuantos()).isGreaterThan(1000);
        assertThat(arriba.centroX()).isGreaterThan(W / 2.0);
        assertThat(azules(salida, H / 2, H).cuantos()).isZero();
    }

    @Test
    @DisplayName("con toda la fila ocupada, el logo cambia de fila")
    void cambiaDeFilaSiHaceFalta() throws Exception {
        BufferedImage foto = gris(W, H);
        // Una banda de texto de lado a lado en la parte baja.
        letras(foto, 0, (int) (H * 0.6), W, H);

        Colocado colocado = SelloDeLogo.colocar(png(foto), logoConAire(), Posicion.BOTTOM_CENTER, false);

        assertThat(colocado.posicion()).isEqualTo(Posicion.TOP_CENTER);
        assertThat(azules(leer(colocado.imagen()), 0, H / 2).cuantos()).isGreaterThan(1000);
    }

    @Test
    @DisplayName("si toda la foto está igual de ocupada, lo pedido manda")
    void sinSitioMejorSeQuedaDondeSePidio() throws Exception {
        BufferedImage foto = gris(W, H);
        letras(foto, 0, 0, W, H);

        Colocado colocado = SelloDeLogo.colocar(png(foto), logoConAire(), Posicion.TOP_LEFT, false);

        assertThat(colocado.posicion()).isEqualTo(Posicion.TOP_LEFT);
    }

    @Test
    @DisplayName("una foto lisa no mueve nada: el rincón pedido está limpio y se respeta")
    void limpioSeRespeta() throws Exception {
        Colocado colocado = SelloDeLogo.colocar(fondoGris(W, H), logoConAire(), Posicion.BOTTOM_LEFT, false);

        assertThat(colocado.posicion()).isEqualTo(Posicion.BOTTOM_LEFT);
    }

    @Test
    @DisplayName("la ocupación distingue un fondo liso de uno con letras")
    void ocupacion() {
        BufferedImage foto = gris(W, H);
        java.awt.Rectangle rincon = new java.awt.Rectangle(40, 45, 340, 140);
        assertThat(SelloDeLogo.ocupacion(foto, rincon)).isLessThan(1.0);

        letras(foto, 0, 0, W / 2, 300);
        assertThat(SelloDeLogo.ocupacion(foto, rincon)).isGreaterThan(SelloDeLogo.OCUPADO);
    }

    @Test
    @DisplayName("las alternativas van de la más parecida a la menos: misma fila primero, el centro al final")
    void alternativas() {
        assertThat(Posicion.TOP_LEFT.alternativas()).containsExactly(Posicion.TOP_RIGHT, Posicion.TOP_CENTER,
                Posicion.BOTTOM_LEFT, Posicion.BOTTOM_RIGHT, Posicion.BOTTOM_CENTER);
        assertThat(Posicion.BOTTOM_CENTER.alternativas()).containsExactly(Posicion.BOTTOM_RIGHT, Posicion.BOTTOM_LEFT,
                Posicion.TOP_CENTER, Posicion.TOP_RIGHT, Posicion.TOP_LEFT);
        for (Posicion p : Posicion.values()) {
            assertThat(p.alternativas()).doesNotContain(p).hasSize(5);
        }
    }

    // ------------------------------------------------------------ errores y códigos

    @Test
    @DisplayName("un logo que no se puede leer avisa, para que se publique sin él")
    void logoIlegible() throws Exception {
        assertThatThrownBy(() -> SelloDeLogo.poner(fondoGris(W, H), new byte[] { 1, 2, 3 }, Posicion.TOP_LEFT, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un logo todo blanco no tiene nada que pegar")
    void logoVacio() throws Exception {
        BufferedImage blanco = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blanco.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        assertThatThrownBy(() -> SelloDeLogo.poner(fondoGris(W, H), png(blanco), Posicion.TOP_LEFT, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("los códigos de posición se leen sin importar mayúsculas y lo desconocido da null")
    void codigos() {
        assertThat(Posicion.de("bottom_center")).isEqualTo(Posicion.BOTTOM_CENTER);
        assertThat(Posicion.de(" TOP_RIGHT ")).isEqualTo(Posicion.TOP_RIGHT);
        assertThat(Posicion.de("NONE")).isNull();
        assertThat(Posicion.de("en el medio")).isNull();
        assertThat(Posicion.de(null)).isNull();
    }
}
