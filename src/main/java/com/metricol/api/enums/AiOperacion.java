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
    TEXTO_CAMPANA,

    /** El director de arte: mira las fotos y arma el plan de la imagen de campaña. */
    DIRECTOR_ARTE,

    /** El agente revisa una foto contra la marca: si va, por qué, y qué comunicar. */
    AGENTE_REVISAR,

    /** El agente pasa a texto lo que se dice en un video. */
    AGENTE_TRANSCRIBIR,

    /** El agente mira un video completo (varios cuadros y lo que se dice). */
    AGENTE_VIDEO,

    /** Organizar una tanda de fotos en carruseles, posts e historias (una llamada de texto por tanda). */
    AGENTE_ORGANIZAR,

    /** El director de foto: mira la foto en detalle y escribe cómo mejorarla y dónde va el logo. */
    AGENTE_DIRIGIR_FOTO,

    /** La mejora de la foto con el modelo de imágenes (fiel a la original). */
    AGENTE_MEJORAR,

    /** Comparar la foto mejorada con la original: que no se haya inventado nada. */
    AGENTE_VERIFICAR_FOTO,

    /** Deducir cómo trabaja el negocio (sus rasgos), una vez por espacio. */
    AGENTE_PERFILAR
}
