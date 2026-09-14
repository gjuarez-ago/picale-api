package com.metricol.api.enums;

/**
 * En qué punto va un archivo de medios.
 *
 * <p>Existe desde que la app sube directo a R2 y el servidor deja de ver los
 * bytes. Antes no hacía falta: el archivo llegaba a la API, se subía y la fila
 * nacía completa o no nacía. Con la subida directa hay un hueco entre
 * "aparté el sitio" y "el archivo está ahí", y ese hueco tiene que ser un
 * estado y no una suposición: si el teléfono se queda sin batería a mitad,
 * alguien tiene que poder distinguir el archivo que llegó del que no.
 */
public enum MediaAssetStatus {

    /**
     * Se prefirmó la subida y se apartó el espacio, pero todavía no se ha
     * confirmado que el archivo llegara a R2.
     *
     * <p>Cuenta para la cuota a propósito: si no contara, seis prefirmados
     * seguidos pasarían del límite entre todos. Lo que no cuenta es para la
     * galería, porque puede que ese archivo no exista.
     */
    PENDING,

    /** El archivo está en R2 y su tamaño real ya está apuntado. */
    READY,

    /**
     * El archivo ya no está en R2; la fila se conserva.
     *
     * <p>Es lo que le pasa al video de una publicación que salió hace tiempo.
     * Un video pesa hasta cien megas y se sube para entregárselo a las redes;
     * una vez publicado, cada red guarda el suyo y el nuestro solo cuesta. Las
     * fotos NO se liberan: son la biblioteca que se reutiliza y pesan mil
     * veces menos.
     *
     * <p>La fila sobrevive porque la publicación la sigue nombrando, y con
     * ella la miniatura, que sí se conserva: la publicación se sigue viendo
     * con su portada y el video se ve entero en la red donde salió.
     *
     * <p>No cuenta para la cuota. Es el punto: ese espacio ya se devolvió.
     */
    RELEASED
}
