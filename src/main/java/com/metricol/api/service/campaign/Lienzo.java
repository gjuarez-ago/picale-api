package com.metricol.api.service.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.campaign.CampaignImageService.Formato;

/**
 * La forma que necesita una imagen: proporción final y el lienzo que se le pide
 * a la IA. Es lo que distingue una versión de otra.
 *
 * <p>Se genera UNA imagen por lienzo y no una por red: Instagram y Facebook
 * piden lo mismo en una publicación (4:5), así que comparten la imagen, y
 * LinkedIn luce mejor cuadrada. Generar una por red gastaría tiempo y imágenes
 * del día en piezas idénticas.
 */
enum Lienzo {

    /** El feed de Instagram y Facebook. Se pide a 2:3 y se recorta desde el centro. */
    CUATRO_QUINTOS(4, 5, "4:5", "1024x1536"),

    /** LinkedIn. Cuadrada: es el lienzo nativo de la IA, sin recorte. */
    CUADRADO(1, 1, "1:1", "1024x1024"),

    /** Historias. Se pide a 2:3 y se recorta a los lados. */
    HISTORIA(9, 16, "9:16", "1024x1536");

    final int ratioAncho;
    final int ratioAlto;
    final String etiqueta;

    /** El tamaño que se le pide a gpt-image, que solo admite tres. */
    final String tamano;

    Lienzo(int ratioAncho, int ratioAlto, String etiqueta, String tamano) {
        this.ratioAncho = ratioAncho;
        this.ratioAlto = ratioAlto;
        this.etiqueta = etiqueta;
        this.tamano = tamano;
    }

    boolean historia() {
        return this == HISTORIA;
    }

    /** El lienzo que le toca a una red con un formato. */
    static Lienzo deRed(Platform red, Formato formato) {
        if (formato == Formato.STORY) {
            return HISTORIA;
        }
        return red == Platform.LINKEDIN ? CUADRADO : CUATRO_QUINTOS;
    }

    /**
     * Una versión del contenido: la imagen que se crea para un lienzo y las
     * redes que la usan.
     */
    record Variante(String id, Lienzo lienzo, List<Platform> redes) {

        /**
         * Junta las redes por lienzo, conservando el orden en que se eligieron.
         * Con Instagram, Facebook y LinkedIn salen dos versiones, no tres.
         */
        static List<Variante> agrupar(Formato formato, List<Platform> redes) {
            Map<Lienzo, List<Platform>> porLienzo = new LinkedHashMap<>();
            for (Platform red : redes) {
                porLienzo.computeIfAbsent(deRed(red, formato), l -> new ArrayList<>()).add(red);
            }
            List<Variante> variantes = new ArrayList<>();
            int n = 1;
            for (Map.Entry<Lienzo, List<Platform>> e : porLienzo.entrySet()) {
                variantes.add(new Variante("v" + n++, e.getKey(), List.copyOf(e.getValue())));
            }
            return variantes;
        }
    }
}
