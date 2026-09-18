package com.metricol.api.models.response;

import java.util.List;

/**
 * La imagen (o las imágenes de un carrusel, en su orden) ya guardadas en R2,
 * con el texto que las acompaña.
 *
 * <p>{@code creditsUsed} y {@code creditsRemaining} son imágenes: por ahora el
 * tope es el número de imágenes por día y workspace, y la app ya sabe pintarlo
 * como "1 crédito usado · 9 restantes".
 */
public record CampaignImageResponse(
        String id,
        int version,
        String direction,
        String headline,
        String supportingCopy,
        String imageUrl,
        List<String> imageUrls,
        String caption,
        String revisedPrompt,
        int creditsUsed,
        Integer creditsRemaining) {
}
