package com.metricol.api.service.media.subtitulos;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.VideoTrimDispatcher;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

/**
 * Subtítulos con estilo pintados en un video, en dos pasos.
 *
 * <ol>
 * <li><b>El texto.</b> Se saca lo que se dice con el segundo de cada palabra
 * y se le enseña a la persona por frases, para que corrija lo que la IA oyó
 * mal ("sat" por "SAT", un nombre). Se guarda en el video: corregir o cambiar
 * de estilo no vuelve a cobrar la transcripción.</li>
 * <li><b>El video.</b> Con el texto ya corregido se reserva una copia y se
 * pinta en la cola de video, la misma de los recortes: un solo ffmpeg pesado
 * a la vez. El original nunca se toca.</li>
 * </ol>
 *
 * <p>Si nadie elige estilo ni color, se sortean (pedido de Gabriel, 30 oct
 * 2026: "me gustan todos, mételo aleatoriamente"). Así dos videos seguidos de
 * la misma cuenta no se ven idénticos.
 */
@Service
public class SubtitulosService {

    private static final Logger log = LoggerFactory.getLogger(SubtitulosService.class);

    /** Más largo ya no es un reel, y el audio pasaría del tope de la transcripción. */
    static final int MAX_SEGUNDOS = 600;

    /** El color de los subtítulos: el de la marca o el amarillo de TikTok. */
    public enum Color { MARCA, AMARILLO }

    /** Lo que se guarda en la copia para pintarla. */
    record Encargo(EstiloSubtitulos estilo, String color, List<Palabra> palabras) {
    }

    private final MediaAssetRepository assets;
    private final WorkspaceRepository workspaces;
    private final FfmpegImagen ffmpeg;
    private final OpenAiClient ia;
    private final R2StorageService storage;
    private final StorageQuotaService cuota;
    private final VideoTrimDispatcher cola;
    private final MediaService media;
    private final ObjectMapper json;
    Random azar = new Random();

    public SubtitulosService(MediaAssetRepository assets, WorkspaceRepository workspaces, FfmpegImagen ffmpeg,
            OpenAiClient ia, R2StorageService storage, StorageQuotaService cuota, VideoTrimDispatcher cola,
            MediaService media, ObjectMapper json) {
        this.assets = assets;
        this.workspaces = workspaces;
        this.ffmpeg = ffmpeg;
        this.ia = ia;
        this.storage = storage;
        this.cuota = cuota;
        this.cola = cola;
        this.media = media;
        this.json = json;
    }

