package com.metricol.api.service.media;

import java.util.Collection;

import com.metricol.api.enums.Platform;

/**
 * Qué forma tiene que tener una imagen para que una red la acepte.
 *
 * <p>Existe para que "adaptar a la red elegida" signifique algo concreto y no
 * una corazonada. Cada red tiene su propia idea de qué es una imagen válida, y
 * la diferencia entre ellas es enorme: Instagram por API es mucho más estricto
 * que su propia aplicación —solo acepta entre 4:5 y 1.91:1— mientras que
 * Facebook se traga casi cualquier cosa.
 *
 * <p><b>La pieza importante es {@link #interseccion(Collection)}.</b> Una
 * publicación sale a varias redes en UNA sola llamada al proveedor, con un
 * solo juego de imágenes; no se puede mandar una versión distinta a cada una.
 * Así que la imagen tiene que cumplir a la vez con todas las elegidas, y eso
 * es exactamente la intersección de sus límites. El efecto práctico: si se
 * publica solo en TikTok, una foto vertical 9:16 se queda como está; en cuanto
 * se añade Instagram, hay que adaptarla.
 *
 * <p>Los números de Instagram son los que documenta Meta para el Content
 * Publishing API. Los de las demás son topes prudentes, no límites duros
 * copiados: donde una red es generosa o no publica un número exacto, aquí se
 * pone un valor cómodo y seguro. Equivocarse por prudente cuesta unos píxeles;
 * equivocarse por optimista cuesta una publicación rechazada.
 */
public record EspecImagen(
        double ratioMin,
        double ratioMax,
        int anchoMin,
        int anchoMax,
        long bytesMax,
        int maxFotos) {

    private static final long MB = 1024L * 1024L;

    /** Sin restricción real de proporción, para las redes permisivas. */
    private static final double LIBRE_MIN = 0.1;
    private static final double LIBRE_MAX = 10.0;

    public static EspecImagen de(Platform platform) {
        return switch (platform) {
            // Meta lo documenta así para publicar por API: entre 4:5 y
            // 1.91:1, hasta 1440 px de ancho y 8 MB. Es la más estricta de
            // todas y la que en la práctica manda.
            case INSTAGRAM -> new EspecImagen(0.8, 1.91, 320, 1440, 8 * MB, 10);

            // Facebook acepta proporciones muy variadas; el tope de peso es
            // prudente, no el máximo real.
            case FACEBOOK -> new EspecImagen(LIBRE_MIN, LIBRE_MAX, 200, 2048, 8 * MB, 10);

            // Las fotos de TikTok admiten vertical completo, que es
            // justamente su formato natural.
            case TIKTOK -> new EspecImagen(LIBRE_MIN, LIBRE_MAX, 200, 1920, 10 * MB, 35);

            // YouTube no publica fotos: solo video. Cero fotos no es un tope
            // apretado, es "por aquí no se puede", y es lo que hace que
            // `PostService` lo diga antes de subir nada en vez de dejar que
            // upload-post lo rechace al final.
            //
            // El resto de los números van a propósito en lo más permisivo que
            // hay. `interseccion` se queda con el mínimo de cada uno, y unos
            // valores estrechos aquí encogerían las fotos de las otras redes
            // por una red que ni siquiera las acepta.
            case YOUTUBE -> new EspecImagen(LIBRE_MIN, LIBRE_MAX, 0, Integer.MAX_VALUE, Long.MAX_VALUE, 0);

            case LINKEDIN -> new EspecImagen(LIBRE_MIN, LIBRE_MAX, 200, 2048, 8 * MB, 20);
        };
    }

    /**
     * El único formato que sirve para todas las redes a la vez.
     *
     * <p>Se queda con el mínimo más alto, el máximo más bajo y el tope de peso
     * más chico. Si la lista viene vacía —no debería— devuelve la de Instagram
     * por ser la estricta: pasarse de exigente no rompe nada, quedarse corto
     * sí.
     */
    public static EspecImagen interseccion(Collection<Platform> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return de(Platform.INSTAGRAM);
        }

        double ratioMin = 0;
        double ratioMax = Double.MAX_VALUE;
        int anchoMin = 0;
        int anchoMax = Integer.MAX_VALUE;
        long bytesMax = Long.MAX_VALUE;
        int maxFotos = Integer.MAX_VALUE;

        for (Platform platform : platforms) {
            EspecImagen espec = de(platform);
            ratioMin = Math.max(ratioMin, espec.ratioMin());
            ratioMax = Math.min(ratioMax, espec.ratioMax());
            anchoMin = Math.max(anchoMin, espec.anchoMin());
            anchoMax = Math.min(anchoMax, espec.anchoMax());
            bytesMax = Math.min(bytesMax, espec.bytesMax());
            maxFotos = Math.min(maxFotos, espec.maxFotos());
        }

        return new EspecImagen(ratioMin, ratioMax, anchoMin, anchoMax, bytesMax, maxFotos);
    }

    /** 9:16, la proporción de una historia o un reel. */
    public static final double RATIO_VERTICAL = 9.0 / 16.0;

    /**
     * Lo que exige una historia o un reel: 9:16, cualquiera sea la red.
     *
     * <p>No se cruza con los límites del feed de {@link #interseccion}: una
     * historia de Instagram admite 9:16 aunque el feed por API pida entre 4:5 y
     * 1.91:1, y mezclarlos daría un rango vacío. De la intersección solo se
     * toma lo que sigue valiendo —el peso y el ancho máximos—.
     *
     * <p>La proporción lleva un margen de un 0.01: 1080 × 1919 es la misma foto
     * que 1080 × 1920 y no debe reencodarse por un píxel.
     */
    public static EspecImagen vertical(Collection<Platform> platforms) {
        EspecImagen base = interseccion(platforms);
        return new EspecImagen(
                RATIO_VERTICAL - 0.01,
                RATIO_VERTICAL + 0.01,
                720,
                Math.min(1080, base.anchoMax()),
                base.bytesMax(),
                base.maxFotos());
    }

    /** Una especificación de proporción única (9:16): no hay rango del que elegir la más cercana. */
    public boolean proporcionFija() {
        return ratioMax - ratioMin <= 0.03;
    }

    /** ¿Hay que tocar esta imagen, o ya sirve tal cual? */
    public boolean cumple(int ancho, int alto, long bytes) {
        if (ancho <= 0 || alto <= 0) {
            return false;
        }
        double proporcion = (double) ancho / alto;
        return proporcion >= ratioMin
                && proporcion <= ratioMax
                && ancho <= anchoMax
                && ancho >= anchoMin
                && bytes <= bytesMax;
    }

    /**
     * La proporción permitida más cercana a la que trae la foto.
     *
     * <p>La MÁS CERCANA, no una fija: llevar una foto casi cuadrada al 4:5 y
     * una apaisada al 1.91:1 mueve mucho menos la imagen que empujarlas a las
     * dos al mismo sitio.
     */
    public double ratioObjetivo(double proporcion) {
        if (proporcionFija()) {
            // Con un solo valor posible se va exactamente a él: acercarse al
            // borde del margen dejaría un 9:16 que no lo es del todo.
            return (ratioMin + ratioMax) / 2;
        }
        if (proporcion < ratioMin) {
            return ratioMin;
        }
        if (proporcion > ratioMax) {
            return ratioMax;
        }
        return proporcion;
    }
}
