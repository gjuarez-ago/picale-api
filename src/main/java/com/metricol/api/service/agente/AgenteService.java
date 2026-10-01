package com.metricol.api.service.agente;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.PostSaveRequest;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.BrandService;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.PostService;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.Redactor;
import com.metricol.api.service.limits.LimitesConfigurables;
import com.metricol.api.service.publishing.FormatRulesService;

/**
 * El agente: la IA como community manager de una cuenta.
 *
 * <p>Toma cada foto que se sube con el agente encendido y la lleva, sola, hasta
 * una propuesta: la revisa contra la marca, elige las redes, escribe el texto
 * de cada una y le busca fecha. La persona solo aprueba o descarta. Nada sale
 * sin aprobar. Ver {@code docs/agente-community-manager.md}.
 *
 * <p><b>Sin transacción a propósito.</b> Cada foto pasa por dos llamadas a la
 * IA que tardan segundos; con una transacción abierta serían segundos con una
 * conexión de la base en la mano, y el pool de producción tiene cinco. Cada
 * escritura va en la suya, al final.
 *
 * <p>Primera versión: fotos, tal cual (sin diseño ni logo), en todas las redes
 * conectadas que las acepten. El diseño con IA, el logo y los videos llegan
 * después sobre esta misma cadena.
 */
@Service
public class AgenteService {

    private static final Logger log = LoggerFactory.getLogger(AgenteService.class);

    /** Cuántas fotos revisa por cuenta en cada vuelta: que una cuenta con cien no acapare. */
    static final int POR_VUELTA = 4;