    /** Lo que se dice en el video, por frases. La segunda vez sale de lo guardado. */
    public List<Frase> texto(UUID videoId, UUID workspaceId) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> guardadas = leer(video.getTranscripcion());
        if (!guardadas.isEmpty()) {
            return Frases.agrupar(guardadas);
        }
        FfmpegImagen.MedidasVideo medidas = ffmpeg.medirVideo(video.getUrl());
        if (medidas == null) {
            throw new IllegalArgumentException("No pude abrir este video.");
        }
        if (medidas.segundos() > MAX_SEGUNDOS + 1) {
            throw new IllegalArgumentException("Los subtítulos son para videos de hasta 10 minutos.");
        }
        byte[] audio = ffmpeg.audio(video.getUrl(), (int) Math.ceil(Math.min(medidas.segundos(), MAX_SEGUNDOS)));
        if (audio == null) {
            throw new IllegalArgumentException("Este video no tiene sonido.");
        }
        List<Palabra> palabras = LecturaDeWhisper.palabras(ia.transcribirConTiempos(audio, "audio.mp3"));
        if (palabras.isEmpty()) {
            throw new IllegalArgumentException("No escuché a nadie hablando en este video.");
        }
        video.setTranscripcion(escribir(palabras));
        assets.save(video);
        return Frases.agrupar(palabras);
    }

    /**
     * Reserva la copia con subtítulos y la manda a pintar; vuelve de inmediato
     * con la copia en "procesando" (se consulta igual que un recorte).
     *
     * @param estilo nulo = al azar
     * @param color  nulo = al azar
     * @param frases el texto corregido; nulo = el que se sacó tal cual
     */
    public MediaAssetResponse pedir(UUID videoId, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            List<Frase> frases) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> palabras = frases == null ? leer(video.getTranscripcion()) : Frases.palabras(frases);
        if (palabras.isEmpty()) {
            throw new IllegalArgumentException(frases == null
                    ? "Primero saca el texto del video."
                    : "No quedó texto para poner. Escribe al menos una frase.");
        }
        EstiloSubtitulos elegido = estilo != null ? estilo
                : EstiloSubtitulos.values()[azar.nextInt(EstiloSubtitulos.values().length)];
        Color tono = color != null ? color : Color.values()[azar.nextInt(Color.values().length)];
        String hex = tono == Color.AMARILLO ? GuionAss.AMARILLO : GuionAss.resalteLegible(coloresDe(workspaceId));

        long reservados = video.getSizeBytes() == null ? 1 : video.getSizeBytes();
        String nombre = nombreSubtitulado(video.getFileName());
        cuota.verificar(reservados, nombre);
        String key = storage.claveNueva(workspaceId, nombre, "video/mp4");
        MediaAsset copia = assets.save(MediaAsset.builder()
                .fileName(nombre)
                .storageKey(key)
                .url(storage.urlDe(key))
                .type(MediaType.VIDEO)
                .sizeBytes(reservados)
                .contentType("video/mp4")
                .status(MediaAssetStatus.PROCESSING)
                .sourceAssetId(video.getId())
                .subtitulos(escribir(new Encargo(elegido, hex, palabras)))
                .generadaPorIa(false)
                .creadaConIa(false)
                .build());
        if (!cola.dispatch(copia.getId(), copia.getTenantId())) {
            assets.delete(copia);
            throw new IllegalStateException("Hay varios videos editándose. Intenta de nuevo en un momento.");
        }
        log.info("Subtítulos {} en {} para el video {}", elegido, hex, video.getId());
        return media.respuesta(copia);
    }

    /**
     * El guion ASS de una copia encargada, al tamaño real del video. Estático
     * a propósito: lo usa la cola de video, y este servicio a su vez manda a
     * esa cola; como bean sería una dependencia en círculo.
     */
    public static String guion(ObjectMapper json, MediaAsset copia, FfmpegImagen.MedidasVideo medidas) {
        Encargo encargo;
        try {
            encargo = json.readValue(copia.getSubtitulos(), Encargo.class);
        } catch (Exception ex) {
            throw new IllegalStateException("No pude leer cómo pintar los subtítulos.", ex);
        }
        return GuionAss.escribir(encargo.palabras(), encargo.estilo(), medidas.ancho(), medidas.alto(),
                encargo.color());
    }

    private MediaAsset videoDe(UUID id, UUID workspaceId) {
        MediaAsset video = assets.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Video no encontrado."));
        if (!workspaceId.toString().equals(video.getTenantId())) {
            throw new ResourceNotFoundException("Ese video no existe en este negocio.");
        }
        if (video.getType() != MediaType.VIDEO || video.getStatus() != MediaAssetStatus.READY) {
            throw new IllegalArgumentException("Ese archivo no es un video disponible.");
        }
        return video;
    }

    /** Los colores de la marca: los del logotipo y, detrás, el que se eligió para la cuenta. */
    private String coloresDe(UUID workspaceId) {
        Workspace espacio = workspaces.findById(workspaceId).orElse(null);
        if (espacio == null) return null;
        String logo = espacio.getMarcaColores() == null ? "" : espacio.getMarcaColores();
        String cuenta = espacio.getColor() == null ? "" : espacio.getColor();
        return logo + "," + cuenta;
    }

    static String nombreSubtitulado(String original) {
        String nombre = original == null || original.isBlank() ? "video" : original;
        int punto = nombre.lastIndexOf('.');
        if (punto > 0) nombre = nombre.substring(0, punto);
        return nombre + "-subtitulado.mp4";
    }

    private List<Palabra> leer(String texto) {
        if (texto == null || texto.isBlank()) return List.of();
        try {
            return json.readValue(texto, new TypeReference<List<Palabra>>() { });
        } catch (Exception ex) {
            log.warn("Transcripción guardada ilegible: {}", ex.toString());
            return List.of();
        }
    }

    private String escribir(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (Exception ex) {
            throw new IllegalStateException("No pude guardar los subtítulos.", ex);
        }
    }

    /** Para que la cola sepa si una copia es de subtítulos o un recorte. */
    public static boolean esDeSubtitulos(MediaAsset copia) {
        return copia.getSubtitulos() != null && !copia.getSubtitulos().isBlank();
    }
}
