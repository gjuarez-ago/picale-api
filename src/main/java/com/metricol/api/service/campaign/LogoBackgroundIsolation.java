package com.metricol.api.service.campaign;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** Separa exclusivamente un fondo exterior uniforme aprobado; no infiere si pertenece a la identidad. */
final class LogoBackgroundIsolation {
    private LogoBackgroundIsolation() {}
    static BufferedImage removeSolid(BufferedImage source) {
        int width = source.getWidth(), height = source.getHeight();
        if ((long) width * height > 16_777_216) throw new IllegalArgumentException("El logo es demasiado grande para separar su fondo. Usa una versión de menor resolución.");
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = out.createGraphics();
        try { graphics.drawImage(source, 0, 0, null); } finally { graphics.dispose(); }
        int background = source.getRGB(0, 0);
        if ((background >>> 24) == 0) return out;
        int compatible = 0, samples = 0;
        for (int x = 0; x < width; x += Math.max(1, width / 100)) {
            samples += 2;
            if (similar(source.getRGB(x, 0), background)) compatible++;
            if (similar(source.getRGB(x, height - 1), background)) compatible++;
        }
        for (int y = 0; y < height; y += Math.max(1, height / 100)) {
            samples += 2;
            if (similar(source.getRGB(0, y), background)) compatible++;
            if (similar(source.getRGB(width - 1, y), background)) compatible++;
        }
        if (compatible < samples * .9) throw new IllegalArgumentException("El fondo del logo no es uniforme. Elige conservarlo o sube una versión transparente.");
        boolean[] seen = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < width; x++) { offer(queue, seen, x); offer(queue, seen, (height - 1) * width + x); }
        for (int y = 1; y < height - 1; y++) { offer(queue, seen, y * width); offer(queue, seen, y * width + width - 1); }
        int removed = 0;
        while (!queue.isEmpty()) {
            int i = queue.removeFirst();
            int x = i % width, y = i / width;
            if (!similar(source.getRGB(x, y), background)) continue;
            out.setRGB(x, y, 0); removed++;
            if (x > 0) offer(queue, seen, i - 1);
            if (x + 1 < width) offer(queue, seen, i + 1);
            if (y > 0) offer(queue, seen, i - width);
            if (y + 1 < height) offer(queue, seen, i + width);
        }
        if (removed > width * height * .99) throw new IllegalArgumentException("No pude separar el símbolo de su fondo con seguridad. Elige conservar el original.");
        return out;
    }
    private static void offer(ArrayDeque<Integer> queue, boolean[] seen, int index) {
        if (!seen[index]) { seen[index] = true; queue.add(index); }
    }
    private static boolean similar(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= 24
                && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= 24 && Math.abs((a & 255) - (b & 255)) <= 24;
    }
}
