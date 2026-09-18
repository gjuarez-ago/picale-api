package com.metricol.api.enums;

/**
 * Para qué se le habló a OpenAI.
 *
 * <p>Es la columna por la que se desglosa el gasto. Saber que un cliente gasta
 * más sirve de poco sin saber EN QUÉ —fotos, textos o retoques—, y eso es lo
 * que dice qué se puede recortar.
 */
public enum AiOperacion {

    /** Mirar las fotos al subirlas (VisorDeMedios). */
    DESCRIBIR_IMAGENES,

    /** Escribir el texto de cada red a partir de lo dictado. */
    REDACTAR,

    /** Un chip de estilo sobre el texto de una red. */
    AJUSTAR,

    /**
     * La segunda vuelta de un chip cuya respuesta se pasó del límite. Aparte
     * de AJUSTAR para ver cuánto cuesta que el modelo no cuente bien.
     */
    ACORTAR,

    /** El endpoint viejo de sugerir un caption suelto. */
    SUGERIR_CAPTION,

    /** Una imagen de campaña generada con gpt-image (una fila por pieza). */
    GENERAR_IMAGEN,

    /** El titular y el caption que acompañan a una imagen de campaña. */
    TEXTO_CAMPANA
}