    /** Con menos de esto la marca dice muy poco para descartar con criterio. */
    static final int MARCA_SUFICIENTE = 60;

    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag("es-MX"));

    private final WorkspaceRepository workspaces;
    private final MediaAssetRepository assets;
    private final PostRepository posts;
    private final SocialAccountRepository cuentas;
    private final RevisorDeMarca revisor;
    private final Redactor redactor;
    private final PostService postService;
    private final MediaService mediaService;
    private final FormatRulesService formatos;
    private final LimitesConfigurables limites;
    private final AiQuotaGuard cupoIa;

    public AgenteService(WorkspaceRepository workspaces, MediaAssetRepository assets, PostRepository posts,
            SocialAccountRepository cuentas, RevisorDeMarca revisor, Redactor redactor, PostService postService,
            MediaService mediaService, FormatRulesService formatos, LimitesConfigurables limites,
            AiQuotaGuard cupoIa) {
        this.workspaces = workspaces;
        this.assets = assets;
        this.posts = posts;
        this.cuentas = cuentas;
        this.revisor = revisor;
        this.redactor = redactor;
        this.postService = postService;
        this.mediaService = mediaService;
        this.formatos = formatos;
        this.limites = limites;
        this.cupoIa = cupoIa;
    }

    // ------------------------------------------------------------ el switch

    public record Estado(boolean activo, LocalDateTime desde, long porRevisar, long propuestas,
            long enObservacion, long descartadas, int marcaPercent) {
    }

    public Estado estado(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        long porRevisar = w.conAgente() && w.getAgenteDesde() != null
                ? assets.porRevisarDelAgente(w.getAgenteDesde())
                : 0;
        return new Estado(w.conAgente(), w.getAgenteDesde(), porRevisar, posts.propuestasDelAgente().size(),
                assets.countByAgenteEtapa(EtapaAgente.OBSERVACION), assets.countByAgenteEtapa(EtapaAgente.DESCARTADA),
                BrandService.completitud(w).percent());
    }

    /**
     * Encender o apagar. Encenderlo marca desde cuándo: solo cuenta lo que se
     * suba a partir de ahí. Apagarlo no toca nada de lo que ya propuso.
     */
    public Estado encender(UUID workspaceId, boolean activo) {
        Workspace w = workspace(workspaceId);
        if (activo && !w.conAgente()) {
            w.setAgenteDesde(LocalDateTime.now());
        }
        w.setAgenteActivo(activo);
        workspaces.save(w);
        return estado(workspaceId);
    }

    // ------------------------------------------------------------ el trabajo

    /**
     * Una vuelta del agente en una cuenta. La llama el proceso de fondo con el
     * workspace ya impuesto. Nunca lanza: una foto que falla no detiene las
     * demás, y una cuenta que falla no detiene a las otras.
     *
     * @return cuántas fotos llevó a algún lado
     */
    public int vuelta(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        if (!w.conAgente() || w.getAgenteDesde() == null) {
            return 0;
        }
        reacomodarVencidas();

        List<SocialAccount> destino = cuentasParaFotos();
        if (destino.isEmpty()) {
            // Sin redes no hay a dónde proponer: ni se gasta en revisar.
            return 0;
        }

        int hechas = 0;
        for (MediaAsset asset : assets.paraElAgente(w.getAgenteDesde(), PageRequest.of(0, POR_VUELTA))) {
            try {
                cupoIa.exigirCupo();
            } catch (RuntimeException topeDelDia) {
                log.info("El agente de {} llegó al tope de IA del día; sigue mañana.", workspaceId);
                break;
            }
            if (procesar(asset, w, destino, false)) {
                hechas++;
            }
        }
        return hechas;
    }

    /**
     * Lleva una foto a su sitio: propuesta, observación o descartada.
     *
     * @param forzar la persona ya dijo que va (desde Observación o
     *               Descartadas): no se vuelve a preguntar a la marca
     * @return si quedó en algún sitio; {@code false} = sigue pendiente
     */
    boolean procesar(MediaAsset asset, Workspace w, List<SocialAccount> destino, boolean forzar) {
        Redactor.Negocio negocio = negocio(w);
        boolean marcaCompleta = BrandService.completitud(w).percent() >= MARCA_SUFICIENTE;

        RevisorDeMarca.Revision revision = revisor.revisar(asset.getUrl(), negocio, marcaCompleta);
        if (revision == null) {
            marcar(asset, EtapaAgente.PENDIENTE, "No pude revisarla todavía; lo vuelvo a intentar en un rato.");
            return false;
        }
        if (!forzar && revision.veredicto() != RevisorDeMarca.Veredicto.VA) {
            marcar(asset, revision.veredicto() == RevisorDeMarca.Veredicto.OBSERVACION
                    ? EtapaAgente.OBSERVACION
                    : EtapaAgente.DESCARTADA, revision.motivo());
            return true;
        }

        try {
            Set<Platform> redes = destino.stream().map(SocialAccount::getPlatform)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String encargo = encargo(revision);
            Redactor.Borrador borrador = redactor.redactar(encargo,
                    revision.descripcion().isBlank() ? List.of() : List.of(revision.descripcion()),
                    redes, negocio);

            LocalDateTime fecha = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(),
                    posts.huecosTomados(LocalDateTime.now()), limites.maxPorDia());

            PostSaveRequest pedido = new PostSaveRequest();
            pedido.setCaption(texto(borrador));
            pedido.setTitulo(borrador.titulo());
            pedido.setBrief(encargo);
            pedido.setMediaUrls(List.of(asset.getUrl()));
            pedido.setFormat(PostFormat.PHOTO.name());
            pedido.setSocialAccountIds(destino.stream().map(SocialAccount::getId).toList());
            Map<String, String> porRed = new LinkedHashMap<>();
            borrador.textos().forEach((red, t) -> porRed.put(red.name(), t));
            pedido.setCaptionsPorRed(porRed);

            String motivo = motivo(forzar, revision, redes, fecha);
            postService.crearPropuesta(pedido, fecha, motivo);

            if (asset.getDescripcionIa() == null || asset.getDescripcionIa().isBlank()) {
                asset.setDescripcionIa(revision.descripcion().isBlank() ? null : revision.descripcion());
            }
            marcar(asset, EtapaAgente.PROPUESTA, motivo);
            return true;
        } catch (RuntimeException ex) {
            log.warn("El agente no pudo preparar la propuesta de {}: {}", asset.getId(), ex.toString());
            marcar(asset, EtapaAgente.PENDIENTE, "No pude preparar la publicación; lo vuelvo a intentar en un rato.");
            return false;
        }
    }

    /**
     * Las propuestas que nadie aprobó a tiempo no salen: se mueven al
     * siguiente hueco libre. Así no hay que hacer nada para que una semana sin
     * revisar no se pierda.
     */
    void reacomodarVencidas() {
        LocalDateTime limite = LocalDateTime.now().plusMinutes(30);
        for (Post p : posts.propuestasDelAgente()) {
            if (p.getFechaPropuesta() != null && p.getFechaPropuesta().isBefore(limite)) {
                List<LocalDateTime> tomados = new ArrayList<>(posts.huecosTomados(LocalDateTime.now()));
                tomados.remove(p.getFechaPropuesta());
                LocalDateTime nueva = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(), tomados,
                        limites.maxPorDia());
                p.setFechaPropuesta(nueva);
                posts.save(p);
            }
        }
    }

    // ------------------------------------------------------------ la bandeja

    /** De solo lectura y con transacción: la respuesta recorre los destinos, que son perezosos. */
    @Transactional(readOnly = true)
    public List<PostResponse> propuestas() {
        return posts.propuestasDelAgente().stream().map(postService::respuesta).toList();
    }

    public List<MediaAssetResponse> archivos(EtapaAgente etapa) {
        return assets.findByAgenteEtapaOrderByCreatedAtDesc(etapa).stream().map(mediaService::respuesta).toList();
    }

    /**
     * Aprobar: se programa en su fecha. Si la fecha ya está demasiado cerca,
     * se toma el siguiente hueco; aprobar tarde no debe publicar de golpe.
     */
    public PostResponse aprobar(UUID postId, UUID workspaceId) {
        Post post = posts.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Propuesta no encontrada."));
        LocalDateTime cuando = post.getFechaPropuesta();
        if (cuando == null || cuando.isBefore(LocalDateTime.now().plusMinutes(10))) {
            List<LocalDateTime> tomados = new ArrayList<>(posts.huecosTomados(LocalDateTime.now()));
            tomados.remove(cuando);
            cuando = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(), tomados, limites.maxPorDia());
        }
        PostResponse hecho = postService.programarPropuesta(postId, cuando, workspaceId);
        etapaDeSusFotos(post, EtapaAgente.APROBADA, null);
        return hecho;
    }

    /** Aprobar todas: las que no se pueden (sin cupo, sin página) se quedan y se dice cuántas. */
    public record Lote(int aprobadas, int pendientes, String primerMotivo) {
    }

    public Lote aprobarTodas(UUID workspaceId) {
        int ok = 0;
        int no = 0;
        String motivo = null;
        for (Post p : posts.propuestasDelAgente()) {
            try {
                aprobar(p.getId(), workspaceId);
                ok++;
            } catch (RuntimeException ex) {
                no++;
                if (motivo == null) {
                    motivo = ex.getMessage();
                }
            }
        }
        return new Lote(ok, no, motivo);
    }

    /** Descartar una propuesta: se elimina y su foto pasa a Descartadas, rescatable. */
    public void descartar(UUID postId) {
        Post post = posts.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Propuesta no encontrada."));
        postService.delete(postId);
        etapaDeSusFotos(post, EtapaAgente.DESCARTADA, "La descartaste tú.");
    }

    /**
     * La persona decide sobre una foto en Observación o Descartadas: si va, el
     * agente la prepara ya; si no, se queda descartada.
     */
    public void decidir(UUID assetId, boolean va, UUID workspaceId) {
        MediaAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Archivo no encontrado."));
        if (!va) {
            marcar(asset, EtapaAgente.DESCARTADA, "Dijiste que no va.");
            return;
        }
        List<SocialAccount> destino = cuentasParaFotos();
        if (destino.isEmpty()) {
            throw new IllegalStateException("Conecta al menos una red para que pueda proponer esta foto.");
        }
        if (!procesar(asset, workspace(workspaceId), destino, true)) {
            throw new IllegalStateException("No pude prepararla ahora; lo intento de nuevo en un rato.");
        }
    }

    // ------------------------------------------------------------ piezas

    /** Las cuentas a las que puede ir una foto: conectadas, encendidas, con página y que acepten fotos. */
    private List<SocialAccount> cuentasParaFotos() {
        return cuentas.findAll().stream()
                .filter(c -> c.getStatus() == SocialAccountStatus.CONNECTED)
                .filter(c -> !c.apagadaPorLaPersona())
                .filter(c -> !c.sinPagina())
                .filter(c -> formatos.admite(PostFormat.PHOTO, c.getPlatform()))
                .toList();
    }

    private Workspace workspace(UUID id) {
        return workspaces.findById(id).orElseThrow(() -> new ResourceNotFoundException("Espacio no encontrado."));
    }

    private static Redactor.Negocio negocio(Workspace w) {
        return new Redactor.Negocio(w.getName(), w.getGiro(), w.getCiudad(), w.getDescripcion(), w.getObjetivo(),
                MarcaDelNegocio.de(w.getBrandProfile()));
    }

    /** Lo que se le encarga a quien escribe: un objetivo, no un texto que mejorar. */
    static String encargo(RevisorDeMarca.Revision r) {
        String idea = r.idea().isBlank() ? "una publicacion para las redes del negocio con esta foto" : r.idea();
        return "Haz una publicacion para las redes del negocio con esta foto: " + idea;
    }

    private static String texto(Redactor.Borrador b) {
        if (b.guion() != null && !b.guion().isBlank()) {
            return b.guion();
        }
        return b.textos().values().stream().filter(t -> t != null && !t.isBlank()).findFirst()
                .orElse(b.titulo() == null ? "Publicacion" : b.titulo());
    }

    static String motivo(boolean forzar, RevisorDeMarca.Revision r, Set<Platform> redes, LocalDateTime fecha) {
        String porQue = forzar ? "Me dijiste que va." : "Va con tu marca: " + sinPunto(r.motivo()) + ".";
        String donde = redes.size() > 1 ? "En todas tus redes" : "En " + redes.iterator().next().getLabel();
        return porQue + " La dejé tal cual, sin diseño. " + donde + ", el " + FECHA.format(fecha)
                + ": el primer hueco libre.";
    }

    private static String sinPunto(String s) {
        String t = s == null ? "" : s.strip();
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    private void marcar(MediaAsset asset, EtapaAgente etapa, String motivo) {
        asset.setAgenteEtapa(etapa);
        asset.setAgenteMotivo(motivo == null || motivo.length() <= 500 ? motivo : motivo.substring(0, 500));
        assets.save(asset);
    }

    private void etapaDeSusFotos(Post post, EtapaAgente etapa, String motivo) {
        for (MediaAsset a : assets.findByUrlIn(post.getMediaUrls())) {
            marcar(a, etapa, motivo == null ? a.getAgenteMotivo() : motivo);
        }
    }
}
