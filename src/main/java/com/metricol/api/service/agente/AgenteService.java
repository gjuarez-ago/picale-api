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
import com.metricol.api.service.agente.video.AnalisisDeVideo;
import com.metricol.api.service.agente.video.AnalistaDeVideo;
import com.metricol.api.service.agente.video.DecisorDeVideo;
import com.metricol.api.service.agente.video.EditorDeVideo;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.MedidorDeVideo;
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

    /** Cuánto dura el candado de quien revisa un archivo; pasado esto se da por caído y otro lo toma. */
    static final int CANDADO_MINUTOS = 20;

    /** Cuántas veces se reintenta un archivo que no se pudo revisar antes de mandarlo a Observación. */
    static final int MAX_INTENTOS = 3;

    /** Diseños por semana de una cuenta normal mientras los cobros estén apagados. Las maestras no tienen tope. */
    static final int TOPE_SEMANAL_SIN_COBROS = 3;


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
    private final MedidorDeVideo medidor;
    /** Quien mira y escucha los videos. Ver {@code agente/video/}. */
    private final AnalistaDeVideo analista;
    /** Quien los edita (hoy nadie: {@code SinEditor}). */
    private final EditorDeVideo editor;
    /** Para leer o actuar en otra cuenta desde una petición web. */
    private final CuentaAparte otraCuenta;
    private final com.metricol.api.config.VideoLimitsProperties videoLimites;

    public AgenteService(WorkspaceRepository workspaces, MediaAssetRepository assets, PostRepository posts,
            SocialAccountRepository cuentas, RevisorDeMarca revisor, Redactor redactor, PostService postService,
            MediaService mediaService, FormatRulesService formatos, LimitesConfigurables limites,
            AiQuotaGuard cupoIa, LogoSobreFoto logo, HuellaDeImagen huellas, CampaignImageService generador,
            CreditService creditos, RetoqueDeFoto retoque, MedidorDeVideo medidor,
            com.metricol.api.config.VideoLimitsProperties videoLimites, AnalistaDeVideo analista,
            EditorDeVideo editor, CuentaAparte otraCuenta) {
        this.otraCuenta = otraCuenta;
        this.analista = analista;
        this.editor = editor;
        this.retoque = retoque;
        this.medidor = medidor;
        this.videoLimites = videoLimites;
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
        return new Estado(w.conAgente(), w.getAgenteDesde(), porRevisar, posts.contarPropuestasDelAgente(),
                assets.countByAgenteEtapa(EtapaAgente.OBSERVACION), assets.countByAgenteEtapa(EtapaAgente.DESCARTADA),
                BrandService.completitud(w).percent(),
                h.dias().stream().map(java.time.DayOfWeek::getValue).sorted().toList(), h.desde(), h.hasta(),
                posts.contarProgramadasDelAgente());
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
            // En un hilo limpio: en el de la petición la sesión ya está atada a la cuenta actual.
            Estado e = otraCuenta.en(m.id(), () -> estado(m.id()));
            cuentas.add(new Cuenta(m.id(), m.name(), m.logoUrl(), m.color(), m.activo(), e.activo(),
                    e.propuestas(), e.enObservacion(), e.porRevisar()));
        }
        // Lo que más espera, primero: es lo que el community manager va a atender.
        cuentas.sort(java.util.Comparator.comparingLong((Cuenta c) -> -(c.propuestas() + c.enObservacion())));
        return cuentas;
    }

    /**
     * Una propuesta en la bandeja de todas las cuentas, con lo que la persona
     * puede hacer con ella en ESA cuenta (su rol puede ser distinto en cada una).
     */
    public record PropuestaDeCuenta(UUID cuentaId, String cuenta, String color, PostResponse propuesta,
            boolean puedeAprobar, boolean puedeDescartar) {
    }

    /**
     * La bandeja del community manager: lo que espera aprobación en todas sus
     * cuentas, en el orden en que saldría. Cada cuenta se lee con su propio
     * workspace impuesto, como si se entrara a ella.
     */
    public List<PropuestaDeCuenta> bandejaDeTodas(List<com.metricol.api.models.response.MiWorkspaceResponse> mias) {
        List<PropuestaDeCuenta> todas = new ArrayList<>();
        for (var m : mias) {
            if (m.archivado()) {
                continue;
            }
            boolean aprueba = m.permisos() != null && m.permisos().contains("POST_SCHEDULE");
            boolean descarta = m.permisos() != null && m.permisos().contains("POST_DELETE");
            for (PostResponse p : otraCuenta.en(m.id(), this::propuestas)) {
                todas.add(new PropuestaDeCuenta(m.id(), m.name(), m.color(), p, aprueba, descarta));
            }
        }
        todas.sort(java.util.Comparator.comparing((PropuestaDeCuenta p) -> p.propuesta().getFechaPropuesta(),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return todas;
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
        if (destino.isEmpty() && cuentasPara(PostFormat.REEL).isEmpty()) {
            // Sin redes no hay a dónde proponer: ni se gasta en revisar.
            return 0;
        }

        int hechas = 0;
        LocalDateTime ahora = LocalDateTime.now();
        for (MediaAsset asset : assets.paraElAgente(w.getAgenteDesde(), ahora.minusMinutes(CANDADO_MINUTOS),
                PageRequest.of(0, POR_VUELTA))) {
            try {
                cupoIa.exigirCupo();
            } catch (RuntimeException topeDelDia) {
                log.info("El agente de {} llegó al tope de IA del día; sigue mañana.", workspaceId);
                break;
            }
            // Si un botón ("Revisar ahora", "Que la revise") ya la tomó, es suya.
            if (!tomar(asset)) {
                continue;
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
        return procesar(asset, w, destino, forzar, null);
    }

    /** @param cambio lo que la persona pidió cambiar ("¿Le cambiamos algo?"), o nulo */
    boolean procesar(MediaAsset asset, Workspace w, List<SocialAccount> destino, boolean forzar, Cambio cambio) {
        if (asset.getType() == com.metricol.api.enums.MediaType.VIDEO) {
            return procesarVideo(asset, w, forzar, cambio);
        }
        if (destino.isEmpty()) {
            marcar(asset, EtapaAgente.OBSERVACION, "No tienes redes conectadas que publiquen fotos.");
            return true;
        }
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
        decision = conCambio(decision, cambio, disenosDisponibles(w));
        if (decision.tratamiento() == DecisorDelAgente.Tratamiento.OBSERVACION) {
            marcar(asset, EtapaAgente.OBSERVACION, decision.explicacion());
            return true;
        }

        try {
            Set<Platform> redes = destino.stream().map(SocialAccount::getPlatform)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String encargo = conCambio(encargo(revision), cambio);
            String porQue = forzar ? "Me dijiste que va." : "Va con tu marca: " + sinPunto(revision.motivo()) + ".";

            if (decision.tratamiento() == DecisorDelAgente.Tratamiento.DISENO) {
                if (proponerDiseno(asset, w, destino, redes, encargo, decision, porQue,
                        categoria(revision.diagnostico()))) {
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

            CalendarioDelAgente.Hueco hueco = hueco(w, categoria(revision.diagnostico()));
            LocalDateTime fecha = hueco.cuando();

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

            String motivo = porQue + " " + String.join(" ", pasos) + " " + cuandoYDonde(redes, hueco);
            postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(), decision.tratamiento().name(),
                    categoria(revision.diagnostico()).name());

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
     * Un video, con los tres roles separados:
     * <ol>
     * <li>Lo barato primero, sin IA: medirlo (orientación y duración). Lo que
     * no puede salir nunca —ilegible, de más de diez minutos, horizontal, de
     * menos de 3 s— va a Observación sin gastar nada.</li>
     * <li>El {@link AnalistaDeVideo} lo mira entero y lo escucha.</li>
     * <li>El {@link DecisorDeVideo} decide formato, recorte y portada.</li>
     * <li>Si hay que recortar, el {@link EditorDeVideo}; hoy no hay, y el
     * decisor deja el tramo exacto en Observación.</li>
     * </ol>
     */
    boolean procesarVideo(MediaAsset video, Workspace w, boolean forzar) {
        return procesarVideo(video, w, forzar, null);
    }

    boolean procesarVideo(MediaAsset video, Workspace w, boolean forzar, Cambio cambio) {
        FfmpegImagen.MedidasVideo m = medidor.medir(video);
        if (m == null) {
            marcar(video, EtapaAgente.OBSERVACION, "No pude leer este video (formato o archivo dañado). Prueba subirlo otra vez.");
            return true;
        }
        double duracion = m.segundos();
        if (duracion > AnalistaDeVideo.MAX_SEGUNDOS) {
            marcar(video, EtapaAgente.OBSERVACION, "Dura " + DecisorDeVideo.duracion(duracion)
                    + " y en Pícale los videos son de hasta 10 minutos. Súbelo más corto.");
            return true;
        }
        if (!m.vertical()) {
            marcar(video, EtapaAgente.OBSERVACION, "Es un video horizontal: Reels, TikTok e historias piden vertical. "
                    + "Grábalo en vertical o publícalo a mano.");
            return true;
        }
        FormatRulesService.Regla reel = formatos.de(PostFormat.REEL);
        if (reel.minSegundos() != null && duracion < reel.minSegundos()) {
            marcar(video, EtapaAgente.OBSERVACION, "Dura " + Math.round(duracion) + " s y un Reel necesita al menos "
                    + reel.minSegundos() + " s.");
            return true;
        }
        if (cuentasPara(PostFormat.REEL).isEmpty()) {
            marcar(video, EtapaAgente.OBSERVACION, "No tienes redes conectadas que publiquen video.");
            return true;
        }

        // El Analista: lo mira entero y lo escucha.
        Redactor.Negocio negocio = negocio(w);
        boolean marcaCompleta = BrandService.completitud(w).percent() >= MARCA_SUFICIENTE;
        AnalisisDeVideo analisis = analista.analizar(video, duracion, negocio, marcaCompleta);
        if (analisis == null) {
            marcar(video, EtapaAgente.PENDIENTE, "No pude analizarlo todavía; lo vuelvo a intentar en un rato.");
            return false;
        }
        if (!forzar && analisis.veredicto() != RevisorDeMarca.Veredicto.VA) {
            marcar(video, analisis.veredicto() == RevisorDeMarca.Veredicto.OBSERVACION
                    ? EtapaAgente.OBSERVACION
                    : EtapaAgente.DESCARTADA, analisis.motivo());
            return true;
        }

        // El Decisor: formato, recorte y portada.
        DecisorDeVideo.Decision decision = DecisorDeVideo.decidir(analisis, duracion,
                !cuentasPara(PostFormat.STORY).isEmpty(), editor.puedeRecortar());
        if (decision.tratamiento() == DecisorDeVideo.Tratamiento.OBSERVACION) {
            marcar(video, EtapaAgente.OBSERVACION, decision.explicacion());
            return true;
        }

        try {
            // El Editor, si hay que recortar. Hoy no hay (SinEditor), así que
            // el decisor nunca pide RECORTAR; el camino queda listo para él.
            MediaAsset publicar = video;
            if (decision.tratamiento() == DecisorDeVideo.Tratamiento.RECORTAR) {
                MediaAsset recortado = editor.recortar(video, analisis, decision.inicio(), decision.fin());
                if (recortado == null) {
                    marcar(video, EtapaAgente.PENDIENTE, "No pude recortarlo; lo vuelvo a intentar en un rato.");
                    return false;
                }
                publicar = recortado;
            }
            int segundos = (int) Math.round(decision.fin() - decision.inicio());

            // Solo las redes que publican ese formato y aceptan esa duración.
            List<SocialAccount> todas = cuentasPara(decision.formato());
            List<SocialAccount> destino = todas.stream()
                    .filter(c -> !videoLimites.excede(c.getPlatform(), segundos)).toList();
            if (destino.isEmpty()) {
                marcar(video, EtapaAgente.OBSERVACION, decision.explicacion()
                        + " Pero ninguna de tus redes acepta un video de " + segundos + " s.");
                return true;
            }
            Set<Platform> redes = destino.stream().map(SocialAccount::getPlatform)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // El texto, con lo que se ve Y lo que se dice: así un precio o una
            // promoción que se menciona en voz alta llega al caption.
            String encargo = conCambio("Haz una publicacion para las redes del negocio con este video: "
                    + (analisis.idea().isBlank() ? "una publicacion con este video" : analisis.idea()), cambio);
            List<String> queSeVe = new ArrayList<>();
            if (!analisis.descripcion().isBlank()) {
                queSeVe.add(analisis.descripcion());
            }
            if (analisis.hayVoz()) {
                queSeVe.add("En el video se dice: " + analisis.transcripcion());
            }
            Redactor.Borrador borrador = redactor.redactar(encargo, queSeVe, redes, negocio);
            CalendarioDelAgente.Categoria categoria = categoria(analisis.comoDiagnostico());
            CalendarioDelAgente.Hueco hueco = hueco(w, categoria);

            PostSaveRequest pedido = new PostSaveRequest();
            pedido.setCaption(texto(borrador));
            pedido.setTitulo(borrador.titulo());
            pedido.setBrief(encargo);
            pedido.setMediaUrls(List.of(publicar.getUrl()));
            pedido.setFormat(decision.formato().name());
            pedido.setVideoDurationSeconds(segundos);
            pedido.setSocialAccountIds(destino.stream().map(SocialAccount::getId).toList());
            Map<String, String> porRed = new LinkedHashMap<>();
            borrador.textos().forEach((red, t) -> porRed.put(red.name(), t));
            pedido.setCaptionsPorRed(porRed);

            String porQue = forzar ? "Me dijiste que va." : "Va con tu marca: " + sinPunto(analisis.motivo()) + ".";
            String fuera = todas.size() > destino.size()
                    ? " " + todas.stream().filter(c -> !destino.contains(c)).map(c -> c.getPlatform().getLabel())
                            .distinct().collect(Collectors.joining(" y ")) + " no: dura más de lo que acepta."
                    : "";
            String motivo = porQue + " " + decision.explicacion() + fuera + " " + cuandoYDonde(redes, hueco);
            Post propuesta = postService.crearPropuesta(pedido, hueco.cuando(), motivo, video.getUrl(),
                    decision.tratamiento() == DecisorDeVideo.Tratamiento.RECORTAR ? "RECORTE"
                            : DecisorDelAgente.Tratamiento.TAL_CUAL.name(),
                    categoria.name());
            if (decision.formato() == PostFormat.REEL) {
                propuesta.setPortadaMs(decision.portadaMs());
                posts.save(propuesta);
            }
            marcar(video, EtapaAgente.PROPUESTA, motivo);
            return true;
        } catch (RuntimeException ex) {
            log.warn("El agente no pudo preparar la propuesta del video {}: {}", video.getId(), ex.toString());
            marcar(video, EtapaAgente.PENDIENTE, "No pude preparar la publicación; lo vuelvo a intentar en un rato.");
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
            String encargo, DecisorDelAgente.Decision decision, String porQue, CalendarioDelAgente.Categoria categoria) {
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

        CalendarioDelAgente.Hueco hueco = hueco(w, categoria);
        LocalDateTime fecha = hueco.cuando();
        // Un id por generación: las versiones son una publicación (se aprueban,
        // descartan y mueven juntas) y un solo crédito para el tope semanal.
        String disenoId = UUID.randomUUID().toString();
        List<Post> creadas = new ArrayList<>();
        try {
            crearVersiones(diseno, destino, redes, encargo, decision, porQue, categoria, hueco, fecha, asset,
                    disenoId, creadas);
        } catch (RuntimeException ex) {
            // A medias no se queda: si una versión no se pudo guardar, se quitan
            // las que sí, y la foto sigue tal cual (el diseño ya se pagó, eso no
            // tiene vuelta, pero no habrá propuestas duplicadas en la siguiente vuelta).
            log.warn("No se pudieron guardar las versiones del diseño de {}: {}", asset.getId(), ex.toString());
            creadas.forEach(p -> postService.delete(p.getId()));
            return false;
        }
        return !creadas.isEmpty();
    }

    private void crearVersiones(CampaignImageService.Diseno diseno, List<SocialAccount> destino, Set<Platform> redes,
            String encargo, DecisorDelAgente.Decision decision, String porQue, CalendarioDelAgente.Categoria categoria,
            CalendarioDelAgente.Hueco hueco, LocalDateTime fecha, MediaAsset asset, String disenoId, List<Post> creadas) {
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
            String motivo = porQue + " " + decision.explicacion() + " (1 crédito) " + cuandoYDonde(deEsta, hueco);
            Post creada = postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(),
                    DecisorDelAgente.Tratamiento.DISENO.name(), categoria == null ? null : categoria.name());
            creada.setAgenteDisenoId(disenoId);
            creadas.add(posts.save(creada));
        }
    }

    /** «Para Instagram y Facebook, el jue 2 oct, 11:00: el primer hueco libre.» O por qué se movió. */
    static String cuandoYDonde(Set<Platform> redes, CalendarioDelAgente.Hueco hueco) {
        String donde = redes.size() > 1
                ? "Para " + redes.stream().map(Platform::getLabel).collect(Collectors.joining(", "))
                : "Para " + redes.iterator().next().getLabel();
        return donde + ", el " + FECHA.format(hueco.cuando()) + ": "
                + (hueco.razon() == null ? "el primer hueco libre." : sinMayuscula(hueco.razon()));
    }

    private static String sinMayuscula(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** El siguiente hueco que respeta el horario, los topes y la mezcla de la semana. */
    private CalendarioDelAgente.Hueco hueco(Workspace w, CalendarioDelAgente.Categoria categoria) {
        LocalDateTime ahora = LocalDateTime.now();
        List<CalendarioDelAgente.Tomado> tomados = posts.tomadosConCategoria(ahora).stream()
                .map(r -> new CalendarioDelAgente.Tomado((LocalDateTime) r[0], (String) r[1]))
                .toList();
        return CalendarioDelAgente.siguienteHueco(ahora, tomados, limites.maxPorDia(), horario(w), categoria);
    }

    /**
     * De qué clase es la publicación, para la mezcla: promoción, venta (producto
     * o intención de vender), comunidad (equipo, evento, testimonio) o día a día.
     */
    static CalendarioDelAgente.Categoria categoria(DecisorDelAgente.Diagnostico d) {
        if (d == null) {
            return CalendarioDelAgente.Categoria.DIA_A_DIA;
        }
        return switch (d.tipo()) {
            case "PROMOCION" -> CalendarioDelAgente.Categoria.PROMOCION;
            case "PRODUCTO" -> CalendarioDelAgente.Categoria.VENTA;
            case "EQUIPO", "EVENTO", "TESTIMONIO" -> CalendarioDelAgente.Categoria.COMUNIDAD;
            default -> switch (d.intencion()) {
                case VENDER -> CalendarioDelAgente.Categoria.VENTA;
                case COMUNIDAD, CONFIANZA -> CalendarioDelAgente.Categoria.COMUNIDAD;
                default -> CalendarioDelAgente.Categoria.DIA_A_DIA;
            };
        };
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
        // Las cuentas maestras (exentas de pago) no tienen límite.
        if (creditos.esMaestra(w.getId())) {
            return Integer.MAX_VALUE;
        }
        java.time.LocalDate hoy = java.time.LocalDate.now();
        LocalDateTime lunes = hoy.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay();
        int usados = (int) posts.disenosDelAgenteDesde(lunes);
        // Sin cobros no hay saldo que repartir, pero cada diseño sí cuesta: un
        // tope fijo por semana evita que el agente diseñe todo lo que vea.
        if (!creditos.cobrosActivos()) {
            return Math.max(0, TOPE_SEMANAL_SIN_COBROS - usados);
        }
        int disponibles = creditos.disponibles(w.getId());
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
        java.util.Set<UUID> hechas = new java.util.HashSet<>();
        for (Post p : posts.propuestasDelAgente()) {
            if (hechas.contains(p.getId()) || p.getFechaPropuesta() == null || !p.getFechaPropuesta().isBefore(limite)) {
                continue;
            }
            // Las versiones de un diseño se mueven juntas: son una publicación.
            List<Post> grupo = grupoDe(p);
            grupo.forEach(g -> hechas.add(g.getId()));
            LocalDateTime nueva = huecoSin(w, grupo).cuando();
            grupo.forEach(g -> moverA(g, nueva));
        }
    }

    /**
     * Al quedar un hueco libre (se descartó o se rehízo una propuesta), las que
     * vienen después se adelantan si caben antes, respetando horario, topes y
     * mezcla. Sin esto el calendario quedaba con un día vacío en medio y lo
     * demás esperando de más.
     *
     * @return cuántas se movieron
     */
    int replanear(Workspace w) {
        int movidas = 0;
        java.util.Set<UUID> hechas = new java.util.HashSet<>();
        // En orden: cada una ve ya dónde quedaron las anteriores.
        for (Post p : posts.propuestasDelAgente()) {
            if (hechas.contains(p.getId()) || p.getFechaPropuesta() == null) {
                continue;
            }
            List<Post> grupo = grupoDe(p);
            grupo.forEach(g -> hechas.add(g.getId()));
            LocalDateTime antes = huecoSin(w, grupo).cuando();
            if (antes.isBefore(p.getFechaPropuesta())) {
                grupo.forEach(g -> moverA(g, antes));
                movidas++;
            }
        }
        return movidas;
    }

    /**
     * El mejor hueco para esta publicación, sin contar sus propias versiones
     * como ocupadas (de un diseño en dos proporciones se quitan las dos).
     */
    private CalendarioDelAgente.Hueco huecoSin(Workspace w, List<Post> grupo) {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime suya = grupo.get(0).getFechaPropuesta();
        int porQuitar = suya == null ? 0 : grupo.size();
        List<CalendarioDelAgente.Tomado> tomados = new ArrayList<>();
        for (Object[] r : posts.tomadosConCategoria(ahora)) {
            LocalDateTime cuando = (LocalDateTime) r[0];
            if (porQuitar > 0 && cuando != null && cuando.equals(suya)) {
                porQuitar--;
                continue;
            }
            tomados.add(new CalendarioDelAgente.Tomado(cuando, (String) r[1]));
        }
        return CalendarioDelAgente.siguienteHueco(ahora, tomados, limites.maxPorDia(), horario(w),
                CalendarioDelAgente.Categoria.de(grupo.get(0).getAgenteCategoria()));
    }

    /** La mueve de fecha y corrige la fecha que dice su explicación, para que no mienta. */
    private void moverA(Post p, LocalDateTime nueva) {
        LocalDateTime vieja = p.getFechaPropuesta();
        p.setFechaPropuesta(nueva);
        if (vieja != null && p.getAgenteMotivo() != null) {
            p.setAgenteMotivo(p.getAgenteMotivo().replace(FECHA.format(vieja), FECHA.format(nueva)));
        }
        posts.save(p);
    }

    // ------------------------------------------------------------ la bandeja

    /** Lo que espera aprobación en la cuenta actual. La transacción la abre PostService (ver ahí por qué). */
    public List<PostResponse> propuestas() {
        return postService.propuestasDelAgente(p -> true);
    }

    public List<MediaAssetResponse> archivos(EtapaAgente etapa) {
        return assets.findByAgenteEtapaOrderByCreatedAtDesc(etapa).stream().map(mediaService::respuesta).toList();
    }

    /**
     * Aprobar: se programa en su fecha. Si la fecha ya está demasiado cerca,
     * se toma el siguiente hueco; aprobar tarde no debe publicar de golpe.
     */
    public PostResponse aprobar(UUID postId, UUID workspaceId) {
        Post post = propuestaPendiente(postId);
        List<Post> grupo = grupoDe(post);
        LocalDateTime cuando = post.getFechaPropuesta();
        if (cuando == null || cuando.isBefore(LocalDateTime.now().plusMinutes(10))) {
            cuando = huecoSin(workspace(workspaceId), grupo).cuando();
        }
        // Las versiones de un diseño son una publicación: salen juntas, a la misma hora.
        PostResponse hecho = null;
        for (Post p : grupo) {
            PostResponse r = postService.programarPropuesta(p.getId(), cuando, workspaceId);
            if (p.getId().equals(postId)) {
                hecho = r;
            }
        }
        etapaDeSusFotos(post, EtapaAgente.APROBADA, null);
        if (DecisorDelAgente.Tratamiento.DISENO.name().equals(post.getAgenteTratamiento())) {
            aprender(workspaceId, -1);
        }
        return hecho;
    }

    /** Una propuesta del agente que sigue esperando aprobación, o un error que lo dice. */
    private Post propuestaPendiente(UUID postId) {
        Post post = posts.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Propuesta no encontrada."));
        if (!post.delAgente() || post.getStatus() != com.metricol.api.enums.PostStatus.DRAFT) {
            throw new IllegalStateException("Esta publicación ya no está esperando aprobación.");
        }
        return post;
    }

    /**
     * Las propuestas que son la misma publicación: las versiones de un diseño
     * (una por proporción) y nada más. Una foto tal cual es un grupo de uno.
     */
    private List<Post> grupoDe(Post post) {
        if (post.getAgenteDisenoId() == null) {
            return List.of(post);
        }
        List<Post> grupo = posts.propuestasDelAgente().stream()
                .filter(p -> post.getAgenteDisenoId().equals(p.getAgenteDisenoId()))
                .toList();
        return grupo.isEmpty() ? List.of(post) : grupo;
    }

    /** Aprobar todas: las que no se pueden (sin cupo, sin página) se quedan y se dice cuántas. */
    public record Lote(int aprobadas, int pendientes, String primerMotivo) {
    }

    public Lote aprobarTodas(UUID workspaceId) {
        int ok = 0;
        int no = 0;
        String motivo = null;
        for (Post p : posts.propuestasDelAgente()) {
            // Una versión ya salió junto con su diseño hermano en una vuelta anterior.
            boolean sigue = posts.findByIdAndDeletedAtIsNull(p.getId())
                    .map(x -> x.getStatus() == com.metricol.api.enums.PostStatus.DRAFT).orElse(false);
            if (!sigue) {
                continue;
            }
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

    /**
     * Descartar una propuesta: se elimina —con sus otras versiones, si es un
     * diseño— y su foto pasa a Descartadas, rescatable. Solo propuestas del
     * agente que siguen esperando: por aquí no se elimina otra publicación.
     */
    public void descartar(UUID postId) {
        Post post = propuestaPendiente(postId);
        for (Post p : grupoDe(post)) {
            postService.delete(p.getId());
        }
        etapaDeSusFotos(post, EtapaAgente.DESCARTADA, "La descartaste tú.");
        UUID workspaceId = post.getTenantId() == null ? null : UUID.fromString(post.getTenantId());
        if (DecisorDelAgente.Tratamiento.DISENO.name().equals(post.getAgenteTratamiento())) {
            aprender(workspaceId, +1);
        }
        // Quedó un hueco: lo que venía después se adelanta.
        if (workspaceId != null) {
            workspaces.findById(workspaceId).ifPresent(this::replanear);
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
            if (asset.getAgenteEtapa() == EtapaAgente.PROPUESTA || asset.getAgenteEtapa() == EtapaAgente.APROBADA) {
                throw new IllegalStateException("Ya está en Por aprobar o aprobada: descártala desde ahí.");
            }
            marcar(asset, EtapaAgente.DESCARTADA, "Dijiste que no va.");
            return;
        }
        List<SocialAccount> destino = validarRevisable(asset);
        // Las mismas reglas que cualquier revisión: el tope de IA del día y el candado.
        cupoIa.exigirCupo();
        if (!tomar(asset)) {
            throw new IllegalStateException("Ya la estoy revisando; en un momento la ves.");
        }
        if (!procesar(asset, workspace(workspaceId), destino, true)) {
            throw new IllegalStateException("No pude prepararla ahora; lo intento de nuevo en un rato.");
        }
    }

    /**
     * Lo que tiene que cumplir un archivo para que se le pida al agente que lo
     * revise a mano (un botón): ni propuesto ya, ni en una publicación, ni de la
     * IA, y con redes a dónde ir.
     *
     * @return las cuentas para fotos (vacía para un video: él busca las suyas)
     */
    private List<SocialAccount> validarRevisable(MediaAsset asset) {
        boolean esVideo = asset.getType() == com.metricol.api.enums.MediaType.VIDEO;
        if (esVideo && (asset.getThumbnailUrl() == null || asset.getThumbnailUrl().isBlank())) {
            throw new IllegalStateException("Este video todavía no tiene portada; en unos segundos ya se puede revisar.");
        }
        if (asset.deLaIa()) {
            throw new IllegalArgumentException("Esta imagen la hizo la IA: el agente solo revisa lo que subes tú.");
        }
        if (asset.getAgenteEtapa() == EtapaAgente.PROPUESTA || asset.getAgenteEtapa() == EtapaAgente.APROBADA) {
            throw new IllegalStateException("Ya la revisé: está en Por aprobar o ya la aprobaste.");
        }
        if (posts.existsEnUso(asset.getUrl())) {
            throw new IllegalStateException("Ya está en una publicación: no la vuelvo a proponer.");
        }
        List<SocialAccount> destino = cuentasParaFotos();
        if (!esVideo && destino.isEmpty()) {
            throw new IllegalStateException("Conecta al menos una red que acepte fotos para que pueda proponerla.");
        }
        return destino;
    }

    /**
     * Toma el archivo para revisarlo, o {@code false} si otro ya lo tiene. Es
     * atómico en la base: el proceso de fondo y un botón nunca lo revisan los
     * dos a la vez, que es como salían dos propuestas (y dos créditos) por foto.
     */
    private boolean tomar(MediaAsset asset) {
        LocalDateTime ahora = LocalDateTime.now();
        if (assets.tomar(asset.getId(), ahora, ahora.minusMinutes(CANDADO_MINUTOS)) != 1) {
            return false;
        }
        asset.setAgenteEtapa(EtapaAgente.REVISANDO);
        asset.setAgenteTomadoEn(ahora);
        return true;
    }

    // ------------------------------------------------------------ ¿le cambiamos algo?

    /**
     * Rehace una propuesta con lo que la persona pidió: se quita la propuesta
     * (y sus otras versiones, si era un diseño) y la foto original se vuelve a
     * procesar con el cambio. El texto del cambio llega a quien escribe y al
     * diseño; "sin logo", "tal cual", "diséñala"... cambian además la decisión.
     *
     * @return la propuesta nueva (o sus versiones)
     */
    public List<PostResponse> cambiar(UUID postId, String texto, UUID workspaceId) {
        Cambio cambio = Cambio.de(texto);
        if (cambio.vacio()) {
            throw new IllegalArgumentException("Escribe qué le cambiamos.");
        }
        Post post = propuestaPendiente(postId);
        String original = post.getAgenteFotoUrl() != null ? post.getAgenteFotoUrl() : post.getMediaUrls().get(0);
        MediaAsset asset = assets.findByUrlIn(List.of(original)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Ya no encuentro la foto original de esta propuesta."));

        // Si otra versión de la misma foto ya se aprobó, rehacerla duplicaría la
        // publicación (y gastaría otro crédito): primero hay que cancelar esa.
        boolean otraAprobada = posts.findByAgenteFotoUrlAndDeletedAtIsNull(original).stream()
                .anyMatch(p -> p.getStatus() != com.metricol.api.enums.PostStatus.DRAFT);
        if (otraAprobada) {
            throw new IllegalStateException("Una versión de esta foto ya está aprobada. Cancélala primero para rehacerla.");
        }
        // Antes de quitar nada: si hoy ya no hay IA, la propuesta se queda como estaba.
        cupoIa.exigirCupo();

        // Las versiones de un mismo diseño salen de la misma foto: se rehacen juntas.
        for (Post hermana : grupoDe(post)) {
            postService.delete(hermana.getId());
        }
        for (Post resto : posts.findByAgenteFotoUrlAndDeletedAtIsNull(original)) {
            postService.delete(resto.getId());
        }

        // Pendiente y no a medias: si algo falla de aquí en adelante, la vuelta la retoma.
        asset.setAgenteEtapa(EtapaAgente.PENDIENTE);
        assets.save(asset);
        if (!tomar(asset)) {
            throw new IllegalStateException("Ya la estoy revisando; en un momento la ves.");
        }
        Workspace w = workspace(workspaceId);
        if (!procesar(asset, w, cuentasParaFotos(), true, cambio)) {
            throw new IllegalStateException("No pude rehacerla ahora; la vuelvo a preparar en un rato.");
        }
        replanear(w);
        return propuestasDe(original);
    }

    /** Las propuestas que salieron de una foto. */
    public List<PostResponse> propuestasDe(String fotoOriginal) {
        return postService.propuestasDelAgente(p -> fotoOriginal.equals(p.getAgenteFotoUrl()));
    }

    /** El encargo para quien escribe, con lo que la persona pidió cambiar al final. */
    static String conCambio(String encargo, Cambio cambio) {
        if (cambio == null || cambio.vacio()) {
            return encargo;
        }
        return encargo + ". Cambios que pidio el dueno (respetalos): " + cambio.texto();
    }

    /** La decisión del decisor, con lo que la persona pidió encima. Lo que pidió manda. */
    static DecisorDelAgente.Decision conCambio(DecisorDelAgente.Decision d, Cambio c, int disenosDisponibles) {
        if (c == null) {
            return d;
        }
        List<String> pasos = new ArrayList<>(d.pasos());
        DecisorDelAgente.Tratamiento t = d.tratamiento();
        boolean logo = d.logo();
        if (Boolean.TRUE.equals(c.diseno()) && t != DecisorDelAgente.Tratamiento.DISENO) {
            if (disenosDisponibles >= 1) {
                t = DecisorDelAgente.Tratamiento.DISENO;
                pasos.add("Me pediste diseño: la diseño con IA.");
            } else {
                pasos.add("Me pediste diseño, pero no quedan créditos esta semana.");
            }
        } else if (Boolean.FALSE.equals(c.diseno()) && t == DecisorDelAgente.Tratamiento.DISENO) {
            t = DecisorDelAgente.Tratamiento.TAL_CUAL;
            pasos.add("Me pediste sin diseño: va tal cual.");
        }
        if (c.logo() != null && c.logo() != logo) {
            logo = c.logo();
            pasos.add(logo ? "Me pediste el logo: se lo pongo." : "Me pediste sin logo: se lo quito.");
        }
        if (!c.vacio()) {
            pasos.add("Apliqué lo que pediste: «" + c.texto() + "».");
        }
        return new DecisorDelAgente.Decision(t, logo, d.prioridad(), pasos);
    }

    // ------------------------------------------------------------ probar a mano

    /** Lo que hizo el agente con una foto que se le pidió revisar a mano. */
    public record Resultado(String etapa, String motivo) {
    }

    /**
     * Revisa una foto ya, sin esperar al proceso de fondo y aunque el switch
     * esté apagado: es el botón de probar desde Contenido. Pasa por el mismo
     * camino que una foto nueva —marca, decisión, texto, fecha— y no se salta
     * nada que gaste, así que cuesta lo mismo que si la hubiera tomado solo.
     */
    public Resultado revisarAhora(UUID assetId, UUID workspaceId) {
        MediaAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Archivo no encontrado."));
        List<SocialAccount> destino = validarRevisable(asset);
        cupoIa.exigirCupo();
        // El candado: si el proceso de fondo ya la está revisando, no se revisa dos veces.
        // Una que estaba en observación o descartada (por repetida) se vuelve a mirar.
        if (!tomar(asset)) {
            throw new IllegalStateException("Ya la estoy revisando; en un momento la ves.");
        }
        procesar(asset, workspace(workspaceId), destino, false);
        MediaAsset hecho = assets.findById(assetId).orElse(asset);
        return new Resultado(hecho.getAgenteEtapa() == null ? null : hecho.getAgenteEtapa().name(),
                hecho.getAgenteMotivo());
    }

    /** Una vuelta ya, sin esperar los dos minutos: el botón "Revisar ahora". */
    public int vueltaAhora(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        if (!w.conAgente()) {
            throw new IllegalStateException("Enciende el agente para que revise lo nuevo.");
        }
        return vuelta(workspaceId);
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
        return cuentasPara(PostFormat.PHOTO);
    }

    /** Las cuentas que pueden recibir ese formato: conectadas, encendidas y con página. */
    private List<SocialAccount> cuentasPara(PostFormat formato) {
        return cuentas.findAll().stream()
                .filter(c -> c.getStatus() == SocialAccountStatus.CONNECTED)
                .filter(c -> !c.apagadaPorLaPersona())
                .filter(c -> !c.sinPagina())
                .filter(c -> formatos.admite(formato, c.getPlatform()))
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
        if (etapa == EtapaAgente.PENDIENTE) {
            // Algo falló. Se reintenta, pero no para siempre: una que nunca sale
            // gastaría IA en cada vuelta y taparía a las que se suben después.
            int intentos = (asset.getAgenteIntentos() == null ? 0 : asset.getAgenteIntentos()) + 1;
            asset.setAgenteIntentos(intentos);
            if (intentos >= MAX_INTENTOS) {
                etapa = EtapaAgente.OBSERVACION;
                motivo = "No pude revisarla tras " + intentos + " intentos. Si va, dime y la preparo.";
            }
        } else if (etapa != EtapaAgente.REVISANDO) {
            asset.setAgenteIntentos(null);
        }
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
