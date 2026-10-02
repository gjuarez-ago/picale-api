package com.metricol.api.enums;

/**
 * En qué va un archivo con el agente. Nulo en la fila = el agente no lo ha
 * tocado (o nunca lo hará: se subió con el agente apagado).
 *
 * <p>Ver {@code docs/agente-community-manager.md}.
 */
public enum EtapaAgente {

    /** Va con la marca y ya hay una propuesta esperando aprobación. */
    PROPUESTA,

    /** No está claro si va: espera que la persona diga sí o no. Sin gastar nada. */
    OBSERVACION,

    /** No va con la marca, o se repite. No se borra: se puede rescatar. */
    DESCARTADA,

    /** La persona aprobó su propuesta: ya está programada. */
    APROBADA,

    /** No se pudo revisar (la IA falló o no hay redes). Se reintenta más tarde, hasta tres veces. */
    PENDIENTE,

    /**
     * Alguien lo está revisando ahora (el proceso de fondo o un botón). Es el
     * candado que evita que dos lo revisen a la vez y salgan dos propuestas.
     * Si quien lo tomó se cae, a los 20 minutos vuelve a estar libre.
     */
    REVISANDO,

    /**
     * Revisada y aprobada por la marca, esperando a que se organice con las
     * demás de su tanda (carrusel, post o historia). Ver {@code OrganizadorDeContenido}.
     */
    ANALIZADA
}
