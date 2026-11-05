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
import com.metricol.api.enums.AiOperacion;
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

    /** La cola de video está llena: no es un fallo del video, es esperar turno. */
    public static class ColaLlena extends IllegalStateException {
        public ColaLlena() {
            super("Hay varios videos editándose. Intenta de nuevo en un momento.");
        }
    }

    /**
     * Lo que se guarda en la copia para hacerla. Las palabras pueden venir
     * vacías (solo música) y la música nula (solo subtítulos).
     *
     * @param musica  la canción del catálogo; nula = sin música
     * @param volumen de la música, ya igualada a -14 LUFS (1 = completa)
     * @param logo    el logo de la marca para la esquina de arriba; nulo = sin logo
     */
    record Encargo(EstiloSubtitulos estilo, String color, List<Palabra> palabras, String musica, Double volumen,
            Double recorteInicio, Double recorteFin, String logo) {
    }

    /** El tramo que se queda de una copia recortada (segundos del original); nulo si va entera. */
    public record TramoDeCopia(double inicio, double fin) {
    }

    /** La música que lleva una copia y a qué volumen. */
    public record MusicaDeCopia(String cancion, double volumen) {
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
        return Frases.agrupar(palabras(videoId, workspaceId, true));
    }

    /**
     * Lo que se dice en el video, palabra por palabra; vacío si no tiene
     * sonido o nadie habla (para la música eso no es un error).
     *
     * @throws IllegalArgumentException si no se puede abrir o es muy largo
     */
    public List<Palabra> palabras(UUID videoId, UUID workspaceId) {
        return palabras(videoId, workspaceId, false);
    }

    /** @param exigir si no hay voz es un error que se le dice a la persona */
    private List<Palabra> palabras(UUID videoId, UUID workspaceId, boolean exigir) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> guardadas = leer(video.getTranscripcion());
        if (!guardadas.isEmpty()) {
            return guardadas;
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
            if (exigir) throw new IllegalArgumentException("Este video no tiene sonido.");
            return List.of();
        }
        List<Palabra> palabras = LecturaDeWhisper.palabras(ia.transcribirConTiempos(audio, "audio.mp3"));
        if (palabras.isEmpty() && exigir) {
            throw new IllegalArgumentException("No escuché a nadie hablando en este video.");
        }
        if (!palabras.isEmpty()) {
            video.setTranscripcion(escribir(palabras));
            assets.save(video);
        }
        return palabras;
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
        return encargar(video, workspaceId, encargo(workspaceId, estilo, color, palabras, null),
                nombreSubtitulado(video.getFileName()), null);
    }

    /**
     * La copia final de un video subido, como la pidió la cuenta: con
     * subtítulos, con música de fondo, o con los dos en una sola pasada.
     *
     * @param conSubtitulos si se le pintan (con el texto ya sacado)
     * @param musica        nula = sin música
     */
    public MediaAssetResponse pedirEdicion(UUID videoId, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            boolean conSubtitulos, MusicaDeCopia musica) {
        return pedirEdicion(videoId, workspaceId, estilo, color, conSubtitulos, musica, null);
    }

    /** @param recorte nulo = entero; si no, solo ese tramo (los subtítulos se corren con él) */
    public MediaAssetResponse pedirEdicion(UUID videoId, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            boolean conSubtitulos, MusicaDeCopia musica, RecorteAutomatico.Recorte recorte) {
        return pedirEdicion(videoId, workspaceId, estilo, color, conSubtitulos, musica, recorte, null);
    }

    /** @param logo la URL del logo para la esquina de arriba; nula = sin logo */
    public MediaAssetResponse pedirEdicion(UUID videoId, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            boolean conSubtitulos, MusicaDeCopia musica, RecorteAutomatico.Recorte recorte, String logo) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> palabras = conSubtitulos ? leer(video.getTranscripcion()) : List.of();
        if (conSubtitulos && palabras.isEmpty()) {
            throw new IllegalArgumentException("Primero saca el texto del video.");
        }
        return editar(video, workspaceId, estilo, color, palabras, musica, recorte, logo);
    }

    /**
     * La copia automática, con las palabras que se acaban de escuchar y no
     * con las que haya en la fila en este momento. El 3 nov 2026 otra tarea
     * borró la transcripción del 114023 entre la escucha y este paso; al
     * releerla vacía, el video se soltó sin subtítulos ni música aunque
     * Whisper sí lo había escuchado. Si falta en la fila, se vuelve a poner.
     *
     * @param subtitulos las palabras a pintar; vacía = sin subtítulos
     */
    public MediaAssetResponse encargarEdicion(UUID videoId, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            List<Palabra> subtitulos, MusicaDeCopia musica, RecorteAutomatico.Recorte recorte, String logo) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> palabras = subtitulos == null ? List.of() : subtitulos;
        if (!palabras.isEmpty() && leer(video.getTranscripcion()).isEmpty()
                && assets.ponerTranscripcionSiFalta(video.getId(), escribir(palabras)) > 0) {
            log.warn("La transcripción del video {} se había borrado: se volvió a poner.", video.getId());
        }
        return editar(video, workspaceId, estilo, color, palabras, musica, recorte, logo);
    }

    private MediaAssetResponse editar(MediaAsset video, UUID workspaceId, EstiloSubtitulos estilo, Color color,
            List<Palabra> palabras, MusicaDeCopia musica, RecorteAutomatico.Recorte recorte, String logo) {
        boolean conSubtitulos = !palabras.isEmpty();
        if (palabras.isEmpty() && musica == null && recorte == null && logo == null) {
            throw new IllegalArgumentException("No hay nada que ponerle a este video.");
        }
        if (recorte != null) {
            palabras = RecorteAutomatico.corridas(palabras, recorte.inicio(), recorte.fin());
        }
        Encargo base = encargo(workspaceId, estilo, color, palabras, musica);
        Encargo encargo = new Encargo(base.estilo(), base.color(), base.palabras(), base.musica(), base.volumen(),
                recorte == null ? null : recorte.inicio(), recorte == null ? null : recorte.fin(), logo);
        String nombre = conSubtitulos ? nombreSubtitulado(video.getFileName())
                : musica != null ? nombreConMusica(video.getFileName())
                : recorte == null ? nombreSubtitulado(video.getFileName()).replace("-subtitulado.mp4", "-con-logo.mp4")
                : nombreSubtitulado(video.getFileName()).replace("-subtitulado.mp4", "-recortado.mp4");
        return encargar(video, workspaceId, encargo, nombre, recorte == null ? null : recorte.nota());
    }

    private Encargo encargo(UUID workspaceId, EstiloSubtitulos estilo, Color color, List<Palabra> palabras,
            MusicaDeCopia musica) {
        EstiloSubtitulos elegido = estilo != null ? estilo
                : EstiloSubtitulos.values()[azar.nextInt(EstiloSubtitulos.values().length)];
        Color tono = color != null ? color : Color.values()[azar.nextInt(Color.values().length)];
        String hex = tono == Color.AMARILLO ? GuionAss.AMARILLO : GuionAss.resalteLegible(coloresDe(workspaceId));
        return new Encargo(elegido, hex, palabras, musica == null ? null : musica.cancion(),
                musica == null ? null : musica.volumen(), null, null, null);
    }

    private MediaAssetResponse encargar(MediaAsset video, UUID workspaceId, Encargo encargo, String nombre,
            String recorteNota) {
        long reservados = video.getSizeBytes() == null ? 1 : video.getSizeBytes();
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
                .subtitulos(escribir(encargo))
                .musicaFondo(encargo.musica() == null ? null : SubtitulosAutomaticos.MUSICA_PUESTA)
                .recorteNota(recorteNota)
                .generadaPorIa(false)
                .creadaConIa(false)
                .build());
        if (!cola.dispatch(copia.getId(), copia.getTenantId())) {
            assets.delete(copia);
            throw new ColaLlena();
        }
        log.info("Copia de {}: subtítulos {} en {} ({} palabras), música {}, logo {}", video.getId(),
                encargo.estilo(), encargo.color(), encargo.palabras().size(), encargo.musica(), encargo.logo() != null);
        return media.respuesta(copia);
    }

    /**
     * El guion ASS de una copia encargada, al tamaño real del video. Estático
     * a propósito: lo usa la cola de video, y este servicio a su vez manda a
     * esa cola; como bean sería una dependencia en círculo.
     */
    public static String guion(ObjectMapper json, MediaAsset copia, FfmpegImagen.MedidasVideo medidas) {
        Encargo encargo = encargoDe(json, copia);
        if (encargo.palabras() == null || encargo.palabras().isEmpty()) {
            return null;
        }
        return GuionAss.escribir(encargo.palabras(), encargo.estilo(), medidas.ancho(), medidas.alto(),
                encargo.color());
    }

    /** El tramo que se queda de una copia recortada; nulo si va entera. */
    public static TramoDeCopia tramo(ObjectMapper json, MediaAsset copia) {
        Encargo encargo = encargoDe(json, copia);
        return encargo.recorteInicio() == null || encargo.recorteFin() == null ? null
                : new TramoDeCopia(encargo.recorteInicio(), encargo.recorteFin());
    }

    /** La música de una copia encargada; nula si solo lleva subtítulos. */
    public static MusicaDeCopia musica(ObjectMapper json, MediaAsset copia) {
        Encargo encargo = encargoDe(json, copia);
        return encargo.musica() == null ? null
                : new MusicaDeCopia(encargo.musica(), encargo.volumen() == null ? 1 : encargo.volumen());
    }

    /** El logo de una copia encargada; nulo si va sin logo. */
    public static String logo(ObjectMapper json, MediaAsset copia) {
        return encargoDe(json, copia).logo();
    }

    private static Encargo encargoDe(ObjectMapper json, MediaAsset copia) {
        try {
            return json.readValue(copia.getSubtitulos(), Encargo.class);
        } catch (Exception ex) {
            throw new IllegalStateException("No pude leer cómo hacer la copia del video.", ex);
        }
    }

    /**
     * Si el video ya trae música o sonido de fondo: escucha las pausas entre
     * palabras (ver {@link FondoDelAudio}). Un video sin pista de sonido no
     * trae nada.
     *
     * @throws IllegalStateException si no pudo escucharlo; quien llama reintenta
     */
    public boolean yaTraeMusica(UUID videoId, UUID workspaceId, List<Palabra> palabras) {
        MediaAsset video = videoDe(videoId, workspaceId);
        double[] niveles = ffmpeg.nivelesDeAudio(video.getUrl(), MAX_SEGUNDOS);
        if (niveles == null) {
            throw new IllegalStateException("No pude escuchar el video.");
        }
        return niveles.length > 0 && FondoDelAudio.yaTraeFondo(niveles, palabras);
    }

    /** Lo que dura el video, para cortar la música justo ahí. */
    public double segundos(UUID videoId, UUID workspaceId) {
        FfmpegImagen.MedidasVideo medidas = ffmpeg.medirVideo(videoDe(videoId, workspaceId).getUrl());
        if (medidas == null) {
            throw new IllegalStateException("No pude abrir este video.");
        }
        return medidas.segundos();
    }

    private static final String SISTEMA_PREVIOS = """
            Miras cuadros de un video tomados mientras alguien habla. Dices si el video YA TRAE \
            SUBTITULOS PEGADOS: texto que transcribe lo que se dice en ese momento, normalmente una o \
            dos lineas abajo o al centro, que cambia de un cuadro a otro. NO cuentan como subtitulos: \
            logotipos, titulos o rotulos fijos, letreros del lugar, textos de un diseno o grafica, \
            nombres de la persona, marcas de agua. Contestas SOLO SI o NO.""";

    /**
     * Si el video ya trae subtítulos pegados (mucha gente los pone en CapCut
     * antes de subir): encima de esos, los nuestros salían dobles. Mira tres
     * cuadros en momentos en que se habla, así que va después de {@link #texto}.
     * Probado con videos reales: acierta con subtítulos chicos, grandes y de
     * colores, y no confunde los textos de un diseño.
     *
     * @throws IllegalStateException si no pudo mirar; quien llama reintenta
     */
    public boolean yaTraeSubtitulos(UUID videoId, UUID workspaceId) {
        MediaAsset video = videoDe(videoId, workspaceId);
        List<Palabra> palabras = leer(video.getTranscripcion());
        if (palabras.isEmpty()) {
            return false;
        }
        List<String> cuadros = new java.util.ArrayList<>();
        for (double parte : new double[] { 0.25, 0.5, 0.75 }) {
            Palabra p = palabras.get(Math.min(palabras.size() - 1, (int) (palabras.size() * parte)));
            byte[] jpg = ffmpeg.cuadro(video.getUrl(), (p.inicio() + p.fin()) / 2, 720, 5);
            if (jpg != null) {
                cuadros.add("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpg));
            }
        }
        if (cuadros.isEmpty()) {
            throw new IllegalStateException("No pude ver los cuadros del video.");
        }
        String respuesta = ia.describeImages(AiOperacion.SUBTITULOS_PREVIOS, SISTEMA_PREVIOS,
                "Tres cuadros del mismo video, en orden. Ya trae subtitulos pegados?", cuadros);
        return respuesta != null && respuesta.strip().toUpperCase(java.util.Locale.ROOT).startsWith("SI");
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

    static String nombreConMusica(String original) {
        return nombreSubtitulado(original).replace("-subtitulado.mp4", "-con-musica.mp4");
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

    /**
     * Si una copia lleva subtítulos pintados (una de solo música no). Se lee
     * del JSON tal como lo escribe Jackson, sin espacios: así la biblioteca no
     * tiene que interpretar cada copia para ponerle su etiqueta.
     */
    public static boolean pintaSubtitulos(MediaAsset copia) {
        return esDeSubtitulos(copia) && !copia.getSubtitulos().contains("\"palabras\":[]");
    }

    /** Si una copia lleva el logo de la marca; igual que arriba, sin interpretar el JSON. */
    public static boolean llevaLogo(MediaAsset copia) {
        return esDeSubtitulos(copia) && copia.getSubtitulos().contains("\"logo\":\"");
    }

    /** Para que la cola sepa si una copia es de subtítulos o un recorte. */
    public static boolean esDeSubtitulos(MediaAsset copia) {
        return copia.getSubtitulos() != null && !copia.getSubtitulos().isBlank();
    }
}
