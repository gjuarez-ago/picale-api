package com.metricol.api.service.media;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Saca un fotograma de cada video para poder enseñarlo sin reproducirlo.
 *
 * <p>Antes la galería pintaba un icono gris de cámara para todos los videos.
 * Con dos videos ya no se sabe cuál es cuál: hay que abrirlos de uno en uno
 * para encontrar el que se busca.
 *
 * <p><b>Se hace en el servidor y no en el teléfono</b>, aunque el teléfono
 * podría. Tres razones: la miniatura se saca UNA vez y la ven todos los
 * dispositivos de esa cuenta; no hace falta arrastrar un paquete de
 * thumbnails a la app; y el panel web —que no tiene forma de decodificar un
 * video— la recibe gratis.
 *
 * <p>Y se hace FUERA de la petición de confirmar. Sacar un fotograma tarda un
 * par de segundos y confirmar una subida tiene que contestar ya: la persona
 * está mirando la barra de progreso. Cuando la miniatura esté lista aparecerá
 * en el siguiente refresco de la galería.
 */
@Service
public class MiniaturaDeVideo {

    private static final Logger log = LoggerFactory.getLogger(MiniaturaDeVideo.class);

    private static final String PREFIJO = "derivados/miniaturas/";

    /**
     * Dónde viven las miniaturas, para quien tenga que distinguirlas.
     *
     * <p>Cuelgan de {@code derivados/} pero NO siguen su forma —son claves
     * planas, una por video, sin la carpeta por imagen original—, así que la
     * barrida de derivados las tomaba por sobrantes y las borraba todas. Se
     * expone el prefijo para que pueda reconocerlas en vez de adivinarlas.
     */
    public static String prefijo() {
        return PREFIJO;
    }

    /**
     * La clave que le toca a la miniatura de este video.
     *
     * <p>Se calcula del mismo modo que al generarla, y vive aquí para que
     * exista un solo sitio que sepa la forma del nombre: quien barre necesita
     * poder decir qué miniaturas siguen teniendo video detrás, y sin esto
     * tendría que reimplementar el mismo saneado y quedarse desincronizado en
     * cuanto uno de los dos cambiara.
     */
    public static String claveDe(String storageKey) {
        return PREFIJO + storageKey.replaceAll("[^A-Za-z0-9]", "_") + ".jpg";
    }

    /** Ancho de la miniatura. Se pinta en una cuadrícula, no a pantalla completa. */
    private static final int ANCHO = 480;

    private final MediaAdaptProperties props;
    private final FfmpegImagen ffmpeg;
    private final R2StorageService storage;
    private final MediaAssetRepository repository;
    private final MejorCuadroDeVideo mejorCuadro;

    public MiniaturaDeVideo(
            MediaAdaptProperties props,
            FfmpegImagen ffmpeg,
            R2StorageService storage,
            MediaAssetRepository repository,
            MejorCuadroDeVideo mejorCuadro) {
        this.props = props;
        this.ffmpeg = ffmpeg;
        this.storage = storage;
        this.repository = repository;
        this.mejorCuadro = mejorCuadro;
    }

    /**
     * Genera la miniatura de un video y la guarda en su fila.
     *
     * <p>En su propia transacción. Quien la llama en otro hilo es
     * {@link MiniaturasEnSegundoPlano}, que además impone el workspace: aquí
     * NO se puede, porque la transacción —y con ella la resolución del
     * tenant— se abre antes de la primera línea de este método.
     *
     * <p>Quien llama ya contestó a quien estaba esperando, y no debe quedarse
     * atado a esto.
     *
     * <p>Nunca lanza. Un video sin miniatura se sigue viendo con el icono de
     * siempre; que falle esto no puede costar una subida que ya salió bien.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generar(java.util.UUID assetId) {
        generar(assetId, false);
    }

    /**
     * @param rehacer la fila ya apunta a una miniatura, pero el archivo no
     *                está en R2: se vuelve a sacar. Cae en la misma clave, así
     *                que la URL no cambia y las portadas que la usan —en la
     *                fila del video y en sus publicaciones— vuelven a verse.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generar(java.util.UUID assetId, boolean rehacer) {
        if (!props.isEnabled()) {
            return;
        }

        MediaAsset asset = repository.findById(assetId).orElse(null);
        if (asset == null || asset.getType() != MediaType.VIDEO) {
            return;
        }
        if (!rehacer && asset.getThumbnailUrl() != null && !asset.getThumbnailUrl().isBlank()) {
            return;
        }

        // Cada salida dice por qué.
        //
        // Antes las cuatro eran `return` a secas, y el resultado era que el
        // arranque anunciaba "sacando miniatura a 3 videos" y después no
        // aparecía nada: ni lista ni fallida. Desde fuera no había forma de
        // distinguir el archivo que ya no está en R2 del ffmpeg que no supo
        // leer el video, y las dos se arreglan de maneras distintas.
        if (asset.getStorageKey() == null || asset.getStorageKey().isBlank()) {
            log.warn("El video {} no tiene clave de almacenamiento; sin ella no hay "
                    + "archivo que abrir. Es de antes de que se guardara la clave.", assetId);
            return;
        }

        Path video = null;
        try {
            video = Files.createTempFile("picale-video-", ".mp4");
            if (!storage.descargar(asset.getStorageKey(), video)) {
                log.warn("El video {} ya no está en R2 ({}); no se le puede sacar miniatura.",
                        assetId, asset.getStorageKey());
                return;
            }

            // El mejor de varios candidatos en vez del segundo uno a ciegas:
            // esta miniatura es la que se ve en la cuadricula de Contenido, y
            // con el segundo fijo salian negras. Aqui se puede gastar el rato
            // de probar cuadros porque corre en segundo plano, cuando quien
            // subio el video ya recibio su respuesta.
            // Sin duracion: la fila del archivo no la guarda. No hace falta,
            // porque un segundo que no existe no devuelve cuadro y se descarta.
            double segundo = mejorCuadro.segundoDe(video, null);
            String cuando = String.format(java.util.Locale.US, "%.3f", segundo);
            byte[] imagen = ffmpeg.fotograma(video, cuando, ANCHO, props.getCalidad());
            if (imagen == null) {
                log.warn("ffmpeg no devolvió fotograma del video {} ({}).",
                        assetId, asset.getStorageKey());
                return;
            }

            String clave = claveDe(asset.getStorageKey());
            asset.setThumbnailUrl(storage.subirBytes(clave, imagen, "image/jpeg"));
            repository.save(asset);

            log.info("Miniatura lista para el video {} ({} bytes)", assetId, imagen.length);
        } catch (Exception ex) {
            log.warn("No se pudo sacar la miniatura del video {}: {}", assetId, ex.toString());
        } finally {
            FfmpegImagen.borrar(video);
        }
    }
}
