package com.metricol.api.service.campaign;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Los colores de marca, leídos del logo y no descritos a la IA.
 *
 * <p>Decirle "azul marino y verde" a un modelo de imágenes da un azul y un
 * verde cualquiera; darle los códigos exactos hace que la barra, el botón y el
 * titular salgan del color del logo. Es lo que hace que una campaña se vea de
 * la marca y no de una plantilla.
 *
 * <p>Agrupa los píxeles en cubos de color (16 niveles por canal), descarta el
 * fondo —blanco, casi blanco, transparente— y los grises sin matiz que casi
 * nunca son "el color de la marca", y devuelve los más frecuentes que no se
 * parezcan entre sí.
 */
final class PaletaDeLogo {

    private PaletaDeLogo() {
    }

    /** Hasta {@code maximo} colores en {@code #RRGGBB}, del más al menos presente. Vacío si no se pudo leer. */
    static List<String> dominantes(byte[] imagen, int maximo) {
        BufferedImage logo;
        try {
            logo = ImageIO.read(new ByteArrayInputStream(imagen));
        } catch (IOException ex) {
            return List.of();
        }
        if (logo == null) {
            return List.of();
        }

        // Se muestrea: un logo de 4000 px no necesita mirarse píxel por píxel.
        int salto = Math.max(1, Math.max(logo.getWidth(), logo.getHeight()) / 400);
        Map<Integer, long[]> cubos = new HashMap<>();
        for (int y = 0; y < logo.getHeight(); y += salto) {
            for (int x = 0; x < logo.getWidth(); x += salto) {
                int argb = logo.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a < 200 || esFondoOGris(r, g, b)) {
                    continue;
                }
                int clave = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                long[] acumulado = cubos.computeIfAbsent(clave, k -> new long[4]);
                acumulado[0]++;
                acumulado[1] += r;
                acumulado[2] += g;
                acumulado[3] += b;
            }
        }

        List<long[]> ordenados = new ArrayList<>(cubos.values());
        ordenados.sort((p, q) -> Long.compare(q[0], p[0]));

        List<int[]> elegidos = new ArrayList<>();
        for (long[] cubo : ordenados) {
            int[] color = { (int) (cubo[1] / cubo[0]), (int) (cubo[2] / cubo[0]), (int) (cubo[3] / cubo[0]) };
            boolean parecido = elegidos.stream().anyMatch(otro -> distancia(otro, color) < 70);
            if (!parecido) {
                elegidos.add(color);
            }
            if (elegidos.size() >= maximo) {
                break;
            }
        }

        List<String> hex = new ArrayList<>();
        for (int[] color : elegidos) {
            hex.add(String.format("#%02X%02X%02X", color[0], color[1], color[2]));
        }
        return hex;
    }

    /** Blanco, casi blanco o un gris sin matiz: el papel del logo, no su color. */
    private static boolean esFondoOGris(int r, int g, int b) {
        if (r >= 225 && g >= 225 && b >= 225) {
            return true;
        }
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 24 && max > 60;
    }

    private static double distancia(int[] a, int[] b) {
        int dr = a[0] - b[0];
        int dg = a[1] - b[1];
        int db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
