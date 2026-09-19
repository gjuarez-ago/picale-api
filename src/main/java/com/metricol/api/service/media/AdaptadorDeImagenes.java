package com.metricol.api.service.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Deja cada imagen como la quiere la red antes de publicarla, sin preguntarle
 * nada a nadie.
 *
 * <p>Antes esto lo resolvía la app: cuando una foto no cabía en la proporción
 * de Instagram, le ponía a la persona dos botones —"Recortar" o "Rellenar"— y
 * la obligaba a decidir. Es una decisión que no debería existir: nadie abre
 * una aplicación de publicar para aprender que Instagram solo acepta entre 4:5
 * y 1.91:1.
 *
 * <p>Ahora se hace aquí, y la foto no pierde nada: lo que le falta al lienzo
 * se rellena con la propia foto ampliada y desenfocada (ver
 * {@link FfmpegImagen#encajar}).
 *
 * <p><b>Dónde encaja en el tiempo.</b> Se hace al publicar y no al subir, por
 * una razón: al subir todavía no se sabe a qué redes va. Y las redes cambian
 * lo que hay que hacer — una foto vertical 9:16 se queda intacta si solo va a
 * TikTok, y hay que adaptarla en cuanto se añade Instagram.
 *
 * <p><b>Nunca tumba una publicación.</b> Si ffmpeg falta, falla o tarda, se
 * devuelve la URL original y se publica con ella: que la red la rechace es un
 * final peor, pero no publicar es el peor de todos.
 */
@Service
public class AdaptadorDeImagenes {

    private static final Logger log = LoggerFactory.getLogger(AdaptadorDeImagenes.class);

    /**
     * Los derivados viven fuera de {@code media/}, que es el prefijo de lo que
     * subió la persona y lo que mide la cuota. No son suyos: los generamos
     * nosotros, y cobrarle el espacio de una conversión que no pidió sería
     * cobrarle por nuestra decisión de diseño.
     */
    private static final String PREFIJO = "derivados/";

    private final MediaAdaptProperties props;
    private final FfmpegImagen ffmpeg;
    private final R2StorageService storage;

    public AdaptadorDeImagenes(
            MediaAdaptProperties props,
            FfmpegImagen ffmpeg,
            R2StorageService storage) {
        this.props = props;
        this.ffmpeg = ffmpeg;
        this.storage = storage;
    }

    /**
     * Devuelve la lista lista para publicar: las URLs que ya servían tal cual,
     * y las adaptadas donde hacía falta.
     *
     * <p>Siempre devuelve tantas URLs como recibió y en el mismo orden. Un
     * carrusel es una sola publicación con las fotos en el orden que eligió la
     * persona, y perder una o cambiarlas de sitio se vería en la red.
     */
    public Adaptacion adaptar(UUID workspaceId, List<String> urls, Collection<Platform> platforms) {
        return adaptar(workspaceId, urls, platforms, PostFormat.PHOTO);
    }

    /**
     * Igual, sabiendo qué formato se publica. Una historia o un reel piden 9:16
     * y esto lo deja así solo: la foto se encaja sobre un fondo hecho con ella
     * misma, sin recortar y sin pedirle nada a nadie. Antes la app rechazaba en
     * rojo cualquier foto que no fuera vertical y obligaba a cambiarla.
     */
    public Adaptacion adaptar(UUID workspaceId, List<String> urls, Collection<Platform> platforms,
            PostFormat formato) {
        if (!props.isEnabled() || urls == null || urls.isEmpty()) {
            return new Adaptacion(urls, false);
        }

        EspecImagen espec = exigeVertical(formato)
                ? EspecImagen.vertical(platforms)
                : EspecImagen.interseccion(platforms);
        List<String> resultado = new ArrayList<>(urls.size());
        boolean huboFallo = false;

        for (String url : urls) {
            String adaptada = null;
            try {
                adaptada = adaptarUna(workspaceId, url, espec);
            } catch (Exception ex) {
                // Ancho a proposito: cualquier cosa que salga mal aqui es un
                // motivo para publicar la original, no para perder el post.
                huboFallo = true;
                log.warn("No se pudo adaptar {}: {}", url, ex.toString());
            }
            resultado.add(adaptada == null ? url : adaptada);
        }
        return new Adaptacion(resultado, huboFallo);
    }

    /** Historia y reel piden 9:16; el resto de los formatos, lo que pida cada red. */
    static boolean exigeVertical(PostFormat formato) {
        return formato == PostFormat.STORY || formato == PostFormat.REEL;
    }

    /**
     * Lo que sale de {@link #adaptar}: las URLs con las que hay que publicar
     * y si alguna se quedo sin adaptar porque la conversion fallo.
     *
     * <p>Lo segundo no es un detalle interno. Publicar la original porque ya
     * servia y publicarla porque la conversion revento se ven igual desde
     * fuera —la misma URL—, pero no son lo mismo: en el segundo caso la red
     * la puede rechazar, y ese rechazo SI se arregla volviendo a intentarlo,
     * porque lo que fallo estaba de nuestro lado. Quien publica necesita
     * poder distinguirlo para no dar por perdida una publicacion que solo
     * necesitaba otra pasada.
     */
    public record Adaptacion(List<String> urls, boolean huboFallo) {
    }

    private String adaptarUna(UUID workspaceId, String url, EspecImagen espec) throws IOException {
        String clave = storage.claveDe(url);
        if (clave == null) {
            // No es nuestra: ni se descarga ni se toca.
            return null;
        }
        if (esVideo(clave)) {
            // De momento solo imágenes. Un video mal encajado no se arregla
            // reescalándolo, y sí se rompe reencodándolo a ciegas.
            return null;
        }

        R2StorageService.Consulta original = storage.consultar(clave);
        if (original == null) {
            return null;
        }
        if (original.sizeBytes() > props.getMaxBytesEntrada()) {
            log.warn("La imagen {} pesa {} bytes y no se adapta", clave, original.sizeBytes());
            return null;
        }

        String claveDerivada = claveDerivada(workspaceId, clave, espec);

        // Si ya se adaptó con ESTA misma especificación, se reutiliza. Importa
        // más de lo que parece: la cola reintenta hasta cuatro veces, y sin
        // esto cada reintento volvería a bajar, convertir y subir.
        if (storage.consultar(claveDerivada) != null) {
            return storage.urlDe(claveDerivada);
        }

        Path temporal = Files.createTempFile("picale-origen-", extensionDe(clave));
        try {
            if (!storage.descargar(clave, temporal)) {
                return null;
            }

            FfmpegImagen.Medidas medidas = ffmpeg.medir(temporal);
            if (medidas == null) {
                return null;
            }

            // Medir no basta: las medidas viven en la cabecera y la cabecera
            // de un archivo truncado es perfecta. Se decodifica entera. Si
            // ffmpeg se queja, la imagen NO pasa tal cual aunque cumpla la
            // especificacion: se reencoda, que es lo que la deja bien formada.
            // Una foto asi paso por aqui sin tocarse y la rechazaron las
            // cuatro redes.
            String errores = ffmpeg.verificar(temporal);
            if (errores == null) {
                // Ni se pudo decodificar. Se publica la original: que la red
                // la rechace es un motivo para avisar, no para perder el post.
                log.warn("La imagen {} no se pudo decodificar; se publica tal cual", clave);
                return null;
            }
            boolean danada = !errores.isBlank();
            if (danada) {
                log.info("La imagen {} tiene errores de decodificacion y se reencoda: {}", clave,
                        errores.length() > 200 ? errores.substring(0, 200) + "..." : errores);
            }

            if (!danada && esJpeg(clave)
                    && espec.cumple(medidas.ancho(), medidas.alto(), original.sizeBytes())) {
                // Ya servía. No se toca: reencodar una foto que ya cumple solo
                // le quita calidad.
                return null;
            }

            byte[] adaptada = convertir(temporal, medidas, espec);
            if (adaptada == null) {
                return null;
            }

            log.info("Imagen adaptada: {}x{} ({} bytes) -> {} bytes, para {}",
                    medidas.ancho(), medidas.alto(), original.sizeBytes(), adaptada.length, espec);

            return storage.subirBytes(claveDerivada, adaptada, "image/jpeg");
        } finally {
            FfmpegImagen.borrar(temporal);
        }
    }

    /**
     * Calcula el lienzo y convierte, apretando si no cabe en el peso.
     *
     * <p>Tres intentos y no uno porque el peso final de un JPEG no se puede
     * calcular por adelantado: depende de lo que tenga la foto dentro. Se
     * empieza con buena calidad y solo se aprieta si se pasa.
     */
    private byte[] convertir(Path origen, FfmpegImagen.Medidas medidas, EspecImagen espec) {
        double proporcion = medidas.proporcion();
        double objetivo = espec.ratioObjetivo(proporcion);

        // El lienzo conserva el lado que ya estaba bien y crece por el otro.
        int ancho;
        int alto;
        if (proporcion < objetivo) {
            ancho = (int) Math.round(medidas.alto() * objetivo);
            alto = medidas.alto();
        } else if (proporcion > objetivo) {
            ancho = medidas.ancho();
            alto = (int) Math.round(medidas.ancho() / objetivo);
        } else {
            ancho = medidas.ancho();
            alto = medidas.alto();
        }

        // Y despues se acota al ancho que sirve la red: mas grande solo gasta
        // datos, porque la red lo reescala igual al recibirlo.
        if (ancho > espec.anchoMax()) {
            alto = (int) Math.round(alto * (espec.anchoMax() / (double) ancho));
            ancho = espec.anchoMax();
        }
        if (ancho < espec.anchoMin()) {
            alto = (int) Math.round(alto * (espec.anchoMin() / (double) ancho));
            ancho = espec.anchoMin();
        }

        ancho = par(ancho);
        alto = par(alto);

        int calidad = props.getCalidad();
        byte[] mejorQueHay = null;

        for (int intento = 0; intento < 3; intento++) {
            byte[] bytes = ffmpeg.encajar(origen, ancho, alto, calidad);
            if (bytes == null) {
                return mejorQueHay;
            }
            if (bytes.length <= espec.bytesMax()) {
                return bytes;
            }
            mejorQueHay = bytes;
            // Peor calidad y menos ancho, los dos a la vez: bajar solo la
            // calidad no salva una foto de 48 MP.
            calidad = Math.min(31, calidad + 6);
            ancho = par(Math.max(espec.anchoMin(), (int) (ancho * 0.8)));
            alto = par((int) (alto * 0.8));
        }

        // Se devuelve el ultimo aunque siga pasado: una imagen que la red
        // quiza rechace por peso es mejor que ninguna, y el log ya lo cuenta.
        log.warn("La imagen sigue pesando {} bytes tras tres intentos (tope {})",
                mejorQueHay == null ? -1 : mejorQueHay.length, espec.bytesMax());
        return mejorQueHay;
    }

    /**
     * Redondea a par. El formato yuvj420p submuestrea el color de dos en dos
     * pixeles, y con un lado impar ffmpeg falla con "width not divisible by 2".
     */
    private static int par(int valor) {
        return Math.max(2, valor - (valor % 2));
    }

    /**
     * Nombre estable: la misma imagen con la misma especificacion da siempre
     * la misma clave. De ahi sale gratis que un reintento reutilice lo ya
     * convertido, y que dos publicaciones a las mismas redes compartan archivo.
     *
     * <p>La clave tiene DOS niveles —{@code derivados/ws/huellaOriginal/
     * huellaEspec.jpg}— y no uno. Con un solo nivel el nombre era el resumen
     * de todo junto, así que, dada una imagen, no había forma de saber cuáles
     * de los archivos del bucket salían de ella: un resumen no se deshace. Sus
     * derivados sobrevivían al original y no los borraba nadie nunca, porque
     * no quedaba ninguna fila que supiera que estaban ahí. Con el original en
     * su propia carpeta, borrarlos es borrar un prefijo.
     */
    private String claveDerivada(UUID workspaceId, String claveOriginal, EspecImagen espec) {
        return prefijoDe(workspaceId, claveOriginal)
                + resumir(espec + "|q" + props.getCalidad()) + ".jpg";
    }

    /**
     * La carpeta donde viven TODOS los derivados de una imagen, con la barra
     * final. Borrar esto es borrar todo lo que se generó a partir de ella, sea
     * para las redes que sea.
     */
    public static String prefijoDe(UUID workspaceId, String claveOriginal) {
        return prefijoDe(workspaceId) + resumir(claveOriginal) + "/";
    }

    /** La carpeta de derivados del workspace entero, con la barra final. */
    public static String prefijoDe(UUID workspaceId) {
        return PREFIJO + workspaceId + "/";
    }

    /** La raíz de todos los derivados, de todos los workspaces. */
    public static String prefijoRaiz() {
        return PREFIJO;
    }

    /**
     * De qué workspace y de qué imagen es este derivado, leyéndolo de su
     * clave. {@code null} si la clave no tiene la forma actual.
     *
     * <p>Devolver {@code null} no es un error: son las claves del formato
     * anterior, planas —{@code derivados/<workspace>/<resumen>.jpg}—, donde el
     * nombre mezclaba imagen y especificación en un solo resumen y por eso no
     * se podía relacionar con nada. Quien barre las trata como sobrantes, que
     * es lo que son: no hay forma de saber de qué salieron, y la única imagen
     * capaz de reclamarlas produciría hoy una clave distinta.
     */
    public static RefDerivado leerDerivado(String clave) {
        if (clave == null || !clave.startsWith(PREFIJO)) {
            return null;
        }

        String resto = clave.substring(PREFIJO.length());

        int finWorkspace = resto.indexOf('/');
        if (finWorkspace <= 0) {
            return null;
        }

        String desdeCarpeta = resto.substring(finWorkspace + 1);
        int finCarpeta = desdeCarpeta.indexOf('/');
        if (finCarpeta <= 0) {
            return null;
        }

        return new RefDerivado(
                resto.substring(0, finWorkspace),
                desdeCarpeta.substring(0, finCarpeta));
    }

    /**
     * Las dos piezas que sitúan a un derivado: de quién es y de qué imagen
     * salió. La carpeta es el resumen de la clave del original, así que no
     * dice cuál es —solo permite preguntar si alguna de las vivas encaja.
     */
    public record RefDerivado(String workspaceId, String carpeta) {
    }

    /**
     * El nombre de carpeta que le tocaría a esta clave original.
     *
     * <p>Lo usa la barrida nocturna: como el resumen no se puede deshacer,
     * el camino es al revés —se calcula el de cada archivo que sigue vivo y se
     * borra toda carpeta que no salga en esa lista—.
     */
    public static String carpetaDe(String claveOriginal) {
        return resumir(claveOriginal);
    }

    private static String resumir(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Esta JVM no trae SHA-256", ex);
        }
    }

    private static String extensionDe(String clave) {
        int punto = clave.lastIndexOf('.');
        return punto < 0 || punto == clave.length() - 1 ? ".img" : clave.substring(punto);
    }

    private static boolean esJpeg(String clave) {
        String ext = extensionDe(clave).toLowerCase(Locale.ROOT);
        return ext.equals(".jpg") || ext.equals(".jpeg");
    }

    private static boolean esVideo(String clave) {
        String ext = extensionDe(clave).toLowerCase(Locale.ROOT);
        return ext.equals(".mp4") || ext.equals(".mov") || ext.equals(".webm")
                || ext.equals(".avi") || ext.equals(".mkv") || ext.equals(".m4v");
    }
}
