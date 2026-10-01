package com.metricol.api.service.social;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.metricol.api.entity.Workspace;

/**
 * Dónde está el negocio, como lo pide cada red al publicar.
 *
 * <p>Según el OpenAPI de upload-post: Instagram la lee en {@code location_id},
 * TikTok en {@code tiktok_location_id} junto con {@code tiktok_location_name}
 * (las dos o ninguna), y Facebook no tiene campo de ubicación.
 *
 * @param instagramId el id del lugar en Instagram, o nulo
 * @param tiktokId    el id del lugar en TikTok, o nulo
 * @param tiktokNombre el nombre del lugar en TikTok, o nulo
 */
public record Ubicacion(String instagramId, String tiktokId, String tiktokNombre) {

    public static final Ubicacion NINGUNA = new Ubicacion(null, null, null);

    private static final Pattern ENLACE_INSTAGRAM = Pattern.compile("locations/(\\d{3,})");
    private static final Pattern SOLO_NUMERO = Pattern.compile("^\\d{3,}$");

    /** La del negocio, o ninguna si no tiene local (ver {@code Workspace.ubicacionActiva}). */
    public static Ubicacion de(Workspace w) {
        if (w == null || !Boolean.TRUE.equals(w.getUbicacionActiva())) {
            return NINGUNA;
        }
        return new Ubicacion(w.getUbicacionInstagramId(), w.getUbicacionTiktokId(), w.getUbicacionTiktokNombre());
    }

    public boolean alguna() {
        return enInstagram() || enTiktok();
    }

    public boolean enInstagram() {
        return instagramId != null && !instagramId.isBlank();
    }

    /** TikTok la pide con id y nombre juntos: con uno solo no se manda. */
    public boolean enTiktok() {
        return tiktokId != null && !tiktokId.isBlank() && tiktokNombre != null && !tiktokNombre.isBlank();
    }

    /**
     * El id de un lugar de Instagram a partir de lo que pegue la persona: el
     * enlace ({@code instagram.com/explore/locations/213385402/merida-yucatan/})
     * o el número solo. Instagram no da un buscador por API, y el enlace es lo
     * que cualquiera puede copiar desde la app.
     *
     * @return el id, o nulo si no se reconoce
     */
    public static String idDeInstagram(String pegado) {
        if (pegado == null) {
            return null;
        }
        String t = pegado.strip();
        if (SOLO_NUMERO.matcher(t).matches()) {
            return t;
        }
        Matcher m = ENLACE_INSTAGRAM.matcher(t);
        return m.find() ? m.group(1) : null;
    }
}
