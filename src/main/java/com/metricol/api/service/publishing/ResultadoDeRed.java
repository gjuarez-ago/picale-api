package com.metricol.api.service.publishing;

/**
 * Cómo le fue a UNA red con UNA publicación, según el proveedor.
 *
 * <p>Es lo que antes se daba por supuesto. El código leía la respuesta de
 * {@code /upload} buscando un detalle por red que ahí no existe —upload-post
 * publica en diferido y esa llamada solo acusa recibo— y, al no encontrarlo,
 * daba la red por publicada. Todas las publicaciones quedaban en "publicada"
 * sin identificador ni enlace, y una red que hubiera fallado de verdad habría
 * quedado igual de "publicada".
 *
 * @param publicada si la red la acepto
 * @param postId    el identificador que le puso la red, cuando lo hay
 * @param url       el enlace a la publicacion en la red, cuando lo hay
 * @param error     por que la rechazo, cuando la rechazo
 */
public record ResultadoDeRed(boolean publicada, String postId, String url, String error) {

    public static ResultadoDeRed publicada(String postId, String url) {
        return new ResultadoDeRed(true, postId, url, null);
    }

    public static ResultadoDeRed rechazada(String error) {
        return new ResultadoDeRed(false, null, null,
                error == null || error.isBlank() ? "La red rechazo la publicacion." : error);
    }
}
