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
    private final com.metricol.api.service.agente.foto.DirectorDeFoto director;
    private final com.metricol.api.service.agente.foto.MejoraDeFoto mejora;
    private final com.metricol.api.service.ai.PerfiladorDelNegocio perfilador;
    private final MedidorDeVideo medidor;
    /** Quien mira y escucha los videos. Ver {@code agente/video/}. */
    private final AnalistaDeVideo analista;
    /** Quien los edita (hoy nadie: {@code SinEditor}). */
    private final EditorDeVideo editor;
    /** Para leer o actuar en otra cuenta desde una petición web. */
    private final CuentaAparte otraCuenta;
    private final com.metricol.api.config.VideoLimitsProperties videoLimites;
    /** Lo que le funciona a cada cuenta: sus mejores horas y hashtags. */
    private final com.metricol.api.service.metricas.LoQueFunciona loQueFunciona;
    /** Quien arma la tanda: carruseles, posts e historias. */
    private final OrganizadorDeContenido organizador;

    /**
     * Cuánto se espera sin fotos nuevas para organizar una tanda: subir veinte
     * fotos lleva unos minutos, y organizarlas a la mitad partiría un carrusel.
     */
    @org.springframework.beans.factory.annotation.Value("${app.agente.espera-minutos:10}")
    private int esperaMinutos = 10;

    /**
     * Cuántas propuestas pueden esperar el sí a la vez. Un community manager
     * le manda a su cliente lo de la semana, no tres semanas de golpe: con la
     * fila llena, lo revisado se queda en reserva (ANALIZADA) y entra conforme
     * se aprueba, se descarta o se publica. Así nada se hace viejo esperando.
     */
    @org.springframework.beans.factory.annotation.Value("${app.agente.tope-fila:6}")
    private int topeFila = 6;

    /** ¿Ya hay suficientes esperando el sí? Lo pedido a mano (un botón) no cuenta contra esto. */
    boolean filaLlena() {
        return posts.contarPropuestasDelAgente() >= topeFila;
    }

    /** Dos fotos subidas con más de esto entre una y otra son de tandas distintas. */
    static final int SESION_MINUTOS = 15;

    private final com.metricol.api.service.avisos.AvisosPush avisos;

    /** Las redes cuya conexión caducó: no se les propone nada hasta reconectarlas. */
    private final com.metricol.api.service.social.ConexionesCaducadas conexiones;

    /** Entre un aviso de "tengo publicaciones listas" y el siguiente, en el mismo espacio. */
    static final java.time.Duration ENTRE_AVISOS = java.time.Duration.ofHours(1);

    /** Ni de madrugada ni de noche: los avisos de lo listo salen entre estas horas. */
    static final int AVISOS_DESDE = 8;
    static final int AVISOS_HASTA = 21;

    public AgenteService(WorkspaceRepository workspaces, MediaAssetRepository assets, PostRepository posts,
            SocialAccountRepository cuentas, RevisorDeMarca revisor, Redactor redactor, PostService postService,
            MediaService mediaService, FormatRulesService formatos, LimitesConfigurables limites,
            AiQuotaGuard cupoIa, LogoSobreFoto logo, HuellaDeImagen huellas, CampaignImageService generador,
            CreditService creditos, RetoqueDeFoto retoque, MedidorDeVideo medidor,
            com.metricol.api.config.VideoLimitsProperties videoLimites, AnalistaDeVideo analista,
            EditorDeVideo editor, CuentaAparte otraCuenta,
            com.metricol.api.service.metricas.LoQueFunciona loQueFunciona, OrganizadorDeContenido organizador,
            com.metricol.api.service.avisos.AvisosPush avisos,
            com.metricol.api.service.social.ConexionesCaducadas conexiones,
            com.metricol.api.service.agente.foto.DirectorDeFoto director,
            com.metricol.api.service.agente.foto.MejoraDeFoto mejora,
            com.metricol.api.service.ai.PerfiladorDelNegocio perfilador) {
        this.perfilador = perfilador;
        this.director = director;
        this.mejora = mejora;
        this.avisos = avisos;
        this.conexiones = conexiones;
        this.organizador = organizador;
        this.loQueFunciona = loQueFunciona;
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
     * @param enReserva   ya revisadas, guardadas porque la fila del sí está llena: entran conforme se aprueba
     */
    public record Estado(boolean activo, LocalDateTime desde, long porRevisar, long propuestas,
            long enObservacion, long descartadas, int marcaPercent, List<Integer> dias, int horaDesde,
            int horaHasta, long programadas, long enReserva) {
    }

    public Estado estado(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        long porRevisar = w.conAgente() && w.getAgenteDesde() != null
                ? assets.porRevisarDelAgente(w.getAgenteDesde())
                : 0;
        // Con la fila llena, las ya revisadas no están "revisándose": esperan su turno.
        long enReserva = 0;
        if (porRevisar > 0 && filaLlena()) {
            enReserva = assets.countByAgenteEtapa(EtapaAgente.ANALIZADA);
            porRevisar = Math.max(0, porRevisar - enReserva);
        }
        CalendarioDelAgente.Horario h = horario(w);
        return new Estado(w.conAgente(), w.getAgenteDesde(), porRevisar, posts.contarPropuestasDelAgente(),
                assets.countByAgenteEtapa(EtapaAgente.OBSERVACION), assets.countByAgenteEtapa(EtapaAgente.DESCARTADA),
                BrandService.completitud(w).percent(),
                h.dias().stream().map(java.time.DayOfWeek::getValue).sorted().toList(), h.desde(), h.hasta(),
                posts.contarProgramadasDelAgente(), enReserva);
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
        return vuelta(workspaceId, false);
    }

    /**
     * @param ahoraMismo organiza las tandas sin esperar a que se termine de subir
     *                   (el botón "Revisar ahora")
     */
    int vuelta(UUID workspaceId, boolean ahoraMismo) {
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
        asegurarPerfil(w);

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
            // Un video va directo a propuesta: con la fila llena espera sin tocarse.
            if (asset.getType() == com.metricol.api.enums.MediaType.VIDEO && filaLlena()) {
                continue;
            }
            // Si un botón ("Revisar ahora", "Que la revise") ya la tomó, es suya.
            if (!tomar(asset)) {
                continue;
            }
            // Los videos van directo (ya deciden Reel o historia); las fotos
            // primero se analizan y luego se organizan con las de su tanda.
            boolean listo = asset.getType() == com.metricol.api.enums.MediaType.VIDEO
                    ? procesar(asset, w, destino, false)
                    : analizarFoto(asset, w, destino, false);
            if (listo) {
                hechas++;
            }
        }
        organizar(w, destino, ahoraMismo);
        // La caducidad de lo recién propuesto, ya: así la app sabe desde el
        // principio hasta cuándo puede esperar, con la fecha original intacta.
        for (Post p : posts.propuestasDelAgente()) {
            if (p.getAgenteCaducaEn() == null && p.getFechaPropuesta() != null) {
                caducidad(p);
            }
        }
        try {
            avisar(w, LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("No se pudieron mandar los avisos de {}: {}", workspaceId, ex.toString());
        }
        return hechas;
    }

    // ------------------------------------------------------------ analizar y organizar

    /**
     * Revisa una foto contra la marca. Si no va (o no se pudo), la deja donde
     * corresponde y devuelve nulo; si va, devuelve la revisión.
     */
    private RevisorDeMarca.Revision revisarFoto(MediaAsset asset, Workspace w, List<SocialAccount> destino,
            boolean forzar) {
        if (destino.isEmpty()) {
            marcar(asset, EtapaAgente.OBSERVACION, "No tienes redes conectadas que publiquen fotos.");
            return null;
        }
        // Repetidas, antes de gastar en la IA: la misma toma subida dos veces no
        // son dos publicaciones. Se queda la que llegó primero. Si la persona
        // la rescata (forzar), va aunque se parezca.
        if (!forzar) {
            MediaAsset igual = repetidaDe(asset);
            if (igual != null) {
                marcar(asset, EtapaAgente.DESCARTADA, "Casi igual a «" + igual.getFileName()
                        + "», que ya trabajé: me quedé con esa.");
                return null;
            }
        }
        boolean marcaCompleta = BrandService.completitud(w).percent() >= MARCA_SUFICIENTE;
        RevisorDeMarca.Revision revision = revisor.revisar(asset.getUrl(), negocioParaRevisar(w), marcaCompleta);
        if (revision == null) {
            marcar(asset, EtapaAgente.PENDIENTE, "No pude revisarla todavía; lo vuelvo a intentar en un rato.");
            return null;
        }
        if (!forzar && revision.veredicto() != RevisorDeMarca.Veredicto.VA) {
            marcar(asset, revision.veredicto() == RevisorDeMarca.Veredicto.OBSERVACION
                    ? EtapaAgente.OBSERVACION
                    : EtapaAgente.DESCARTADA, revision.motivo());
            return null;
        }
        // Lo hecho con IA que puede pasar por real (una persona, una causa, la
        // oficina) se pregunta antes: es lo que más le resta credibilidad a una
        // cuenta. Se guarda lo visto para ponerle la etiqueta si la persona dice que va.
        String dudaDeIa = revision.autenticidad().duda();
        if (!forzar && dudaDeIa != null) {
            asset.setAgenteAnalisis(RevisorDeMarca.aJson(revision, false));
            marcar(asset, EtapaAgente.OBSERVACION, dudaDeIa);
            return null;
        }
        if (asset.getDescripcionIa() == null || asset.getDescripcionIa().isBlank()) {
            asset.setDescripcionIa(revision.descripcion().isBlank() ? null : revision.descripcion());
        }
        return revision;
    }

    /**
     * El primer paso: la analiza y, si va, la deja ANALIZADA con su revisión
     * guardada, para organizarla con las demás que se subieron con ella.
     *
     * @return si quedó en algún sitio; {@code false} = sigue pendiente
     */
    boolean analizarFoto(MediaAsset asset, Workspace w, List<SocialAccount> destino, boolean forzar) {
        RevisorDeMarca.Revision revision = revisarFoto(asset, w, destino, forzar);
        if (revision == null) {
            return asset.getAgenteEtapa() != EtapaAgente.PENDIENTE;
        }
        asset.setAgenteAnalisis(RevisorDeMarca.aJson(revision, forzar));
        marcar(asset, EtapaAgente.ANALIZADA, "La revisé: la acomodo con las demás que subiste.");
        return true;
    }

    /** Una foto de la tanda con lo que se sabe de ella. */
    record EnTanda(MediaAsset asset, RevisorDeMarca.Revision revision, boolean forzada) {
    }

    /**
     * El segundo paso: las analizadas se juntan por tanda (subidas con menos de
     * {@link #SESION_MINUTOS} entre una y otra) y cada tanda se organiza cuando
     * ya no llegan fotos nuevas ({@code app.agente.espera-minutos}).
     *
     * @return cuántas publicaciones propuso
     */
    int organizar(Workspace w, List<SocialAccount> destino, boolean ahoraMismo) {
        List<MediaAsset> analizadas = assets.findByAgenteEtapaOrderByCreatedAtAscIdAsc(EtapaAgente.ANALIZADA);
        if (analizadas.isEmpty()) {
            return 0;
        }
        LocalDateTime ahora = LocalDateTime.now();
        int propuestas = 0;
        for (List<MediaAsset> tanda : tandas(analizadas)) {
            LocalDateTime ultima = tanda.get(tanda.size() - 1).getCreatedAt();
            if (!ahoraMismo && ultima != null && ultima.isAfter(ahora.minusMinutes(esperaMinutos))) {
                continue;
            }
            // Fila llena: lo demás se queda en reserva, ya revisado, hasta que haya lugar.
            if (filaLlena()) {
                break;
            }
            try {
                cupoIa.exigirCupo();
            } catch (RuntimeException topeDelDia) {
                break;
            }
            List<EnTanda> fotos = new ArrayList<>();
            for (MediaAsset a : tanda) {
                if (assets.tomarAnalizada(a.getId(), LocalDateTime.now()) != 1) {
                    continue;
                }
                a.setAgenteEtapa(EtapaAgente.REVISANDO);
                RevisorDeMarca.Revision rev = RevisorDeMarca.deJson(a.getAgenteAnalisis());
                if (rev == null) {
                    marcar(a, EtapaAgente.PENDIENTE, "No pude leer lo que vi de ella; la vuelvo a revisar.");
                    continue;
                }
                fotos.add(new EnTanda(a, rev, RevisorDeMarca.forzada(a.getAgenteAnalisis())));
            }
            fotos = sinRafagas(fotos);
            if (!fotos.isEmpty()) {
                propuestas += organizarTanda(w, destino, fotos, null);
            }
        }
        return propuestas;
    }

    /**
     * De cada ráfaga (tomas seguidas de lo mismo), la mejor; las demás a
     * Descartadas, de donde se rescatan si se prefiere otra. Es lo primero que
     * hace un community manager con lo que le mandan: no publica diez veces la
     * misma foto ni mete cinco casi iguales en un carrusel.
     */
    private List<EnTanda> sinRafagas(List<EnTanda> fotos) {
        List<EnTanda> quedan = new ArrayList<>();
        for (List<EnTanda> rafaga : rafagas(fotos)) {
            EnTanda mejor = mejorDe(rafaga, huellas::nitidez);
            quedan.add(mejor);
            for (EnTanda otra : rafaga) {
                if (otra != mejor) {
                    marcar(otra.asset(), EtapaAgente.DESCARTADA, "Es de la misma ráfaga que «"
                            + mejor.asset().getFileName() + "»: me quedé con esa, que se ve mejor. "
                            + "Si prefieres esta, rescátala.");
                }
            }
        }
        quedan.sort(java.util.Comparator.comparingInt(fotos::indexOf));
        return quedan;
    }

    /**
     * Las fotos de una tanda agrupadas por ráfaga, en su orden. Una foto entra
     * a una ráfaga si se parece a la primera de ella ({@link HuellaDeImagen#RAFAGA}).
     * No entran las que no tienen huella, las que la persona dijo que van, ni
     * las piezas diseñadas: dos flyers con la misma plantilla se parecen y son
     * dos mensajes distintos.
     */
    static List<List<EnTanda>> rafagas(List<EnTanda> fotos) {
        List<List<EnTanda>> grupos = new ArrayList<>();
        for (EnTanda f : fotos) {
            Long h = f.asset().getHuella();
            boolean puede = h != null && !f.forzada() && !f.revision().diagnostico().esArte();
            List<EnTanda> suya = null;
            if (puede) {
                for (List<EnTanda> g : grupos) {
                    EnTanda primera = g.get(0);
                    Long hp = primera.asset().getHuella();
                    if (hp != null && !primera.forzada() && !primera.revision().diagnostico().esArte()
                            && HuellaDeImagen.distancia(h, hp) <= HuellaDeImagen.RAFAGA) {
                        suya = g;
                        break;
                    }
                }
            }
            if (suya == null) {
                suya = new ArrayList<>();
                grupos.add(suya);
            }
            suya.add(f);
        }
        return grupos;
    }

    /**
     * La mejor toma de una ráfaga: la que la IA calificó mejor (calidad y
     * fuerza) y, si empatan, la más nítida. La nitidez solo se mide aquí, en
     * las pocas que compiten.
     */
    static EnTanda mejorDe(List<EnTanda> rafaga, java.util.function.Function<MediaAsset, Double> nitidez) {
        if (rafaga.size() == 1) {
            return rafaga.get(0);
        }
        Map<EnTanda, Double> nitidas = new java.util.IdentityHashMap<>();
        java.util.function.Function<EnTanda, Double> nitidezDe = f -> nitidas.computeIfAbsent(f, x -> {
            Double n = nitidez.apply(x.asset());
            return n == null ? -1.0 : n;
        });
        java.util.Comparator<EnTanda> mejor = java.util.Comparator
                .comparingInt((EnTanda f) -> f.revision().diagnostico().calidad() + f.revision().diagnostico().fuerza())
                .thenComparing(nitidezDe::apply);
        EnTanda gana = rafaga.get(0);
        for (EnTanda f : rafaga) {
            if (mejor.compare(f, gana) > 0) {
                gana = f;
            }
        }
        return gana;
    }

    /** Las analizadas partidas en tandas: una pausa de más de {@link #SESION_MINUTOS} corta. */
    static List<List<MediaAsset>> tandas(List<MediaAsset> enOrden) {
        List<List<MediaAsset>> tandas = new ArrayList<>();
        List<MediaAsset> actual = new ArrayList<>();
        LocalDateTime previa = null;
        for (MediaAsset a : enOrden) {
            LocalDateTime t = a.getCreatedAt();
            if (previa != null && t != null && t.isAfter(previa.plusMinutes(SESION_MINUTOS)) && !actual.isEmpty()) {
                tandas.add(actual);
                actual = new ArrayList<>();
            }
            actual.add(a);
            if (t != null) {
                previa = t;
            }
        }
        if (!actual.isEmpty()) {
            tandas.add(actual);
        }
        return tandas;
    }

    /**
     * Arma las publicaciones de una tanda: la IA propone (si hay dos o más) y
     * las reglas mandan. Cada grupo se produce con su formato.
     *
     * @param instruccion lo que pidió la persona al rehacer un carrusel, o nulo
     */
    private int organizarTanda(Workspace w, List<SocialAccount> destino, List<EnTanda> fotos, String instruccion) {
        List<OrganizadorDeContenido.Foto> numeradas = new ArrayList<>();
        for (int i = 0; i < fotos.size(); i++) {
            numeradas.add(new OrganizadorDeContenido.Foto(i + 1, fotos.get(i).revision()));
        }
        boolean hayHistorias = !cuentasPara(PostFormat.STORY).isEmpty();
        List<OrganizadorDeContenido.Grupo> grupos = OrganizadorDeContenido.normalizar(
                fotos.size() >= 2 ? organizador.proponer(numeradas, instruccion) : null,
                numeradas, formatos.de(PostFormat.PHOTO).maxArchivos(), hayHistorias);
        int hechas = 0;
        for (OrganizadorDeContenido.Grupo g : grupos) {
            List<EnTanda> suyas = g.fotos().stream().map(n -> fotos.get(n - 1)).toList();
            try {
                boolean ok = g.formato() == OrganizadorDeContenido.Formato.CARRUSEL
                        ? proponerCarrusel(w, destino, suyas, g.tema(), g.porque())
                        : proponerFoto(suyas.get(0).asset(), w, destino, suyas.get(0).revision(),
                                suyas.get(0).forzada(), null, g.formato() == OrganizadorDeContenido.Formato.HISTORIA);
                if (ok) {
                    hechas++;
                }
            } catch (RuntimeException ex) {
                log.warn("El agente no pudo armar una publicación de la tanda: {}", ex.toString());
                suyas.forEach(f -> marcar(f.asset(), EtapaAgente.PENDIENTE,
                        "No pude preparar la publicación; lo vuelvo a intentar en un rato."));
            }
        }
        return hechas;
    }

    /**
     * Un carrusel: las fotos reales en el orden del organizador (la primera es
     * la portada), cada una con su retoque si lo pide, el logo solo en la
     * portada, y un texto que habla del conjunto. Sin diseño con IA: no gasta
     * créditos. Una foto que no da la calidad sale del carrusel a Observación;
     * si quedan menos de dos, va como post.
     */
    private boolean proponerCarrusel(Workspace w, List<SocialAccount> destino, List<EnTanda> fotos, String tema,
            String porque) {
        List<EnTanda> quedan = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<String> pasos = new ArrayList<>();
        int retocadas = 0;
        int mejoradas = 0;
        boolean conLogo = false;
        for (EnTanda f : fotos) {
            // Cero diseños disponibles: en un carrusel no se diseña, se cuida la foto.
            DecisorDelAgente.Decision d = DecisorDelAgente.decidir(f.revision().diagnostico(),
                    new DecisorDelAgente.Contexto(0, ajusteDeDiseno(w), w.rasgos()));
            if (d.tratamiento() == DecisorDelAgente.Tratamiento.OBSERVACION) {
                marcar(f.asset(), EtapaAgente.OBSERVACION, d.explicacion());
                continue;
            }
            FotoLista lista = prepararFoto(f.asset(), w, f.revision(), d, false, urls.isEmpty() && d.logo());
            if (lista.decision().tratamiento() == DecisorDelAgente.Tratamiento.MEJORA) {
                mejoradas++;
            } else if (lista.decision().tratamiento() == DecisorDelAgente.Tratamiento.RETOQUE) {
                retocadas++;
            }
            conLogo |= lista.conLogo();
            quedan.add(f);
            urls.add(lista.url());
        }
        if (quedan.size() < 2) {
            boolean alguna = false;
            for (EnTanda f : quedan) {
                alguna |= proponerFoto(f.asset(), w, destino, f.revision(), f.forzada(), null, false);
            }
            return alguna;
        }
        if (mejoradas > 0) {
            pasos.add(mejoradas == 1 ? "Mejoré una foto con IA, cuidando que se vea real."
                    : "Mejoré " + mejoradas + " fotos con IA, cuidando que se vean reales.");
        }
        if (retocadas > 0) {
            pasos.add(retocadas == 1 ? "Retoqué una foto." : "Retoqué " + retocadas + " fotos.");
        }
        if (conLogo) {
            pasos.add("Le puse tu logo a la portada.");
        }

        EnTanda portada = quedan.get(0);
        Set<Platform> redes = destino.stream().map(SocialAccount::getPlatform)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String sobre = tema == null || tema.isBlank() ? portada.revision().tema() : tema;
        String encargo = "Haz una publicacion tipo carrusel de " + quedan.size() + " fotos"
                + (sobre.isBlank() ? "" : " sobre " + sobre) + ": "
                + (portada.revision().idea().isBlank() ? "presentalo como un recorrido" : portada.revision().idea());
        List<String> queSeVe = new ArrayList<>();
        for (int i = 0; i < quedan.size(); i++) {
            String d = quedan.get(i).revision().descripcion();
            if (!d.isBlank()) {
                queSeVe.add("Foto " + (i + 1) + ": " + d);
            }
        }
        Redactor.Borrador borrador = redactor.redactar(encargo, queSeVe, redes, negocio(w));
        CalendarioDelAgente.Categoria categoria = categoria(portada.revision().diagnostico());
        CalendarioDelAgente.Hueco hueco = hueco(w, categoria);

        PostSaveRequest pedido = new PostSaveRequest();
        pedido.setCaption(texto(borrador));
        pedido.setTitulo(borrador.titulo());
        pedido.setBrief(encargo);
        pedido.setMediaUrls(urls);
        pedido.setFormat(PostFormat.PHOTO.name());
        pedido.setSocialAccountIds(destino.stream().map(SocialAccount::getId).toList());
        Map<String, String> porRed = new LinkedHashMap<>();
        borrador.textos().forEach((red, t) -> porRed.put(red.name(), t));
        pedido.setCaptionsPorRed(porRed);
        ConUbicacion lugar = ubicacionPara(w, redes, portada.revision().tipo());
        pedido.setConUbicacion(lugar.va());

        boolean forzada = quedan.stream().allMatch(EnTanda::forzada);
        String porQue = forzada ? "Me dijiste que va." : "Va con tu marca.";
        String carrusel = " Hice carrusel con " + quedan.size() + " fotos" + (sobre.isBlank() ? "" : " de " + sobre)
                + ": " + (porque == null || porque.isBlank() ? "son del mismo tema y juntas cuentan más." : sinPunto(porque) + ".");
        String motivo = porQue + carrusel + (pasos.isEmpty() ? "" : " " + String.join(" ", pasos)) + lugar.frase()
                + " " + cuandoYDonde(redes, hueco);

        Post creada = postService.crearPropuesta(pedido, hueco.cuando(), motivo, portada.asset().getUrl(),
                "CARRUSEL", categoria.name());
        creada.setAgenteFotosUrls(String.join("\n", quedan.stream().map(f -> f.asset().getUrl()).toList()));
        posts.save(creada);
        quedan.forEach(f -> marcar(f.asset(), EtapaAgente.PROPUESTA, motivo));
        return true;
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
        // Una sola, a mano (Revisar ahora, Sí va, ¿Le cambiamos algo?): se
        // revisa y se propone ya, como post o historia según la regla de una sola.
        RevisorDeMarca.Revision revision = revisarFoto(asset, w, destino, forzar);
        if (revision == null) {
            return asset.getAgenteEtapa() != EtapaAgente.PENDIENTE;
        }
        // Lo que vio, guardado: con esto se sabe después si es del momento (cuánto puede esperar el sí).
        asset.setAgenteAnalisis(RevisorDeMarca.aJson(revision, forzar));
        boolean historia = OrganizadorDeContenido.sola(new OrganizadorDeContenido.Foto(1, revision),
                !cuentasPara(PostFormat.STORY).isEmpty(), true).formato() == OrganizadorDeContenido.Formato.HISTORIA;
        return proponerFoto(asset, w, destino, revision, forzar, cambio, historia);
    }

    /**
     * Una foto ya revisada, como post o como historia.
     *
     * @param historia va de historia: a las redes que las aceptan, sin diseño,
     *                 con su propio calendario
     */
    private boolean proponerFoto(MediaAsset asset, Workspace w, List<SocialAccount> destino,
            RevisorDeMarca.Revision revision, boolean forzar, Cambio cambio, boolean historia) {
        Redactor.Negocio negocio = negocio(w);
        if (historia) {
            List<SocialAccount> deHistorias = cuentasPara(PostFormat.STORY);
            if (deHistorias.isEmpty()) {
                historia = false;
            } else {
                destino = deHistorias;
            }
        }

        // La IA ya calificó la foto; el decisor resuelve qué necesita.
        DecisorDelAgente.Decision decision = DecisorDelAgente.decidir(revision.diagnostico(),
                new DecisorDelAgente.Contexto(disenosDisponibles(w), ajusteDeDiseno(w), w.rasgos()));
        decision = conCambio(decision, cambio, disenosDisponibles(w));
        // Una historia es del momento: se cuida la foto, no se diseña.
        if (historia && decision.tratamiento() == DecisorDelAgente.Tratamiento.DISENO) {
            decision = new DecisorDelAgente.Decision(DecisorDelAgente.Tratamiento.TAL_CUAL, decision.logo(),
                    decision.prioridad(), decision.pasos());
        }
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
                        categoria(revision.diagnostico()), revision.tipo())) {
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

            CalendarioDelAgente.Hueco hueco = historia ? huecoDeHistoria(w) : hueco(w, categoria(revision.diagnostico()));
            LocalDateTime fecha = hueco.cuando();

            // Mejora, retoque y logo sobre copias: la original no se toca. Lo que
            // no se pueda (la IA, ffmpeg, un logo ilegible) se salta sin perder la propuesta.
            FotoLista lista = prepararFoto(asset, w, revision, decision, historia, decision.logo());
            decision = lista.decision();
            List<String> pasos = lista.pasos();
            String publicar = lista.url();

            PostSaveRequest pedido = new PostSaveRequest();
            pedido.setCaption(texto(borrador));
            pedido.setTitulo(borrador.titulo());
            pedido.setBrief(encargo);
            pedido.setMediaUrls(List.of(publicar));
            pedido.setFormat((historia ? PostFormat.STORY : PostFormat.PHOTO).name());
            pedido.setSocialAccountIds(destino.stream().map(SocialAccount::getId).toList());
            Map<String, String> porRed = new LinkedHashMap<>();
            borrador.textos().forEach((red, t) -> porRed.put(red.name(), t));
            pedido.setCaptionsPorRed(porRed);
            ConUbicacion lugar = ubicacionPara(w, redes, revision.tipo());
            pedido.setConUbicacion(lugar.va());

            String deHistoria = historia ? " Va de historia: es del momento y está en vertical." : "";
            String motivo = porQue + " " + String.join(" ", pasos) + deHistoria + lugar.frase() + " "
                    + cuandoYDonde(redes, hueco);
            postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(), decision.tratamiento().name(),
                    categoria(revision.diagnostico()).name());

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

        // Repetido, antes de pagar por mirarlo y escucharlo: el mismo video
        // subido dos veces no son dos Reels. Si la persona lo rescata, va.
        if (!forzar) {
            MediaAsset igual = repetidaDe(video, () -> huellas.deVideo(video, duracion));
            if (igual != null) {
                marcar(video, EtapaAgente.DESCARTADA, "Casi igual a «" + igual.getFileName()
                        + "», que ya trabajé: me quedé con ese.");
                return true;
            }
        }

        // El Analista: lo mira entero y lo escucha.
        Redactor.Negocio negocio = negocioParaRevisar(w);
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
            CalendarioDelAgente.Hueco hueco = decision.formato() == PostFormat.STORY ? huecoDeHistoria(w)
                    : hueco(w, categoria);

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
            ConUbicacion lugar = ubicacionPara(w, redes, analisis.tipo());
            pedido.setConUbicacion(lugar.va());

            String porQue = forzar ? "Me dijiste que va." : "Va con tu marca: " + sinPunto(analisis.motivo()) + ".";
            String fuera = todas.size() > destino.size()
                    ? " " + todas.stream().filter(c -> !destino.contains(c)).map(c -> c.getPlatform().getLabel())
                            .distinct().collect(Collectors.joining(" y ")) + " no: dura más de lo que acepta."
                    : "";
            String motivo = porQue + " " + decision.explicacion() + fuera + lugar.frase() + " " + cuandoYDonde(redes, hueco);
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
            String encargo, DecisorDelAgente.Decision decision, String porQue, CalendarioDelAgente.Categoria categoria,
            String tipo) {
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
            crearVersiones(w, diseno, destino, redes, encargo, decision, porQue, categoria, hueco, fecha, asset,
                    disenoId, creadas, tipo);
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

    private void crearVersiones(Workspace w, CampaignImageService.Diseno diseno, List<SocialAccount> destino, Set<Platform> redes,
            String encargo, DecisorDelAgente.Decision decision, String porQue, CalendarioDelAgente.Categoria categoria,
            CalendarioDelAgente.Hueco hueco, LocalDateTime fecha, MediaAsset asset, String disenoId, List<Post> creadas,
            String tipo) {
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
            // Cada versión va a sus redes: una solo para Facebook no lleva ubicación.
            ConUbicacion lugar = ubicacionPara(w, deEsta, tipo);
            pedido.setConUbicacion(lugar.va());
            String motivo = porQue + " " + decision.explicacion() + " (" + creditos.porGeneracion() + " créditos)"
                    + lugar.frase() + " " + cuandoYDonde(deEsta, hueco);
            Post creada = postService.crearPropuesta(pedido, fecha, motivo, asset.getUrl(),
                    DecisorDelAgente.Tratamiento.DISENO.name(), categoria == null ? null : categoria.name());
            creada.setAgenteDisenoId(disenoId);
            creadas.add(posts.save(creada));
        }
    }

    /**
     * Si la publicación sale con la ubicación del negocio, y cómo se dice en el porqué («» si no aplica).
     *
     * @param va {@code true}/{@code false} cuando se decidió por lo que se ve; {@code null} cuando
     *           todavía no aplica (el negocio no la puso, o ninguna red de esta publicación tiene
     *           lugar). Nulo no se guarda como "sin ubicación": si después la configura, sale con ella.
     */
    record ConUbicacion(Boolean va, String frase) {
    }

    /**
     * La ubicación va solo si el negocio tiene local, alguna red del envío
     * tiene su lugar guardado, y lo que se ve es del negocio ({@link UbicacionEnLaPublicacion}).
     * Que lo que se ve no sea del local sí se guarda: esa publicación no la lleva aunque luego haya.
     */
    static ConUbicacion ubicacionPara(Workspace w, Set<Platform> redes, String tipo) {
        if (!UbicacionEnLaPublicacion.va(tipo)) {
            boolean puesta = com.metricol.api.service.social.Ubicacion.de(w).alguna();
            return new ConUbicacion(false, puesta ? " Sin ubicación: lo que se ve no es de tu local." : "");
        }
        com.metricol.api.service.social.Ubicacion u = com.metricol.api.service.social.Ubicacion.de(w);
        boolean conLugar = (u.enInstagram() && redes.contains(Platform.INSTAGRAM))
                || (u.enTiktok() && redes.contains(Platform.TIKTOK));
        if (!conLugar) {
            return new ConUbicacion(null, "");
        }
        return new ConUbicacion(true, " Le puse tu ubicación: es de tu negocio.");
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

    /** El siguiente hueco para una historia: su propio ritmo, aparte del feed. */
    private CalendarioDelAgente.Hueco huecoDeHistoria(Workspace w) {
        LocalDateTime ahora = LocalDateTime.now();
        return CalendarioDelAgente.siguienteHuecoDeHistoria(ahora, posts.historiasTomadas(ahora), horario(w));
    }

    /** El siguiente hueco que respeta el horario, los topes y la mezcla de la semana. */
    private CalendarioDelAgente.Hueco hueco(Workspace w, CalendarioDelAgente.Categoria categoria) {
        LocalDateTime ahora = LocalDateTime.now();
        List<CalendarioDelAgente.Tomado> tomados = posts.tomadosConCategoria(ahora).stream()
                .map(r -> new CalendarioDelAgente.Tomado((LocalDateTime) r[0], (String) r[1]))
                .toList();
        return conRazon(w, CalendarioDelAgente.siguienteHueco(ahora, tomados, limites.maxPorDia(),
                horarioAprendido(w), categoria));
    }

    /**
     * El horario del negocio con las horas que mejor le funcionan, cuando ya
     * hay publicaciones medidas suficientes para saberlo.
     */
    CalendarioDelAgente.Horario horarioAprendido(Workspace w) {
        com.metricol.api.service.metricas.AprendizajeDeRendimiento.Aprendido a = loQueFunciona.de(w.getId());
        return a.suficiente() ? horario(w).conPreferidas(a.horas()) : horario(w);
    }

    /** Si el hueco cae en una de sus mejores horas, se dice: es parte del porqué. */
    private CalendarioDelAgente.Hueco conRazon(Workspace w, CalendarioDelAgente.Hueco hueco) {
        if (hueco.razon() == null && com.metricol.api.service.metricas.LoQueFunciona.esBuenaHora(
                loQueFunciona.de(w.getId()), hueco.cuando().toLocalTime())) {
            return new CalendarioDelAgente.Hueco(hueco.cuando(),
                    "Es de las horas en que mejor te va, según cómo les fue a tus publicaciones.");
        }
        return hueco;
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

    /**
     * Antes de la primera foto, cómo trabaja el negocio: de ahí sale qué
     * aplica (logo en obras, cotizar en vez de precios, cuidado en lo
     * regulado). Una vez; si falla, la siguiente vuelta lo intenta.
     */
    void asegurarPerfil(Workspace w) {
        if (w.getPerfilRasgos() != null) {
            return;
        }
        Set<com.metricol.api.enums.RasgoDelNegocio> rasgos = perfilador.deducir(w);
        if (rasgos != null) {
            w.setPerfilRasgos(com.metricol.api.enums.RasgoDelNegocio.guardar(rasgos));
            workspaces.save(w);
            log.info("Perfil de {}: {}", w.getId(), w.getPerfilRasgos());
        }
    }

    /** La foto lista para publicar, lo que se decidió al final y cada paso en palabras. */
    record FotoLista(String url, DecisorDelAgente.Decision decision, List<String> pasos, boolean conLogo) {
    }

    /**
     * Del original a lo que se publica, como lo haría un fotógrafo con su
     * diseñador:
     * <ol>
     * <li>El director de foto la mira en alta resolución: si un retoque fiel
     * la vuelve profesional, escribe la mejora a su medida, y dice dónde y de
     * qué tamaño va el logo.</li>
     * <li>La mejora sale con IA y se compara con la original; si inventó
     * algo, se tira y va el retoque sencillo.</li>
     * <li>El logo va donde dijo el director, sin placa si se lee directo.</li>
     * </ol>
     * Sin director (sin IA, o falló), todo sigue como antes: retoque fijo y
     * logo abajo a la derecha.
     */
    FotoLista prepararFoto(MediaAsset asset, Workspace w, RevisorDeMarca.Revision revision,
            DecisorDelAgente.Decision decision, boolean historia, boolean conLogo) {
        com.metricol.api.service.agente.foto.DirectorDeFoto.Direccion direccion = null;
        boolean fotoTalCual = decision.tratamiento() == DecisorDelAgente.Tratamiento.TAL_CUAL
                || decision.tratamiento() == DecisorDelAgente.Tratamiento.RETOQUE;
        if (fotoTalCual && !revision.diagnostico().esArte()) {
            direccion = director.dirigir(asset.getUrl(), negocio(w), revision.descripcion());
        }
        if (direccion != null) {
            decision = DecisorDelAgente.conDireccion(decision, direccion.mejorar(), direccion.deficienciasEnFrase(),
                    direccion.mejorar() && mejora.quedanHoy(w.getId()) > 0);
        }
        List<String> pasos = new ArrayList<>(decision.pasos());

        MediaAsset base = asset;
        if (decision.tratamiento() == DecisorDelAgente.Tratamiento.MEJORA) {
            com.metricol.api.service.agente.foto.MejoraDeFoto.Mejorada mejorada =
                    mejora.mejorar(asset, w.getId(), direccion);
            if (mejorada != null && mejorada.salio()) {
                base = mejorada.asset();
                if (!direccion.quitar().isEmpty()) {
                    pasos.add("Le quité lo que distraía de la foto, sin tocar tu trabajo.");
                }
            } else {
                pasos.add(mejorada == null || mejorada.noSalio() == null ? "La mejora no salió." : mejorada.noSalio());
                decision = new DecisorDelAgente.Decision(DecisorDelAgente.Tratamiento.RETOQUE, decision.logo(),
                        decision.prioridad(), decision.pasos());
            }
        }
        if (decision.tratamiento() == DecisorDelAgente.Tratamiento.RETOQUE && base == asset) {
            MediaAsset retocada = retoque.retocar(asset, w.getId());
            if (retocada != null) {
                base = retocada;
                if (direccion != null && direccion.mejorar()) {
                    pasos.add("Le hice un retoque sencillo de luz y color.");
                }
            } else {
                pasos.add("El retoque no salió; va como vino.");
            }
        }

        String url = base.getUrl();
        boolean sellada = false;
        if (direccion != null && (conLogo || direccion.diseno())) {
            // El acabado de diseñador: encuadre, estilo (limpio, franja o marco) y logo sin fondo.
            String acabada = logo.acabar(base, w.getLogoUrl(), w.getId(),
                    new com.metricol.api.service.campaign.LogoSobreFoto.Acabado(direccion.estilo(),
                            direccion.logoZona(), direccion.logoTamano().ancho, direccion.encuadre(),
                            direccion.rotulo(), w.getName(), historia),
                    conLogo);
            if (acabada != null) {
                url = acabada;
                sellada = conLogo && w.getLogoUrl() != null;
                if (direccion.encuadre() != null) {
                    pasos.add("La encuadré para que el trabajo luzca.");
                }
                if (sellada) {
                    pasos.add(switch (historia ? "LIMPIO" : direccion.estilo()) {
                        case "FRANJA" -> "La vestí con una franja de tu marca: «" + direccion.rotulo() + "».";
                        case "MARCO" -> "Le puse un marco con el color de tu marca y tu logo "
                                + dondeVa(direccion.logoZona()) + ".";
                        default -> "Puse tu logo sin fondo " + dondeVa(direccion.logoZona())
                                + ", donde no tapa lo importante.";
                    });
                }
                return new FotoLista(url, decision, pasos, sellada);
            }
        }
        if (conLogo) {
            String conSello = direccion == null ? logo.sellar(base, w.getLogoUrl(), w.getId())
                    : logo.sellar(base, w.getLogoUrl(), w.getId(), direccion.logoZona(),
                            direccion.logoTamano().ancho, historia);
            if (conSello != null) {
                url = conSello;
                sellada = true;
                if (direccion != null) {
                    pasos.add("Puse tu logo " + dondeVa(direccion.logoZona()) + ", donde no tapa lo importante.");
                }
            } else {
                pasos.add(w.getLogoUrl() == null ? "No hay un logo guardado en tu marca." : "El logo no se pudo pegar.");
            }
        }
        return new FotoLista(url, decision, pasos, sellada);
    }

    private static String dondeVa(String zona) {
        return switch (zona) {
            case "TOP_LEFT" -> "arriba a la izquierda";
            case "TOP_CENTER" -> "arriba al centro";
            case "TOP_RIGHT" -> "arriba a la derecha";
            case "BOTTOM_LEFT" -> "abajo a la izquierda";
            case "BOTTOM_CENTER" -> "abajo al centro";
            default -> "abajo a la derecha";
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
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime limite = ahora.plusMinutes(30);
        java.util.Set<UUID> hechas = new java.util.HashSet<>();
        boolean caducaron = false;
        for (Post p : posts.propuestasDelAgente()) {
            if (hechas.contains(p.getId()) || p.getFechaPropuesta() == null) {
                continue;
            }
            // Las versiones de un diseño se mueven juntas: son una publicación.
            List<Post> grupo = grupoDe(p);
            grupo.forEach(g -> hechas.add(g.getId()));
            LocalDateTime caduca = caducidad(p);
            if (!ahora.isBefore(caduca)) {
                caducar(grupo);
                caducaron = true;
                continue;
            }
            if (!p.getFechaPropuesta().isBefore(limite)) {
                continue;
            }
            LocalDateTime nueva = huecoSin(w, grupo).cuando();
            // Lo del momento no se pasa a cuando ya no tiene sentido.
            if (nueva.isAfter(caduca)) {
                caducar(grupo);
                caducaron = true;
                continue;
            }
            grupo.forEach(g -> moverA(g, nueva, true));
        }
        // Quedaron huecos: lo que venía después se adelanta.
        if (caducaron) {
            replanear(w);
        }
    }

    // ------------------------------------------------------------ avisos al teléfono

    /**
     * Avisa al equipo, sin molestar:
     * <ol>
     * <li>Lo que se le acaba el tiempo (menos de 3 h para su hora o para
     * retirarse): un aviso por propuesta, una sola vez.</li>
     * <li>Lo nuevo: todo lo que no se ha avisado, en un solo aviso; como mucho
     * uno por hora y solo de {@value #AVISOS_DESDE} a {@value #AVISOS_HASTA} h.
     * Lo de la noche se avisa a la mañana siguiente, junto.</li>
     * </ol>
     */
    void avisar(Workspace w, LocalDateTime ahora) {
        if (!avisos.activo()) {
            return;
        }
        List<Post> pendientes = posts.propuestasDelAgente();
        boolean deDia = ahora.getHour() >= AVISOS_DESDE && ahora.getHour() < AVISOS_HASTA;

        List<Post> urgentes = pendientes.stream()
                .filter(p -> !Boolean.TRUE.equals(p.getAgenteAvisoUrgente()) && porVencer(p, ahora))
                .sorted(java.util.Comparator.comparing(AgenteService::limiteDelSi))
                .toList();
        if (!urgentes.isEmpty() && ahora.getHour() >= 7 && ahora.getHour() < 22) {
            int n = publicacionesEn(urgentes);
            String antes = horaEnPalabras(limiteDelSi(urgentes.get(0)));
            String cuerpo = n == 1
                    ? "Una publicación necesita tu sí antes de las " + antes + "."
                    : n + " publicaciones necesitan tu sí pronto. La primera, antes de las " + antes + ".";
            if (avisos.avisarAlEquipo(w.getId(), w.getName(), cuerpo, Map.of("tipo", "urgente"))) {
                for (Post p : urgentes) {
                    p.setAgenteAvisoUrgente(true);
                    if (p.getAgenteAvisadaEn() == null) {
                        p.setAgenteAvisadaEn(ahora);
                    }
                    posts.save(p);
                }
            }
        }

        boolean toca = w.getAgenteUltimoAviso() == null
                || !w.getAgenteUltimoAviso().plus(ENTRE_AVISOS).isAfter(ahora);
        List<Post> nuevas = posts.propuestasDelAgente().stream().filter(p -> p.getAgenteAvisadaEn() == null).toList();
        if (deDia && toca && !nuevas.isEmpty()) {
            int n = publicacionesEn(nuevas);
            String cuerpo = n == 1
                    ? "Tu asistente te preparó una publicación. ¿La revisas?"
                    : "Tu asistente te preparó " + n + " publicaciones. ¿Las revisas?";
            if (avisos.avisarAlEquipo(w.getId(), w.getName(), cuerpo, Map.of("tipo", "nuevas"))) {
                for (Post p : nuevas) {
                    p.setAgenteAvisadaEn(ahora);
                    posts.save(p);
                }
                w.setAgenteUltimoAviso(ahora);
                workspaces.save(w);
            }
        }
    }

    /** Lo que llegue primero: su hora de salir o su caducidad. */
    static LocalDateTime limiteDelSi(Post p) {
        LocalDateTime fecha = p.getFechaPropuesta();
        LocalDateTime caduca = p.getAgenteCaducaEn();
        if (fecha == null) {
            return caduca == null ? LocalDateTime.MAX : caduca;
        }
        return caduca == null || fecha.isBefore(caduca) ? fecha : caduca;
    }

    static boolean porVencer(Post p, LocalDateTime ahora) {
        LocalDateTime limite = limiteDelSi(p);
        return limite.isAfter(ahora) && limite.isBefore(ahora.plusHours(3));
    }

    /** Las versiones de un diseño son una sola publicación para quien lee el aviso. */
    private static int publicacionesEn(List<Post> propuestas) {
        return (int) propuestas.stream()
                .map(p -> p.getAgenteDisenoId() != null ? (Object) p.getAgenteDisenoId() : p.getId())
                .distinct().count();
    }

    /** "4:00 pm", como lo dice la gente. */
    static String horaEnPalabras(LocalDateTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return h + ":" + String.format("%02d", t.getMinute()) + (t.getHour() < 12 ? " am" : " pm");
    }

    /** Cuánto puede esperar el sí lo del momento (una historia, la promoción de hoy). */
    static final java.time.Duration VIDA_DEL_MOMENTO = java.time.Duration.ofHours(24);

    /** Cuánto puede esperar lo demás: después de dos semanas, mejor preguntar. */
    static final java.time.Duration VIDA_NORMAL = java.time.Duration.ofDays(14);

    /**
     * Hasta cuándo puede esperar el sí, calculada una vez y guardada. Se
     * calcula en la primera vuelta tras crearla, cuando su fecha es todavía
     * la original: lo del momento vive un día, o hasta tres horas después de
     * esa fecha si cae más tarde (que no caduque antes de su primera hora); lo
     * demás, dos semanas o hasta tres días después de esa fecha.
     */
    LocalDateTime caducidad(Post p) {
        if (p.getAgenteCaducaEn() != null) {
            return p.getAgenteCaducaEn();
        }
        LocalDateTime creada = p.getCreatedAt() != null ? p.getCreatedAt() : LocalDateTime.now();
        LocalDateTime caduca;
        if (delMomento(p)) {
            caduca = creada.plus(VIDA_DEL_MOMENTO);
            if (p.getFechaPropuesta() != null && p.getFechaPropuesta().plusHours(3).isAfter(caduca)) {
                caduca = p.getFechaPropuesta().plusHours(3);
            }
        } else {
            // Con el calendario lleno, una puede quedar a más de dos semanas:
            // que no caduque antes de que llegue su hora, y tenga unos días después.
            caduca = creada.plus(VIDA_NORMAL);
            if (p.getFechaPropuesta() != null && p.getFechaPropuesta().plusDays(3).isAfter(caduca)) {
                caduca = p.getFechaPropuesta().plusDays(3);
            }
        }
        p.setAgenteCaducaEn(caduca);
        posts.save(p);
        return caduca;
    }

    /** Una historia, o algo que el revisor o el analista marcaron como del momento. */
    boolean delMomento(Post p) {
        if (p.getFormat() == PostFormat.STORY) {
            return true;
        }
        for (MediaAsset a : assets.findByUrlIn(originalesDe(p))) {
            if (efimero(a.getAgenteAnalisis())) {
                return true;
            }
        }
        return false;
    }

    static boolean efimero(String analisis) {
        if (analisis == null || analisis.isBlank()) {
            return false;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(analisis).path("efimero").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Se le acabó el tiempo sin el sí: no se publica. La propuesta se retira
     * y sus fotos vuelven a preguntar, con el porqué. Nada se pierde: con
     * "sí, va" se prepara de nuevo, con una fecha que sí tiene sentido.
     */
    private void caducar(List<Post> grupo) {
        Post p = grupo.get(0);
        boolean momento = delMomento(p);
        for (Post g : grupo) {
            postService.delete(g.getId());
        }
        etapaDeSusFotos(p, EtapaAgente.OBSERVACION, momento
                ? "Era del momento y no llegó tu sí a tiempo, así que no la publiqué. ¿Todavía va?"
                : "Esperó tu sí dos semanas y no la publiqué. ¿Todavía va?");
        log.info("Propuesta {} caducó sin aprobarse ({}).", p.getId(), momento ? "del momento" : "dos semanas");
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
        if (grupo.get(0).getFormat() == PostFormat.STORY) {
            List<LocalDateTime> historias = new ArrayList<>();
            for (LocalDateTime cuando : posts.historiasTomadas(ahora)) {
                if (porQuitar > 0 && cuando != null && cuando.equals(suya)) {
                    porQuitar--;
                    continue;
                }
                historias.add(cuando);
            }
            return CalendarioDelAgente.siguienteHuecoDeHistoria(ahora, historias, horario(w));
        }
        List<CalendarioDelAgente.Tomado> tomados = new ArrayList<>();
        for (Object[] r : posts.tomadosConCategoria(ahora)) {
            LocalDateTime cuando = (LocalDateTime) r[0];
            if (porQuitar > 0 && cuando != null && cuando.equals(suya)) {
                porQuitar--;
                continue;
            }
            tomados.add(new CalendarioDelAgente.Tomado(cuando, (String) r[1]));
        }
        return CalendarioDelAgente.siguienteHueco(ahora, tomados, limites.maxPorDia(), horarioAprendido(w),
                CalendarioDelAgente.Categoria.de(grupo.get(0).getAgenteCategoria()));
    }

    /** La mueve de fecha y corrige la fecha que dice su explicación, para que no mienta. */
    private void moverA(Post p, LocalDateTime nueva) {
        moverA(p, nueva, false);
    }

    /** @param porTarde se movió porque no llegó el sí a tiempo (no por adelantarla): se le dice */
    private void moverA(Post p, LocalDateTime nueva, boolean porTarde) {
        LocalDateTime vieja = p.getFechaPropuesta();
        p.setFechaPropuesta(nueva);
        if (porTarde) {
            p.setAgenteMovidaVeces((p.getAgenteMovidaVeces() == null ? 0 : p.getAgenteMovidaVeces()) + 1);
        }
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
        // Con el agente en pausa nadie la retiró a tiempo: lo del momento que
        // ya pasó no se publica aunque se apruebe tarde.
        if (!LocalDateTime.now().isBefore(caducidad(post)) && delMomento(post)) {
            caducar(grupo);
            throw new IllegalStateException("Era del momento y su día ya pasó, así que no la publiqué. "
                    + "Te la dejé como pregunta por si todavía va.");
        }
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
        boolean rescate = asset.getAgenteEtapa() == EtapaAgente.OBSERVACION
                || asset.getAgenteEtapa() == EtapaAgente.DESCARTADA;
        if (!tomar(asset)) {
            throw new IllegalStateException("Ya la estoy revisando; en un momento la ves.");
        }
        if (rescate) {
            // Lo que se ve en ella le enseña al revisor que ese tema es de la marca.
            asset.setAgenteRescatada(true);
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
        if (post.getAgenteFotosUrls() != null && !post.getAgenteFotosUrls().isBlank()) {
            return cambiarCarrusel(post, texto, workspaceId);
        }
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

    /**
     * Rehacer un carrusel: sus fotos vuelven a organizarse con lo que pidió la
     * persona ("sepáralas", "quita la 3", "la portada que sea la del frente").
     * No se vuelven a mirar: se usa lo que ya se vio de cada una.
     */
    private List<PostResponse> cambiarCarrusel(Post post, String texto, UUID workspaceId) {
        List<String> originales = originalesDe(post);
        Map<String, MediaAsset> porUrl = new LinkedHashMap<>();
        assets.findByUrlIn(originales).forEach(a -> porUrl.put(a.getUrl(), a));
        cupoIa.exigirCupo();
        for (Post hermana : grupoDe(post)) {
            postService.delete(hermana.getId());
        }
        Workspace w = workspace(workspaceId);
        List<EnTanda> fotos = new ArrayList<>();
        for (String url : originales) {
            MediaAsset a = porUrl.get(url);
            if (a == null) {
                continue;
            }
            RevisorDeMarca.Revision rev = RevisorDeMarca.deJson(a.getAgenteAnalisis());
            if (rev == null) {
                marcar(a, EtapaAgente.PENDIENTE, "La vuelvo a revisar para rehacer el carrusel.");
                continue;
            }
            marcar(a, EtapaAgente.REVISANDO, a.getAgenteMotivo());
            fotos.add(new EnTanda(a, rev, true));
        }
        if (!fotos.isEmpty()) {
            organizarTanda(w, cuentasParaFotos(), fotos, texto);
        }
        replanear(w);
        return postService.propuestasDelAgente(p -> p.getAgenteFotoUrl() != null && originales.contains(p.getAgenteFotoUrl()));
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
        return vuelta(workspaceId, true);
    }

    // ------------------------------------------------------------ piezas

    /**
     * Otra foto ya trabajada que es esta misma, o {@code null}. Calcula y
     * guarda la huella de esta de paso, para que la siguiente se compare
     * contra ella. Sin huella (formato que Java no lee) no hay comparación.
     */
    private MediaAsset repetidaDe(MediaAsset asset) {
        return repetidaDe(asset, () -> huellas.de(asset));
    }

    /**
     * @param calcular cómo sacar la huella si todavía no la tiene (una foto se
     *                 lee entera; un video, por el cuadro de su mitad)
     */
    private MediaAsset repetidaDe(MediaAsset asset, java.util.function.Supplier<Long> calcular) {
        Long h = asset.getHuella() != null ? asset.getHuella() : calcular.get();
        if (h == null) {
            return null;
        }
        if (asset.getHuella() == null) {
            asset.setHuella(h);
            assets.save(asset);
        }
        for (MediaAsset otra : assets.yaTrabajadasConHuella()) {
            // Foto con foto y video con video: el cuadro de un video puede parecerse a una foto suya.
            if (!otra.getId().equals(asset.getId()) && otra.getType() == asset.getType()
                    && HuellaDeImagen.parecidas(h, otra.getHuella())) {
                return otra;
            }
        }
        return null;
    }

    /** Cuántas rescatadas se le cuentan al revisor: las últimas, que es lo que el dueño tiene en mente. */
    private static final int RESCATADAS_EN_CONTEXTO = 8;

    /**
     * El negocio como lo necesita quien revisa: con lo que el dueño ya
     * rescató, para no mandar otra vez a Observación los temas que dijo que sí.
     */
    private Redactor.Negocio negocioParaRevisar(Workspace w) {
        List<String> siVa = assets.rescatadas(PageRequest.of(0, RESCATADAS_EN_CONTEXTO)).stream()
                .map(MediaAsset::getDescripcionIa)
                .map(d -> "- " + (d.length() > 160 ? d.substring(0, 160) + "…" : d.strip()))
                .distinct()
                .toList();
        return negocio(w).conLoQueSiVa(siVa.isEmpty() ? null : String.join("\n", siVa));
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
                // Por reconectar: proponerle sería sumar otra que espera; sale en
                // las demás redes, y la franja de Hoy pide reconectarla.
                .filter(c -> !conexiones.necesitaReconectar(c.getPlatform()))
                .toList();
    }

    private Workspace workspace(UUID id) {
        return workspaces.findById(id).orElseThrow(() -> new ResourceNotFoundException("Espacio no encontrado."));
    }

    private Redactor.Negocio negocio(Workspace w) {
        return new Redactor.Negocio(w.getName(), w.getGiro(), w.getCiudad(), w.getDescripcion(), w.getObjetivo(),
                MarcaDelNegocio.delEspacio(w),
                com.metricol.api.service.metricas.LoQueFunciona.paraElRedactor(loQueFunciona.de(w.getId())));
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

    /** Las fotos originales de una propuesta: las del carrusel, la de una foto, o lo que publica. */
    static List<String> originalesDe(Post post) {
        if (post.getAgenteFotosUrls() != null && !post.getAgenteFotosUrls().isBlank()) {
            return java.util.Arrays.stream(post.getAgenteFotosUrls().split("\n")).map(String::strip)
                    .filter(u -> !u.isEmpty()).toList();
        }
        return post.getAgenteFotoUrl() != null ? List.of(post.getAgenteFotoUrl()) : post.getMediaUrls();
    }

    /** La foto original de la propuesta (no la copia con logo): esa es la que cambia de etapa. */
    private void etapaDeSusFotos(Post post, EtapaAgente etapa, String motivo) {
        List<String> urls = originalesDe(post);
        for (MediaAsset a : assets.findByUrlIn(urls)) {
            marcar(a, etapa, motivo == null ? a.getAgenteMotivo() : motivo);
        }
    }
}
