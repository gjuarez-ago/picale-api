package com.metricol.api.service.media.subtitulos;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.ai.RevisorDeLogo;
import com.metricol.api.service.reels.FfmpegReelRenderer;
import com.metricol.api.service.reels.MusicaDeReels;
import com.metricol.api.service.reels.ReelMusicCatalog;

import jakarta.annotation.PreDestroy;

/**
 * Subtítulos automáticos y música de fondo: todo video que se sube a una
 * cuenta que los tiene encendidos sale con ellos, sin que nadie toque un
 * botón. Si lleva los dos, se ponen en una sola copia.
 *
 * <p>La música se pone bajita si en el video alguien habla, completa si no, y
 * nada si el video ya trae música o sonido de fondo (ver {@link FondoDelAudio}).
 * Y si sobra algo al principio o al final, se recorta cuando el revisor da 90%
 * o más de que queda bien (ver {@link RecorteAutomatico}); todo en la misma copia.
 *
 * <p>El recorrido de un video original:
 * <ol>
 * <li>Al subirse queda {@link #ESPERANDO} (lo marca {@code MediaService}). El
 * agente no lo toma todavía: si lo tomara, propondría el original.</li>
 * <li>Este proceso saca el texto y encarga la copia con el estilo de la cuenta
 * (o uno al azar): queda {@link #HACIENDO}.</li>
 * <li>Cuando la cola de video termina la copia, queda {@link #LISTO} y el
 * agente lo toma; al preparar la publicación usa la copia con subtítulos.</li>
 * </ol>
 *
 * <p>Nunca se queda atorado: si nadie habla, si es muy largo, o si falla tres
 * veces, queda {@link #SIN_SUBTITULOS} y el agente sigue con el original.
 * Un video sin subtítulos es mejor que un video que nunca sale.
 */
@Service
public class SubtitulosAutomaticos {

    private static final Logger log = LoggerFactory.getLogger(SubtitulosAutomaticos.class);

    public static final String ESPERANDO = "ESPERANDO";
    public static final String HACIENDO = "HACIENDO";
    public static final String LISTO = "LISTO";
    public static final String SIN_SUBTITULOS = "SIN_SUBTITULOS";
    /** Ya traía subtítulos pegados: se suelta tal cual, sin ponerle otros encima. */
    public static final String YA_TENIA = "YA_TENIA";
    /** La copia lleva música de fondo (en {@code musicaFondo} del original). */
    public static final String MUSICA_PUESTA = "PUESTA";

    static final int MAX_INTENTOS = 3;

