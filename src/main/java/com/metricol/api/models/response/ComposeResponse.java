package com.metricol.api.models.response;

import java.util.Map;

/**
 * El borrador listo para enseñar.
 *
 * @param guion  la idea en una frase — lo editable, de donde se recrea todo
 * @param textos el texto por red, con la llave en mayusculas (INSTAGRAM, TIKTOK...)
 */
public record ComposeResponse(String guion, Map<String, String> textos) {
}
