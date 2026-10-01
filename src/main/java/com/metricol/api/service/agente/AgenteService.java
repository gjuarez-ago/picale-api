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
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.campaign.CampaignImageService;
import com.metricol.api.service.campaign.LogoSobreFoto;
import com.metricol.api.service.media.HuellaDeImagen;
import com.metricol.api.service.media.RetoqueDeFoto;
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
    private final LogoSobreFoto logo;
    private final HuellaDeImagen huellas;
    private final CampaignImageService generador;
    private final CreditService creditos;
    private final RetoqueDeFoto retoque;

    public AgenteService(WorkspaceRepository workspaces, MediaAssetRepository assets, PostRepository posts,
            SocialAccountRepository cuentas, RevisorDeMarca revisor, Redactor redactor, PostService postService,
            MediaService mediaService, FormatRulesService formatos, LimitesConfigurables limites,
            AiQuotaGuard cupoIa, LogoSobreFoto logo, HuellaDeImagen huellas, CampaignImageService generador,
            CreditService creditos, RetoqueDeFoto retoque) {
        this.retoque = retoque;
        this.logo = logo;
        this.huellas = huellas;
        this.generador = generador;
        this.creditos = creditos;
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

    /**
     * @param dias      días en que publica, 1 = lunes … 7 = domingo
     * @param programadas lo que el agente ya dejó programado y todavía no sale: lo que congela la pausa
     */
    public record Estado(boolean activo, LocalDateTime desde, long porRevisar, long propuestas,
            long enObservacion, long descartadas, int marcaPercent, List<Integer> dias, int horaDesde,
            int horaHasta, long programadas) {
    }

    public Estado estado(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        long porRevisar = w.conAgente() && w.getAgenteDesde() != null
                ? assets.porRevisarDelAgente(w.getAgenteDesde())
                : 0;
        CalendarioDelAgente.Horario h = horario(w);
        return new Estado(w.conAgente(), w.getAgenteDesde(), porRevisar, posts.propuestasDelAgente().size(),
                assets.countByAgenteEtapa(EtapaAgente.OBSERVACION), assets.countByAgenteEtapa(EtapaAgente.DESCARTADA),
                BrandService.completitud(w).percent(),
                h.dias().stream().map(java.time.DayOfWeek::getValue).sorted().toList(), h.desde(), h.hasta(),
                posts.programadasDelAgente().size());
    }

    /**
     * Una cuenta en el resumen del community manager.
     *
     * @param actual si es la cuenta en la que está ahora
     */
    public record Cuenta(UUID id, String nombre, String logoUrl, String color, boolean actual, boolean agenteActivo,
            long propuestas, long enObservacion, long porRevisar) {
    }

    /**
     * Cuánto espera en cada cuenta de la persona: la vista del community
     * manager que lleva varias. Cada una se lee con su propio workspace
     * impuesto, como si se entrara a ella.
     */
    public List<Cuenta> resumen(List<com.metricol.api.models.response.MiWorkspaceResponse> mias) {
        List<Cuenta> cuentas = new ArrayList<>();
        for (var m : mias) {
            if (m.archivado()) {
                continue;
            }
            com.metricol.api.config.TenantIdentifierResolver.comoTenant(m.id().toString(), () -> {
                Estado e = estado(m.id());
                cuentas.add(new Cuenta(m.id(), m.name(), m.logoUrl(), m.color(), m.activo(), e.activo(),
                        e.propuestas(), e.enObservacion(), e.porRevisar()));
            });
        }
        // Lo que más espera, primero: es lo que el community manager va a atender.
        cuentas.sort(java.util.Comparator.comparingLong((Cuenta c) -> -(c.propuestas() + c.enObservacion())));
        return cuentas;
    }

    /** El horario del negocio: qué días y entre qué horas propone publicar. */
    public Estado guardarHorario(UUID workspaceId, List<Integer> dias, int desde, int hasta) {
        if (dias == null || dias.isEmpty()) {
            throw new IllegalArgumentException("Elige al menos un día para publicar.");
        }
        if (desde < 0 || hasta > 24 || hasta <= desde) {
            throw new IllegalArgumentException("La hora de fin tiene que ser después de la de inicio.");
        }
        Workspace w = workspace(workspaceId);
        w.setAgenteDias(dias.stream().filter(d -> d >= 1 && d <= 7).distinct().sorted()
                .map(String::valueOf).collect(Collectors.joining(",")));
        w.setAgenteHoraDesde(desde);
        w.setAgenteHoraHasta(hasta);
        workspaces.save(w);
        return estado(workspaceId);
    }

    /**
     * Pausa de emergencia: apaga el agente y devuelve a "Por aprobar" todo lo
     * que había dejado programado y todavía no sale. Para una crisis, un luto
     * o un día en que publicar una promoción queda mal. Nada se pierde: se
     * vuelve a aprobar cuando pase.
     *
     * @return cuántas se congelaron
     */
    public int pausar(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        w.setAgenteActivo(false);
        workspaces.save(w);
        int congeladas = 0;
        for (Post p : posts.programadasDelAgente()) {
            try {
                postService.cancel(p.getId());
                etapaDeSusFotos(p, EtapaAgente.PROPUESTA, null);
                congeladas++;
            } catch (RuntimeException yaSaliendo) {
                // La que ya está saliendo no se puede parar: sale.
                log.info("La pausa no alcanzó a {}: {}", p.getId(), yaSaliendo.getMessage());
            }
        }
        return congeladas;
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
        reacomodarVencidas(w);

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
        // Repetidas, antes de gastar en la IA: la misma toma subida dos veces no
        // son dos publicaciones. Se queda la que llegó primero. Si la persona
        // la rescata (forzar), va aunque se parezca.
        if (!forzar) {
            MediaAsset igual = repetidaDe(asset);
            if (igual != null) {
                marcar(asset, EtapaAgente.DESCARTADA, "Casi igual a «" + igual.getFileName()
                        + "», que ya trabajé: me quedé con esa.");
                return true;
            }
        }

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

        // La IA ya calificó la foto; el decisor resuelve qué necesita.
        DecisorDelAgente.Decision decision = DecisorDelAgente.decidir(revision.diagnostico(),
                new DecisorDelAgente.Contexto(disenosDisponibles(w), ajusteDeDiseno(w)));
        if (decision.tratamiento() == DecisorDelAgente.Tratamiento.OBSERVACION) {
            marcar(asset, EtapaAgente.OBSERVACION, decision.explicacion());
            return true;
        }

        try {
            Set<Platform> redes = destino.stream().map(SocialAccount::getPlatform)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String encargo = encargo(revision);
            String porQue = forzar ? "Me dijiste que va." : "Va con tu marca: " + sinPunto(revision.motivo()) + ".";

            if (decision.tratamiento() == DecisorDelAgente.Tratamiento.DISENO) {
                if (proponerDiseno(asset, w, destino, redes, encargo, decision, porQue)) {
                    marcar(asset, EtapaAgente.PROPUESTA, "Le hice diseño con IA.");
                    return true;
                }
                // El diseño no salió (y no se cobró): va tal cual, y se dice.
                decision = new DecisorDelAgente.Decision(DecisorDelAgente.Tratamiento.TAL_CUAL, decision.logo(),
                        decision.prioridad(), conPaso(decision.pasos(), "El diseño no salió; va tal cual."));
            }

            Redactor.Borrador borrador = redactor.redactar(encargo,
                    revision.descripcion().isBlank() ? List.of() : List.of(revision.descripcion()),
                    redes, negocio);

            LocalDateTime fecha = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(),
                    posts.huecosTomados(LocalDateTime.now()), limites.maxPorDia(), horario(w));

            // Retoque y logo sobre copias: la original no se toca. Lo que no se
            // pueda (ffmpeg, un logo ilegible) se salta sin perder la propuesta.
            MediaAsset base = asset;
            List<String> pasos = new ArrayList<>(decision.pasos());
            if (decision.tratamiento() == DecisorDelAgente.Tratamiento.RETOQUE) {
                MediaAsset retocada = retoque.retocar(asset, w.getId());
                if (retocada != null) {
                    base = retocada;
                } else {
                    pasos.add("El retoque no salió; va como vino.");
                }
            }
            String publicar = base.getUrl();
            if (decision.logo()) {
                String sellada = logo.sellar(base, w.getLogoUrl(), w.getId());
                if (sellada != null) {
                    publicar = sellada;
                } else {
                    pasos.add(w.getLogoUrl() == null ? "No hay un logo guardado en tu marca." : "El logo no se pudo pegar.");
                }
            }

            PostSaveRequest pedido = new PostSaveRequest();
            pedido.setCaption(texto(borrador));
            pedido.setTitulo(borrador.titulo());
            pedido.setBrief(encargo);
            pedido.setMediaUrls(List.of(publicar));
            pedido.setFormat(PostFormat.PHOTO.name());
            pedido.setSocialAccountIds(destino.stream().map(SocialAccount::getId).toList());
            Map<String, String> porRed = new LinkedHashMap<>();
            borrador.textos().forEach((red, t) -> porRed.put(red.name(), t));
            pedido.setCaptionsPorRed(porRed);

            String motivo = porQue + " " + String.join(" ", pasos) + " " + cuandoYDonde(redes, fecha);
            postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(), decision.tratamiento().name());

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
     * Una promoción diseñada por la IA: una propuesta por cada versión (una por
     * proporción, cada una con sus redes), todas en la misma fecha porque son
     * la misma publicación. Cuesta 1 crédito.
     *
     * @return si salió; {@code false} = va tal cual, sin gastar
     */
    private boolean proponerDiseno(MediaAsset asset, Workspace w, List<SocialAccount> destino, Set<Platform> redes,
            String encargo, DecisorDelAgente.Decision decision, String porQue) {
        CampaignImageService.Diseno diseno;
        try {
            diseno = generador.disenarParaElAgente(w, new CampaignImageRequest(1,
                    new CampaignImageRequest.Format("post", null, null),
                    List.of(asset.getUrl()),
                    // El logo también lo decide el decisor: sin él, se pide que no lo ponga.
                    new CampaignImageRequest.Brand(w.getLogoUrl(), decision.logo() ? null : "NONE"),
                    encargo, null, List.of(), null, "", false,
                    redes.stream().map(Platform::name).toList()));
        } catch (RuntimeException ex) {
            log.info("El agente no pudo diseñar {}; va tal cual: {}", asset.getId(), ex.getMessage());
            return false;
        }
        if (diseno == null) {
            return false;
        }

        LocalDateTime fecha = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(),
                posts.huecosTomados(LocalDateTime.now()), limites.maxPorDia(), horario(w));
        int hechas = 0;
        for (CampaignImageService.Diseno.Version v : diseno.versiones()) {
            List<SocialAccount> suyas = destino.stream().filter(c -> v.redes().contains(c.getPlatform())).toList();
            if (suyas.isEmpty()) {
                continue;
            }
            Map<String, String> porRed = new LinkedHashMap<>();
            for (Platform red : v.redes()) {
                String t = diseno.captionsPorRed().get(red);
                if (t != null && !t.isBlank()) {
                    porRed.put(red.name(), t);
                }
            }
            String caption = porRed.values().stream().findFirst()
                    .orElse(diseno.titular() == null || diseno.titular().isBlank() ? "Publicacion" : diseno.titular());

            PostSaveRequest pedido = new PostSaveRequest();
            pedido.setCaption(caption);
            pedido.setTitulo(diseno.titular());
            pedido.setBrief(encargo);
            pedido.setMediaUrls(List.of(v.url()));
            pedido.setFormat(PostFormat.PHOTO.name());
            pedido.setSocialAccountIds(suyas.stream().map(SocialAccount::getId).toList());
            pedido.setCaptionsPorRed(porRed);

            Set<Platform> deEsta = new LinkedHashSet<>(v.redes());
            deEsta.retainAll(redes);
            String motivo = porQue + " " + decision.explicacion() + " (1 crédito) " + cuandoYDonde(deEsta, fecha);
            postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(), DecisorDelAgente.Tratamiento.DISENO.name());
            hechas++;
        }
        return hechas > 0;
    }

    /** «En todas tus redes, el jue 2 oct, 11:00: el primer hueco libre.» */
    static String cuandoYDonde(Set<Platform> redes, LocalDateTime fecha) {
        String donde = redes.size() > 1
                ? "Para " + redes.stream().map(Platform::getLabel).collect(Collectors.joining(", "))
                : "Para " + redes.iterator().next().getLabel();
        return donde + ", el " + FECHA.format(fecha) + ": el primer hueco libre.";
    }

    private static List<String> conPaso(List<String> pasos, String otro) {
        List<String> todos = new ArrayList<>(pasos);
        todos.add(otro);
        return todos;
    }

    /**
     * Cuántos diseños caben todavía esta semana: el ritmo de los créditos de la
     * cuenta menos los que el agente ya hizo desde el lunes.
     */
    int disenosDisponibles(Workspace w) {
        int disponibles = creditos.disponibles(w.getId());
        java.time.LocalDate hoy = java.time.LocalDate.now();
        LocalDateTime lunes = hoy.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay();
        int usados = (int) posts.disenosDelAgenteDesde(lunes);
        java.time.LocalDate vencen = disponibles == Integer.MAX_VALUE ? null
                : java.util.Optional.ofNullable(creditos.saldo(w.getId()).vencenLosMensuales())
                        .map(LocalDateTime::toLocalDate).orElse(null);
        return RitmoDeCreditos.estaSemana(disponibles, vencen, hoy, usados);
    }

    /** Cuánto prefiere la cuenta que no se diseñe: sube al descartar diseños, baja al aprobarlos. */
    static int ajusteDeDiseno(Workspace w) {
        return w.getAgenteAjusteDiseno() == null ? 0 : w.getAgenteAjusteDiseno();
    }

    /**
     * Las propuestas que nadie aprobó a tiempo no salen: se mueven al
     * siguiente hueco libre. Así no hay que hacer nada para que una semana sin
     * revisar no se pierda.
     */
    void reacomodarVencidas(Workspace w) {
        LocalDateTime limite = LocalDateTime.now().plusMinutes(30);
        for (Post p : posts.propuestasDelAgente()) {
            if (p.getFechaPropuesta() != null && p.getFechaPropuesta().isBefore(limite)) {
                List<LocalDateTime> tomados = new ArrayList<>(posts.huecosTomados(LocalDateTime.now()));
                tomados.remove(p.getFechaPropuesta());
                LocalDateTime nueva = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(), tomados,
                        limites.maxPorDia(), horario(w));
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
            cuando = CalendarioDelAgente.siguienteHueco(LocalDateTime.now(), tomados, limites.maxPorDia(),
                    horario(workspace(workspaceId)));
        }
        PostResponse hecho = postService.programarPropuesta(postId, cuando, workspaceId);
        etapaDeSusFotos(post, EtapaAgente.APROBADA, null);
        if (DecisorDelAgente.Tratamiento.DISENO.name().equals(post.getAgenteTratamiento())) {
            aprender(workspaceId, -1);
        }
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
        if (DecisorDelAgente.Tratamiento.DISENO.name().equals(post.getAgenteTratamiento())) {
            aprender(post.getTenantId() == null ? null : UUID.fromString(post.getTenantId()), +1);
        }
    }

    /**
     * Mueve cuánto diseño quiere la cuenta, entre -1 (más) y 2 (casi nada).
     * Un diseño descartado empuja a diseñar menos; uno aprobado, a diseñar más.
     */
    private void aprender(UUID workspaceId, int paso) {
        if (workspaceId == null) {
            return;
        }
        workspaces.findById(workspaceId).ifPresent(w -> {
            int nuevo = Math.max(-1, Math.min(2, ajusteDeDiseno(w) + paso));
            if (nuevo != ajusteDeDiseno(w)) {
                w.setAgenteAjusteDiseno(nuevo);
                workspaces.save(w);
            }
        });
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

    /**
     * Otra foto ya trabajada que es esta misma, o {@code null}. Calcula y
     * guarda la huella de esta de paso, para que la siguiente se compare
     * contra ella. Sin huella (formato que Java no lee) no hay comparación.
     */
    private MediaAsset repetidaDe(MediaAsset asset) {
        Long h = asset.getHuella() != null ? asset.getHuella() : huellas.de(asset);
        if (h == null) {
            return null;
        }
        if (asset.getHuella() == null) {
            asset.setHuella(h);
            assets.save(asset);
        }
        for (MediaAsset otra : assets.yaTrabajadasConHuella()) {
            if (!otra.getId().equals(asset.getId()) && HuellaDeImagen.parecidas(h, otra.getHuella())) {
                return otra;
            }
        }
        return null;
    }

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

    /** El horario del negocio guardado en la cuenta, o cualquier día de 9 a 21. */
    static CalendarioDelAgente.Horario horario(Workspace w) {
        Set<java.time.DayOfWeek> dias = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        if (w.getAgenteDias() != null) {
            for (String d : w.getAgenteDias().split(",")) {
                try {
                    dias.add(java.time.DayOfWeek.of(Integer.parseInt(d.strip())));
                } catch (RuntimeException ignorado) {
                    // Un día ilegible no tumba el resto.
                }
            }
        }
        return new CalendarioDelAgente.Horario(dias,
                w.getAgenteHoraDesde() == null ? 9 : w.getAgenteHoraDesde(),
                w.getAgenteHoraHasta() == null ? 21 : w.getAgenteHoraHasta());
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

    /** La foto original de la propuesta (no la copia con logo): esa es la que cambia de etapa. */
    private void etapaDeSusFotos(Post post, EtapaAgente etapa, String motivo) {
        List<String> urls = post.getAgenteFotoUrl() != null ? List.of(post.getAgenteFotoUrl()) : post.getMediaUrls();
        for (MediaAsset a : assets.findByUrlIn(urls)) {
            marcar(a, etapa, motivo == null ? a.getAgenteMotivo() : motivo);
        }
    }
}
