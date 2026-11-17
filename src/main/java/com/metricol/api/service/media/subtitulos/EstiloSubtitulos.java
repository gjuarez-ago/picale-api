package com.metricol.api.service.media.subtitulos;

/** Cómo se ven los subtítulos pintados en el video. */
public enum EstiloSubtitulos {

    /** Unas pocas palabras a la vez y se ilumina la que se está diciendo, como en TikTok. */
    PALABRA,

    /** Una o dos palabras enormes, en mayúsculas, que entran con un rebote. */
    REBOTE,

    /** Como "palabra", pero la que suena lleva detrás una caja de color: el de los videos que explican. */
    CAJA,

    /** Una línea abajo sobre una franja oscura, como los de la tele. */
    CLASICO
}
