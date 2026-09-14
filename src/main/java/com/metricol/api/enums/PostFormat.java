package com.metricol.api.enums;

/**
 * Qué clase de publicación es. Se ELIGE, no se adivina.
 *
 * <p>Antes no existía: lo único que había era {@code MediaType}, sacado del
 * archivo. Una foto era una foto y un video era un reel, y con eso no se puede
 * aplicar ninguna regla antes de subir nada — ni saber que una historia lleva
 * un solo archivo, ni exigir 9:16, ni apagar las redes que no publican ese
 * formato. Todo eso llegaba tarde, en forma de rechazo del proveedor.
 *
 * <p>El formato es además lo que decide a qué se parece la publicación en la
 * red: el mismo video de treinta segundos es un reel o una historia según lo
 * que se diga, y si no se dice nada upload-post lo trata como reel.
 */
public enum PostFormat {

    /** Una foto o un carrusel. Lo más común, y por eso el valor por omisión. */
    PHOTO("Foto o carrusel", true),

    /** Video vertical corto. Lo que hoy sale por omisión al subir un video. */
    REEL("Reel", true),

    /** Historia: un solo archivo, foto o video, que dura un día. */
    STORY("Historia", true),

    /**
     * Video largo, para el feed y no como reel.
     *
     * <p><b>Todavía no se ofrece</b> ({@code ofrecido} en false): está aquí
     * porque es la siguiente categoría prevista y porque tenerlo en el modelo
     * desde el principio convierte lanzarlo en encender un booleano, en vez de
     * en volver a tocar la app, la web, el servidor y la base.
     *
     * <p>Que no se ofrezca no lo borra de las reglas: {@link #ofrecido()} dice
     * qué se le puede proponer a alguien, y el resto del enum sigue sabiendo
     * nombrarlo. Es lo mismo que hace el catálogo de redes del frontend con
     * las que no están disponibles.
     */
    VIDEO("Video", false);

    private final String label;
    private final boolean ofrecido;

    PostFormat(String label, boolean ofrecido) {
        this.label = label;
        this.ofrecido = ofrecido;
    }

    /** El nombre como lo lee una persona, para los mensajes y la pantalla. */
    public String getLabel() {
        return label;
    }

    /** Si esta versión deja elegirlo. Ver {@link #VIDEO}. */
    public boolean ofrecido() {
        return ofrecido;
    }
}