    private final MediaAssetRepository assets;
    private final WorkspaceRepository workspaces;
    private final SubtitulosService subtitulos;
    private final MusicaDeReels musicaDeCuenta;
    private final ReelMusicCatalog catalogo;
    private final RecorteAutomatico recortes;
    private final RevisorDeLogo revisorDeLogo;
    /** Uno a la vez: transcribir y encargar es corto, pero no debe correr dos veces el mismo video. */
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "subtitulos-auto");
        t.setDaemon(true);
        return t;
    });
    private final Set<UUID> enCurso = ConcurrentHashMap.newKeySet();

    public SubtitulosAutomaticos(MediaAssetRepository assets, WorkspaceRepository workspaces,
            SubtitulosService subtitulos, MusicaDeReels musicaDeCuenta, ReelMusicCatalog catalogo,
            RecorteAutomatico recortes, RevisorDeLogo revisorDeLogo) {
        this.assets = assets;
        this.workspaces = workspaces;
        this.subtitulos = subtitulos;
        this.musicaDeCuenta = musicaDeCuenta;
        this.catalogo = catalogo;
        this.recortes = recortes;
        this.revisorDeLogo = revisorDeLogo;
    }

    /** El logo que se puede pegar, o nulo (sin logo, o es una foto). La primera vez lo revisa. */
    private String logoUsable(Workspace espacio) {
        return revisorDeLogo == null ? espacio.getLogoUrl() : revisorDeLogo.usable(espacio);
    }

    /**
     * Los ajustes de la cuenta, para el panel.
     *
     * @param estilo   nulo = uno al azar en cada video
     * @param color    nulo = al azar
     * @param recortes recorte automático de lo que sobra al principio y al final;
     *                 al guardar, nulo = dejarlo como estaba
     * @param logo     el logo de la marca arriba en los videos; al guardar, nulo = como estaba
     * @param hayLogo  solo de lectura: si la marca tiene un logo que se puede pegar
     *                 (no lo tiene, o el "logo" es una foto de una persona o un lugar)
     */
    public record Ajustes(boolean activo, EstiloSubtitulos estilo, SubtitulosService.Color color, Boolean recortes,
            Boolean logo, Boolean hayLogo) {

        public Ajustes(boolean activo, EstiloSubtitulos estilo, SubtitulosService.Color color, Boolean recortes) {
            this(activo, estilo, color, recortes, null, null);
        }
    }

    public Ajustes ajustes(UUID workspaceId) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new com.metricol.api.exception.ResourceNotFoundException("Ese negocio no existe."));
        return new Ajustes(Boolean.TRUE.equals(espacio.getSubtitulosAuto()), estiloDe(espacio), colorDe(espacio),
                !Boolean.FALSE.equals(espacio.getRecorteAuto()), Boolean.TRUE.equals(espacio.getLogoVideos()),
                logoUsable(espacio) != null);
    }

    public Ajustes guardar(UUID workspaceId, Ajustes nuevos) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new com.metricol.api.exception.ResourceNotFoundException("Ese negocio no existe."));
        espacio.setSubtitulosAuto(nuevos.activo());
        espacio.setSubtitulosEstilo(nuevos.estilo() == null ? null : nuevos.estilo().name());
        espacio.setSubtitulosColor(nuevos.color() == null ? null : nuevos.color().name());
        if (nuevos.recortes() != null) {
            espacio.setRecorteAuto(nuevos.recortes());
        }
        if (nuevos.logo() != null) {
            espacio.setLogoVideos(nuevos.logo());
        }
        workspaces.save(espacio);
        return ajustes(workspaceId);
    }

    /**
     * Cada 30 segundos, lo que espera. El trabajo va a un hilo propio: el
     * planificador de Spring tiene uno solo para todas las tareas, y
     * transcribir ahí frenaba la cola de publicaciones mientras tanto.
     */
    @Scheduled(initialDelayString = "${app.subtitulos.vuelta-inicial-ms:40000}",
            fixedDelayString = "${app.subtitulos.vuelta-ms:30000}")
    public void vuelta() {
        for (Object[] fila : assets.esperandoSubtitulos(10)) {
            encargar(UUID.fromString(String.valueOf(fila[0])), String.valueOf(fila[1]));
        }
        // Un video "haciendo" cuya copia ya no está en la cola (se borró, o
        // se cayó el servidor antes de crearla) se suelta: nunca se queda atorado.
        for (Object[] fila : assets.haciendoSinCopiaEnCurso(10)) {
            encargar(UUID.fromString(String.valueOf(fila[0])), String.valueOf(fila[1]));
        }
    }

    private void encargar(UUID id, String tenant) {
        if (!enCurso.add(id)) return;
        try {
            hilo.execute(() -> {
                try {
                    TenantIdentifierResolver.comoTenant(tenant, () -> procesar(id));
                } catch (Exception ex) {
                    log.warn("Subtítulos automáticos del video {}: {}", id, ex.toString());
                } finally {
                    enCurso.remove(id);
                }
            });
        } catch (RuntimeException ex) {
            enCurso.remove(id);
        }
    }

    @PreDestroy
    void apagar() {
        hilo.shutdownNow();
    }

    /**
     * Un video que espera su copia: sacar el texto, decidir qué lleva
     * (subtítulos, música o las dos) y encargarla.
     */
    void procesar(UUID id) {
        MediaAsset video = assets.findById(id).orElse(null);
        if (video == null) return;
        if (HACIENDO.equals(video.getSubtitulosEstado())) {
            soltarSiNoHayCopia(video);
            return;
        }
        if (!ESPERANDO.equals(video.getSubtitulosEstado())) return;
        UUID workspaceId = UUID.fromString(video.getTenantId());
        Workspace espacio = workspaces.findById(workspaceId).orElse(null);
        boolean conSubtitulos = espacio != null && Boolean.TRUE.equals(espacio.getSubtitulosAuto());
        boolean conMusica = espacio != null && !Boolean.FALSE.equals(espacio.getMusicaVideos());
        // Un recorte hecho a mano no se vuelve a recortar: ya lo decidió la persona.
        boolean conRecorte = espacio != null && !Boolean.FALSE.equals(espacio.getRecorteAuto())
                && video.getTrimEndSeconds() == null;
        String logo = espacio != null && Boolean.TRUE.equals(espacio.getLogoVideos()) ? logoUsable(espacio) : null;
        if (!conSubtitulos && !conMusica && !conRecorte && logo == null) {
            // Los apagaron mientras esperaba: se suelta tal cual.
            guardar(video, null, video.getSubtitulosIntentos());
            return;
        }
        int intentos = (video.getSubtitulosIntentos() == null ? 0 : video.getSubtitulosIntentos()) + 1;
        try {
            List<Palabra> palabras = subtitulos.palabras(id, workspaceId);
            boolean yaTenia = false;
            if (conSubtitulos && !palabras.isEmpty()) {
                yaTenia = subtitulos.yaTraeSubtitulos(id, workspaceId);
                if (yaTenia) log.info("Video {} ya traía subtítulos: no se le ponen otros.", id);
            }
            boolean ponerSubtitulos = conSubtitulos && !palabras.isEmpty() && !yaTenia;
            SubtitulosService.MusicaDeCopia cancion = null;
            String fondo = null;
            if (conMusica) {
                if (subtitulos.yaTraeMusica(id, workspaceId, palabras)) {
                    log.info("Video {} ya trae música o sonido de fondo: se deja así.", id);
                    fondo = YA_TENIA;
                } else {
                    cancion = cancion(espacio, id, !palabras.isEmpty());
                    fondo = cancion == null ? null : MUSICA_PUESTA;
                }
            }
            // Solo con voz: sin palabras no hay cómo saber qué sobra.
            RecorteAutomatico.Recorte recorte = conRecorte && !palabras.isEmpty()
                    ? recortes.decidir(id, palabras, subtitulos.segundos(id, workspaceId))
                    : null;
            if (!ponerSubtitulos && cancion == null && recorte == null && logo == null) {
                guardar(id, yaTenia ? YA_TENIA : SIN_SUBTITULOS, intentos, fondo);
                return;
            }
            // "Haciendo" ANTES de encargar la copia: si la cola la termina (o
            // la tira) en un instante, su aviso llega después de esta marca y
            // no al revés. Al revés, esta marca pisaba el "listo" y el agente
            // no tomaba nunca el video.
            guardar(id, HACIENDO, intentos, fondo);
            // Las palabras de aquí, no las de la fila: si otra tarea la pisó
            // mientras tanto, el video igual sale con sus subtítulos.
            subtitulos.encargarEdicion(id, workspaceId, estiloDe(espacio), colorDe(espacio),
                    ponerSubtitulos ? palabras : List.of(), cancion, recorte, logo);
        } catch (IllegalArgumentException ex) {
            // Muy largo, no se abre: no es un fallo, no hay qué poner.
            log.info("Video {} sin edición automática: {}", id, ex.getMessage());
            guardar(id, SIN_SUBTITULOS, intentos, null);
        } catch (SubtitulosService.ColaLlena ex) {
            // Muchos videos a la vez: espera su turno sin gastar un intento.
            guardar(id, ESPERANDO, intentos - 1, null);
        } catch (Exception ex) {
            log.warn("Edición automática del video {} (intento {}): {}", id, intentos, ex.toString());
            guardar(id, intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO, intentos, null);
        }
    }

    /**
     * La canción del video: el ambiente de la cuenta (o su giro), la misma para
     * el mismo video. Con voz, bajita para que se entienda; sin voz, completa.
     */
    private SubtitulosService.MusicaDeCopia cancion(Workspace espacio, UUID videoId, boolean conVoz) {
        String ambiente = musicaDeCuenta.ajustesDe(espacio).ambiente();
        ReelMusicCatalog.Selection elegida = catalogo.select(espacio.getGiro(), null, null, conVoz,
                videoId + (ambiente == null ? "" : "|" + ambiente), ambiente);
        return elegida == null ? null : new SubtitulosService.MusicaDeCopia(elegida.track().id(),
                conVoz ? FfmpegReelRenderer.MUSICA_QUE_SE_AGACHA : FfmpegReelRenderer.MUSICA_SOLA);
    }

    private void soltarSiNoHayCopia(MediaAsset video) {
        if (!assets.copiasConSubtitulos(video.getId()).isEmpty()) {
            guardar(video, LISTO, video.getSubtitulosIntentos());
            return;
        }
        int intentos = video.getSubtitulosIntentos() == null ? 0 : video.getSubtitulosIntentos();
        guardar(video, intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO, intentos);
    }

    /**
     * La cola de video terminó una copia con subtítulos. Si el original la
     * estaba esperando, ya se suelta: con copia, o sin ella si falló tres
     * veces. Una copia pedida a mano no le cambia nada a un video que no la
     * esperaba (ni lo devuelve a la fila de los automáticos si falla).
     */
    public static void copiaTerminada(MediaAssetRepository assets, UUID originalId, boolean bien) {
        MediaAsset original = assets.findById(originalId).orElse(null);
        if (original == null || !HACIENDO.equals(original.getSubtitulosEstado())) return;
        int intentos = original.getSubtitulosIntentos() == null ? 0 : original.getSubtitulosIntentos();
        original.setSubtitulosEstado(bien ? LISTO : (intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO));
        assets.save(original);
    }

    /** Relee la fila antes de guardar: palabras() guarda la transcripción en ella y no hay que pisarla. */
    private void guardar(UUID id, String estado, Integer intentos, String fondo) {
        assets.findById(id).ifPresent(fresco -> {
            fresco.setMusicaFondo(fondo);
            guardar(fresco, estado, intentos);
        });
    }

    private void guardar(MediaAsset video, String estado, Integer intentos) {
        video.setSubtitulosEstado(estado);
        video.setSubtitulosIntentos(intentos);
        assets.save(video);
    }

    static EstiloSubtitulos estiloDe(Workspace espacio) {
        try {
            return espacio.getSubtitulosEstilo() == null ? null : EstiloSubtitulos.valueOf(espacio.getSubtitulosEstilo());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static SubtitulosService.Color colorDe(Workspace espacio) {
        try {
            return espacio.getSubtitulosColor() == null ? null : SubtitulosService.Color.valueOf(espacio.getSubtitulosColor());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
