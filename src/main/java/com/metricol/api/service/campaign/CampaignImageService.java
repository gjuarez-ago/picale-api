package com.metricol.api.service.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.VisualIdentity;
import com.metricol.api.entity.VisualIdentity.LogoTreatment;
import com.metricol.api.service.VisualIdentityService;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.CampaignImageResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.ai.AiUsageRecorder;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.ai.ArtDirector;
import com.metricol.api.service.ai.EspecTexto;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.OpenAiImageClient;
import com.metricol.api.service.ai.OpenAiImageClient.Referencia;
import com.metricol.api.service.ai.OpenAiImageClient.Resultado;
import com.metricol.api.service.campaign.Lienzo.Variante;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

import jakarta.annotation.PreDestroy;

/**
 * Crea el contenido con IA: pide las imágenes a gpt-image, las recorta a la
 * proporción de cada red, les pega el logo real, las guarda en R2 como parte de
 * Contenido y escribe el texto que las acompaña.
 *
 * <p><b>Dos tiempos.</b> {@link #preparar} corre en la petición y hace todo lo
 * que puede fallar sin gastar nada —validar formato y redes, el tope del día,
 * que las fotos sean del workspace—. {@link #ejecutar} es lo lento y puede
 * correr en segundo plano: el director de arte, las imágenes y el guardado. Lo
 * que pasa de uno al otro es un {@link Preparado} que ya no depende de la
 * sesión de base de datos de la petición.
 *
 * <p><b>Una imagen por versión, no por red.</b> Las redes se agrupan por
 * {@link Lienzo}: Instagram y Facebook comparten la 4:5 y LinkedIn tiene la
 * suya. Todas las versiones salen del MISMO plan del director, así el mensaje
 * es el mismo en todas; lo único que cambia es la forma y el texto de cada red.
 *
 * <p><b>Qué protege.</b>
 *
 * <ul>
 * <li>Las fotos de referencia y el logo solo pueden ser archivos del propio
 * workspace: se buscan por URL en su Contenido y no se baja nada que la app
 * mande por su cuenta.
 * <li>El tope diario de imágenes se comprueba con TODAS las piezas antes de
 * pagar la primera.
 * <li>Una versión que falla no tira a las demás; y de un carrusel no se guarda
 * nada a medias.
 * </ul>
 *
 * <p>Las piezas se piden a la vez: una tarda hasta un minuto y varias en fila
 * no cabrían. Esos hilos solo hablan con OpenAI; el gasto y el guardado ocurren
 * en el hilo de quien ejecuta, que es el que sabe de qué workspace se trata.
 */
@Service
public class CampaignImageService {

    private static final Logger log = LoggerFactory.getLogger(CampaignImageService.class);

    private static final int MAX_REFERENCIAS_POR_PIEZA = 4;
    private static final long MAX_BYTES_REFERENCIA = 20L * 1024 * 1024;
    /** Lo que suele pesar una pieza; solo sirve para comprobar el espacio antes de pagarla. */
    private static final long BYTES_ESTIMADOS_POR_PIEZA = 1_500_000L;
    private static final Set<String> TIPOS_ACEPTADOS = Set.of("image/png", "image/jpeg", "image/webp");

    private final OpenAiImageClient imagenes;
    private final OpenAiClient texto;
    private final R2StorageService storage;
    private final MediaAssetRepository assets;
    private final PostRepository posts;
    private final StorageQuotaService cuota;
    private final AiQuotaGuard cupo;
    private final AiUsageRecorder usos;
    private final ArtDirector director;
    private final ObjectMapper json = new ObjectMapper();

    private final ExecutorService pool = Executors.newFixedThreadPool(8, tarea -> {
        Thread hilo = new Thread(tarea, "campaign-image");
        hilo.setDaemon(true);
        return hilo;
    });

    private final CreditService creditos;

    public CampaignImageService(OpenAiImageClient imagenes, OpenAiClient texto, R2StorageService storage,
            MediaAssetRepository assets, PostRepository posts, StorageQuotaService cuota, AiQuotaGuard cupo,
            AiUsageRecorder usos, ArtDirector director, CreditService creditos) {
        this.creditos = creditos;
        this.imagenes = imagenes;
        this.texto = texto;
        this.storage = storage;
        this.assets = assets;
        this.posts = posts;
        this.cuota = cuota;
        this.cupo = cupo;
        this.usos = usos;
        this.director = director;
    }

    @PreDestroy
    void cerrar() {
        pool.shutdownNow();
    }

    /** Lo que se crea y en qué redes puede publicarse. */
    /**
     * Si el diseño en formato post sale para esa red. TikTok no: pedírselo
     * hacía fallar el diseño entero ("TikTok no admite publicaciones").
     */
    public static boolean disenaPostPara(Platform red) {
        return Formato.POST.redes.contains(red);
    }

    enum Formato {
        POST(false, "publicaciones", EnumSet.of(Platform.INSTAGRAM, Platform.FACEBOOK, Platform.LINKEDIN)),
        CAROUSEL(true, "carruseles", EnumSet.of(Platform.INSTAGRAM, Platform.FACEBOOK, Platform.LINKEDIN)),
        STORY(false, "historias", EnumSet.of(Platform.INSTAGRAM, Platform.FACEBOOK));

        final boolean secuencia;
        final String plural;
        final Set<Platform> redes;

        Formato(boolean secuencia, String plural, Set<Platform> redes) {
            this.secuencia = secuencia;
            this.plural = plural;
            this.redes = redes;
        }

        /** El lienzo de siempre cuando no se dice a qué redes va. */
        Lienzo lienzoPorDefecto() {
            return this == STORY ? Lienzo.HISTORIA : Lienzo.CUATRO_QUINTOS;
        }

        static Formato de(String codigo) {
            String limpio = codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
            return switch (limpio) {
                case "POST" -> POST;
                case "CAROUSEL" -> CAROUSEL;
                case "STORY", "HISTORIA" -> STORY;
                default -> throw new IllegalArgumentException("Ese formato de imagen no existe.");
            };
        }
    }

    /**
     * Los datos del negocio, copiados. Quien ejecuta en segundo plano no puede
     * tocar el {@code Workspace} de la petición: es una entidad ligada a una
     * sesión de base de datos que ya se cerró.
     */
    record Negocio(UUID id, String nombre, String giro, String ciudad, String descripcion, String objetivo,
            MarcaDelNegocio marca) {

        /** Sin perfil de marca: lo que se sabía antes de que existiera. */
        Negocio(UUID id, String nombre, String giro, String ciudad, String descripcion, String objetivo) {
            this(id, nombre, giro, ciudad, descripcion, objetivo, MarcaDelNegocio.VACIA);
        }

        static Negocio de(Workspace w) {
            return new Negocio(w.getId(), w.getName(), w.getGiro(), w.getCiudad(), w.getDescripcion(),
                    w.getObjetivo() == null ? null : w.getObjetivo().name(),
                    MarcaDelNegocio.delEspacio(w));
        }
    }

    /** Todo lo validado y descargado, listo para ejecutar sin volver a la petición. */
    record Preparado(
            Negocio negocio,
            Formato formato,
            List<Variante> variantes,
            List<String> recursos,
            Map<String, Referencia> fotos,
            Referencia logo,
            SelloDeLogo.Posicion posicionLogo,
            CampaignImageRequest peticion,
            int piezasPorVariante,
            int totalImagenes,
            int restantes,
            String referenciaCredito,
            VisualIdentity identity, List<VisualReference> visualReferences, Referencia previous,
            LogoTreatment logoTreatment) {
        Preparado(Negocio negocio, Formato formato, List<Variante> variantes, List<String> recursos,
                Map<String, Referencia> fotos, Referencia logo, SelloDeLogo.Posicion posicionLogo,
                CampaignImageRequest peticion, int piezasPorVariante, int totalImagenes, int restantes, String referenciaCredito) {
            this(negocio, formato, variantes, recursos, fotos, logo, posicionLogo, peticion, piezasPorVariante,
                    totalImagenes, restantes, referenciaCredito, VisualIdentity.EMPTY, List.of(), null, LogoTreatment.ORIGINAL);
        }
    }

    record VisualReference(String role, String url, Referencia reference) {}

    /** El resultado de una versión. {@code causa} solo sirve dentro del proceso: es lo que se relanza. */
    record VarianteGenerada(
            String id,
            Lienzo lienzo,
            List<Platform> redes,
            List<String> urls,
            List<String> assetIds,
            String error,
            RuntimeException causa, VisualQualityReview.Review review) {
        VarianteGenerada(String id, Lienzo lienzo, List<Platform> redes, List<String> urls, List<String> assetIds,
                String error, RuntimeException causa) {
            this(id, lienzo, redes, urls, assetIds, error, causa, VisualQualityReview.Review.unavailable());
        }
    }

    record Generado(
            List<VarianteGenerada> variantes,
            String titular,
            String subtitulo,
            String captionGeneral,
            Map<Platform, String> captionsPorRed,
            String prompt,
            int restantes) {
    }

    /** Para que quien ejecuta en segundo plano vaya contando cómo va. */
    interface Progreso {
        default UUID trabajoId() { return null; }
        /** Fence durable antes de pagos; por defecto conserva el contrato síncrono. */
        default void antesDeProveedor() {}
        default void piezaLista(VarianteGenerada parcial) {}
        void etapa(String etapa);

        void varianteLista(VarianteGenerada variante);

        Progreso NINGUNO = new Progreso() {
            @Override
            public void etapa(String etapa) {
            }

            @Override
            public void varianteLista(VarianteGenerada variante) {
            }
        };
    }

    // ------------------------------------------------------------ el camino de una imagen

    /** El endpoint de siempre: una imagen, en la misma petición. */
    public CampaignImageResponse generar(User usuario, CampaignImageRequest peticion) {
        Preparado p = preparar(usuario, peticion);
        Generado g;
        try {
            g = ejecutar(p, Progreso.NINGUNO);
        } catch (RuntimeException ex) {
            devolverCredito(p);
            throw ex;
        }
        VarianteGenerada v = g.variantes().get(0);
        if (v.causa() != null) {
            if (v.urls().isEmpty()) devolverCredito(p);
            throw v.causa();
        }
        return new CampaignImageResponse(
                v.assetIds().get(0),
                versionDe(peticion),
                "PRODUCT",
                g.titular(),
                g.subtitulo(),
                v.urls().get(0),
                v.urls(),
                g.captionGeneral(),
                g.prompt(),
                p.totalImagenes(),
                g.restantes() == Integer.MAX_VALUE ? null : g.restantes());
    }

    /**
     * Devuelve el crédito de una generación que no produjo nada. Nunca lanza:
     * es lo último que se hace al fallar, y un error aquí taparía el verdadero.
     */
    void devolverCredito(Preparado p) {
        try {
            if (p != null && p.referenciaCredito() != null) {
                creditos.devolverGeneracion(p.negocio().id(), p.referenciaCredito());
            }
        } catch (Exception ex) {
            log.warn("No se pudo devolver el crédito de la generación {}: {}", p.referenciaCredito(), ex.toString());
        }
    }

    // ------------------------------------------------------------ preparar

    /**
     * Valida y reúne todo, sin gastar nada. Corre en la petición: aquí es donde
     * se le dice a la persona lo que está mal, y falla antes de crear ningún
     * trabajo.
     */
    Preparado preparar(User usuario, CampaignImageRequest peticion) {
        return preparar(usuario == null ? null : usuario.getWorkspace(), peticion);
    }

    Preparado prepararSinCobro(User usuario, CampaignImageRequest peticion) {
        return preparar(usuario == null ? null : usuario.getWorkspace(), peticion, false);
    }

    static Preparado conRestantes(Preparado p, int restantes) {
        return new Preparado(p.negocio(), p.formato(), p.variantes(), p.recursos(), p.fotos(), p.logo(), p.posicionLogo(),
                p.peticion(), p.piezasPorVariante(), p.totalImagenes(), restantes, p.referenciaCredito(),
                p.identity(), p.visualReferences(), p.previous(), p.logoTreatment());
    }

    /**
     * Un diseño pedido por el agente: cada versión con sus redes y su imagen.
     * Público y sin los tipos internos para que el agente no dependa de cómo
     * se genera por dentro.
     */
    public record Diseno(List<Version> versiones, String titular, Map<Platform, String> captionsPorRed) {
        public record Version(List<Platform> redes, String url) {
        }
    }

    /**
     * Diseña de una vez, en el hilo de quien llama, para el agente: no hay
     * persona mirando una barra de progreso, así que no hace falta el trabajo
     * en segundo plano de {@code ContenidoJobs}. Cobra 1 crédito y lo devuelve
     * si no sale ninguna versión, igual que desde la app.
     *
     * @return el diseño, o {@code null} si no salió ninguna versión
     */
    public Diseno disenarParaElAgente(Workspace workspace, CampaignImageRequest peticion) {
        Preparado p = preparar(workspace, peticion);
        Generado g;
        try {
            g = ejecutar(p, Progreso.NINGUNO);
        } catch (RuntimeException ex) {
            devolverCredito(p);
            throw ex;
        }
        List<Diseno.Version> versiones = new ArrayList<>();
        for (VarianteGenerada v : g.variantes()) {
            if (v.causa() == null && !v.urls().isEmpty()) {
                versiones.add(new Diseno.Version(List.copyOf(v.redes()), v.urls().get(0)));
            }
        }
        if (versiones.isEmpty()) {
            if (g.variantes().stream().allMatch(v -> v.urls().isEmpty())) devolverCredito(p);
            return null;
        }
        return new Diseno(versiones, g.titular(), g.captionsPorRed());
    }

    private Preparado preparar(Workspace workspace, CampaignImageRequest peticion) {
        return preparar(workspace, peticion, true);
    }

    private Preparado preparar(Workspace workspace, CampaignImageRequest peticion, boolean cobrar) {
        if (workspace == null) throw new IllegalStateException("Elige un espacio de trabajo para crear el contenido.");
        VisualIdentity identity = VisualIdentityService.of(workspace);
        var creative = peticion.creative();
        boolean retouch = photoRetouch(peticion);
        if (retouch && creative != null && (creative.logoTreatment() == LogoTreatment.INTEGRATED
                || Boolean.TRUE.equals(creative.includeChispa()) || creative.exactText() != null && !creative.exactText().isBlank()
                || creative.slideTexts() != null && creative.slideTexts().stream().anyMatch(t -> t != null && !t.isBlank())
                || Boolean.TRUE.equals(peticion.ctaEnImagen())))
            throw new IllegalArgumentException("El retoque conserva la fotografía sin texto ni personajes nuevos. Para un diseño publicitario elige crear anuncio.");
        LogoTreatment treatment = creative != null && creative.logoTreatment() != null
                ? creative.logoTreatment() : identity.logoTreatment();
        Formato formato = Formato.de(peticion.format() == null ? null : peticion.format().code());
        String logoUrl = peticion.brand() == null || peticion.brand().logoUrl() == null
                || peticion.brand().logoUrl().isBlank() ? workspace.getLogoUrl() : peticion.brand().logoUrl().trim();
        if (Boolean.TRUE.equals(workspace.getLogoEsFoto())) logoUrl = null;
        List<String> recursos = fotosDe(formato, peticion.resourceUrls(), logoUrl);

        int piezas = 1;
        if (formato.secuencia) {
            if (recursos.size() < 2 || recursos.size() > 5) {
                throw new IllegalArgumentException(
                        "Un carrusel necesita entre 2 y 5 fotos. Tu logo no cuenta: se agrega solo.");
            }
            piezas = recursos.size();
        }
        if (creative != null && creative.slideTexts() != null
                && (!formato.secuencia || creative.slideTexts().size() != piezas))
            throw new IllegalArgumentException("Indica un texto por diapositiva, conservando el mismo orden que las fotos.");

        if (workspace == null) {
            throw new IllegalStateException("Elige un espacio de trabajo para crear el contenido.");
        }

        List<Variante> variantes = variantesDe(peticion, formato);
        int total = piezas * variantes.size();

        // Todo lo que puede fallar sin haber gastado nada, primero.
        if (!imagenes.disponible()) {
            throw new IllegalStateException("La generación de imágenes no está disponible por ahora.");
        }
        cupo.exigirCupo();
        int restantes = cupo.exigirCupoImagenes(total);
        cuota.verificar(BYTES_ESTIMADOS_POR_PIEZA * total, "contenido.jpg");

        Map<String, Referencia> fotos = cargarReferencias(recursos, workspace.getId());
        SelloDeLogo.Posicion posicionLogo = retouch && (creative == null || creative.logoTreatment() == null)
                ? null : posicionDelLogo(peticion, formato);
        Referencia logo = posicionLogo == null ? null : cargarLogo(logoUrl, workspace.getId());
        if (logo != null && treatment == LogoTreatment.ORIGINAL)
            SelloDeLogo.leer(logo.bytes(), "El logo no se puede leer. Usa una versión PNG o JPG antes de crear la pieza.");
        if (treatment == LogoTreatment.INTEGRATED && posicionLogo != null && logo == null)
            throw new IllegalArgumentException("Para integrar tu logo necesito su archivo original disponible en este espacio.");
        // Se valida antes del cobro, aunque la separación final sea una copia derivada.
        if (logo != null && treatment == LogoTreatment.ORIGINAL && identity.logoBackground() == VisualIdentity.LogoBackground.REMOVE_SOLID)
            LogoBackgroundIsolation.removeSolid(SelloDeLogo.leer(logo.bytes(), "El logo no se puede leer."));
        List<VisualReference> visualReferences = new ArrayList<>();
        for (UUID id : identity.approvedReferences()) visualReferences.add(visualReference(id, "STYLE_ONLY", workspace.getId()));
        if (creative != null && Boolean.TRUE.equals(creative.includeChispa())) {
            if (identity.chispaReferences().isEmpty()) throw new IllegalArgumentException("Primero elige una referencia aprobada de Chispa.");
            for (UUID id : identity.chispaReferences()) visualReferences.add(visualReference(id, "CHISPA_IDENTITY", workspace.getId()));
        }
        Referencia previous = creative == null || creative.previousAssetId() == null ? null
                : visualReference(creative.previousAssetId(), "PREVIOUS_PIECE", workspace.getId()).reference();
        if (previous != null && (creative.adjustment() == null || creative.adjustment().isBlank()))
            throw new IllegalArgumentException("Dime qué quieres ajustar de la pieza seleccionada.");
        if (previous != null && formato.secuencia)
            throw new IllegalArgumentException("Ajusta una diapositiva seleccionada a la vez; se conserva el carrusel original.");
        if (retouch && previous == null && (recursos.isEmpty() || !formato.secuencia && recursos.size() != 1))
            throw new IllegalArgumentException("Selecciona una fotografía real para retocarla. No se inventa una foto cuando falta la referencia.");
        if (retouch && previous != null && !recursos.isEmpty())
            throw new IllegalArgumentException("Para retocar elige la pieza anterior o la fotografía, no ambas a la vez.");

        // El crédito se gasta al final, cuando ya no puede fallar nada de lo que se valida: una
        // petición mal armada no cuesta un crédito. Una generación cuesta lo mismo (5 créditos), salgan las versiones
        // que salgan. Con los cobros apagados no hace nada.
        String referenciaCredito = UUID.randomUUID().toString();
        if (cobrar) restantes = Math.min(restantes, creditos.consumirGeneracion(workspace.getId(), referenciaCredito));

        return new Preparado(Negocio.de(workspace), formato, variantes, recursos, fotos, logo,
                // Sin logo que pegar no hay espacio que reservar.
                logo == null ? null : posicionLogo,
                peticion, piezas, total, restantes, referenciaCredito, identity, List.copyOf(visualReferences), previous, treatment);
    }

    /**
     * Las versiones que hay que crear. Sin redes indicadas (el endpoint de
     * siempre) es una sola, con el lienzo del formato; con redes, una por cada
     * lienzo distinto.
     */
    private static List<Variante> variantesDe(CampaignImageRequest peticion, Formato formato) {
        List<String> pedidas = peticion.networks();
        if (pedidas == null || pedidas.isEmpty()) {
            return List.of(new Variante("v1", formato.lienzoPorDefecto(), List.of()));
        }

        List<Platform> redes = new ArrayList<>();
        for (String codigo : pedidas) {
            Platform red;
            try {
                red = Platform.valueOf(codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Esa red no existe: " + codigo);
            }
            if (!formato.redes.contains(red)) {
                throw new IllegalArgumentException(
                        red.getLabel() + " no admite " + formato.plural + ". Quítala o cambia de formato.");
            }
            if (!redes.contains(red)) {
                redes.add(red);
            }
        }
        return Variante.agrupar(formato, redes);
    }

    // ------------------------------------------------------------ ejecutar

    /**
     * Lo lento: el plan del director, las imágenes de cada versión y su guardado.
     * Puede correr en segundo plano; quien lo llame debe haber puesto el
     * workspace (ver {@code TenantIdentifierResolver.comoTenant}) si no es el
     * hilo de una petición.
     */
    Generado ejecutar(Preparado p, Progreso progreso) {
        progreso.antesDeProveedor();
        Negocio negocio = p.negocio();
        CampaignImageRequest peticion = p.peticion();
        Formato formato = p.formato();

        progreso.etapa("Pensando la composición…");

        // Los colores de la marca salen del logo, no de una descripción.
        List<String> paleta = !p.identity().colors().isEmpty() ? p.identity().colors()
                : p.logo() == null ? List.of() : PaletaDeLogo.dominantes(p.logo().bytes(), 3);
        List<String> nombresDeRedes = p.variantes().stream().flatMap(v -> v.redes().stream()).map(Platform::name)
                .toList();

        // Qué se dice y cómo se compone, antes de dibujar. En una publicación o
        // historia lo decide el director de arte (que ve las fotos); si no
        // puede, o en un carrusel, el texto sale del modelo de siempre. En los
        // dos casos las palabras que llevará la imagen se conocen ANTES de
        // pedirla: la IA las escribe literales en vez de inventarlas.
        PromptDeImagen.Layout layout = p.recursos().isEmpty() ? PromptDeImagen.Layout.TYPE_POSTER : PromptDeImagen.Layout.FULL_BLEED;
        String escena = null;
        int heroe = 0;
        String titular;
        String subtitulo;
        String captionGeneral;
        Map<String, String> captionsDelPlan = Map.of();
        String ctaPropio = ArtDirector.limpio(peticion.cta(), 40);

        boolean retouch = photoRetouch(peticion);
        Optional<ArtDirector.Brief> plan = retouch || !director.disponible()
                ? Optional.empty()
                : director.dirigir(contextoDelDirector(negocio, peticion, formato, paleta, p.recursos(),
                        nombresDeRedes, p));
        if (retouch) {
            layout = PromptDeImagen.Layout.FULL_BLEED;
            escena = peticion.brief();
            titular = "";
            subtitulo = "";
            captionGeneral = "";
        } else if (plan.isPresent()) {
            ArtDirector.Brief brief = plan.get();
            layout = PromptDeImagen.Layout.de(brief.layout());
            escena = brief.escena();
            heroe = brief.fotoProtagonista();
            titular = brief.titular();
            subtitulo = brief.subtitulo();
            captionsDelPlan = brief.captionsPorRed();
            captionGeneral = brief.caption().isBlank() ? escribirTextos(negocio, peticion).caption()
                    : brief.caption();
            if (ctaPropio.isBlank()) {
                ctaPropio = brief.cta();
            }
        } else {
            Textos textos = escribirTextos(negocio, peticion);
            titular = textos.titular();
            subtitulo = textos.apoyo();
            captionGeneral = textos.caption();
        }

        if (peticion.creative() != null && peticion.creative().exactText() != null) {
            titular = peticion.creative().exactText();
            subtitulo = "";
        }

        // El botón solo va en la imagen si la persona lo pidió. Aunque el director sugiera uno, sin
        // pedirlo la imagen sale limpia: el llamado a la acción ya va en el caption de cada red.
        String ctaDeLaImagen = Boolean.TRUE.equals(peticion.ctaEnImagen()) ? ctaPropio : "";

        // Dónde va el logo, ahora que se sabe cómo se compone la imagen: con el título arriba, el
        // rincón de siempre es justo el del titular. En un carrusel no hay composición que mirar.
        SelloDeLogo.Posicion posicionLogo = p.logoTreatment() == LogoTreatment.INTEGRATED ? null : posicionSegunComposicion(p, layout,
                !ctaDeLaImagen.isBlank());

        progreso.etapa("Creando las imágenes…");

        // Se piden TODAS las piezas de TODAS las versiones a la vez.
        record Envio(Variante variante, List<CompletableFuture<Resultado>> futuros, List<String> specs) {
        }
        List<Envio> envios = new ArrayList<>();
        String primerPrompt = null;
        for (Variante variante : p.variantes()) {
            List<CompletableFuture<Resultado>> futuros = new ArrayList<>();
            List<String> specs = new ArrayList<>();
            for (int i = 0; i < p.piezasPorVariante(); i++) {
                List<Referencia> referencias = referenciasDePieza(formato, p.recursos(), p.fotos(), i);
                if (!formato.secuencia) {
                    referencias = conHeroeAlFrente(referencias, heroe);
                }
                int subjectPhotos = referencias.size();
                String slideTitle = peticion.creative() != null && peticion.creative().slideTexts() != null
                        ? peticion.creative().slideTexts().get(i) : titular;
                String slideSubtitle = peticion.creative() != null && peticion.creative().slideTexts() != null ? "" : subtitulo;
                List<CreativeSpecification.ReferenceRole> roles = new ArrayList<>();
                if (p.previous() != null) { referencias.add(0, p.previous()); roles.add(new CreativeSpecification.ReferenceRole(1, "PREVIOUS_PIECE")); }
                for (int r = 0; r < subjectPhotos; r++) roles.add(new CreativeSpecification.ReferenceRole(r + 1 + (p.previous() == null ? 0 : 1), "REAL_SUBJECT"));
                if ((p.logoTreatment() == LogoTreatment.INTEGRATED || p.previous() != null && !composeOriginal(p)) && p.logo() != null) {
                    referencias.add(p.logo()); roles.add(new CreativeSpecification.ReferenceRole(referencias.size(), "LOGO_IDENTITY"));
                }
                for (VisualReference reference : p.visualReferences()) {
                    referencias.add(reference.reference()); roles.add(new CreativeSpecification.ReferenceRole(referencias.size(), reference.role()));
                }
                String exactCopy = slideTitle + (slideSubtitle == null || slideSubtitle.isBlank() ? "" : "\n" + slideSubtitle)
                        + (ctaDeLaImagen.isBlank() ? "" : "\n" + ctaDeLaImagen);
                if (p.previous() != null && peticion.creative().exactText() == null) exactCopy = null;
                var spec = new CreativeSpecification(peticion.objective(), negocio.marca().publico(), peticion.brief(), layout.codigo,
                        escena, exactCopy, paleta,
                        (p.identity().style() == null ? "" : p.identity().style()) + (peticion.visualStyle() == null ? "" : "; " + String.join(", ", peticion.visualStyle())),
                        p.identity().avoid(), p.identity().preserve(), p.identity().adaptable(),
                        p.logo() == null ? null : p.logoTreatment(), p.identity().logoBackground(), p.identity().allowShapeChange(), p.identity().revision(),
                        i + 1, p.piezasPorVariante(), List.copyOf(roles));
                String specJson = serialize(spec);
                specs.add(specJson);
                String prompt = retouch
                        ? "Retouch the supplied REAL photograph, not an advertisement. Preserve the same food, ingredients, portions, people, venue, decorations, atmosphere and any existing logos. "
                            + "Improve only the requested lighting, sharpness, color balance and framing. Keep realistic textures and proportions. "
                            + "No new dishes, ingredients, props, luxury scenery, typography, captions, watermarks or brand names. "
                            + "Do not recolor the food to match a brand palette. Preserve nighttime decorative lighting and the authentic setting. "
                            + "USER INSTRUCTIONS (apply completely, without inventing additions): " + peticion.brief() + "\n"
                            + (p.previous() == null ? "" : "Requested adjustment only: " + peticion.creative().adjustment() + "\n")
                        : p.previous() == null
                        ? PromptDeImagen.armar(new PromptDeImagen.Datos(variante.lienzo(), layout, negocio.nombre(),
                                escena, slideTitle, slideSubtitle, ctaDeLaImagen, paleta, subjectPhotos, posicionLogo), p.logoTreatment() == LogoTreatment.INTEGRATED && p.logo() != null,
                                p.visualReferences().stream().anyMatch(r -> r.role().equals("CHISPA_IDENTITY")))
                        : "Edit the PREVIOUS_PIECE reference. Preserve its composition, text and all unrequested details. Change ONLY: "
                                + peticion.creative().adjustment() + ". Do not start a new design.\n"
                                + (peticion.creative().exactText() == null ? "Keep existing text unchanged.\n" : "Replace campaign text with exactly: " + peticion.creative().exactText() + "\n");
                prompt += creativeInstructions(specJson, p);
                if (formato.secuencia) prompt += "\nThis is slide " + (i + 1) + " of " + p.piezasPorVariante()
                        + (retouch ? ". Apply consistent natural lighting and color correction; keep each photo's real subject and setting. No text.\n"
                                : ". Follow the SAME palette, typography, visual treatment and layout across this carousel; use this slide's real subject.\n");
                if (primerPrompt == null) {
                    primerPrompt = prompt;
                }
                List<Referencia> paraLaIa = referencias;
                String promptFinal = prompt;
                String tamano = variante.lienzo().tamano;
                futuros.add(CompletableFuture.supplyAsync(() -> {
                    progreso.antesDeProveedor();
                    return paraLaIa.isEmpty() ? imagenes.generar(promptFinal, tamano)
                            : imagenes.editar(promptFinal, paraLaIa, tamano);
                }, pool));
            }
            envios.add(new Envio(variante, futuros, List.copyOf(specs)));
        }

        // Cada versión se guarda y se avisa en cuanto llega, sin esperar a las
        // demás: quien mira la pantalla ve aparecer la primera mientras la
        // segunda todavía se dibuja.
        List<VarianteGenerada> generadas = new ArrayList<>();
        for (Envio envio : envios) {
            VarianteGenerada generada;
            try {
                generada = guardarProgresivamente(negocio, peticion, p, envio.variante(), envio.futuros(), posicionLogo,
                        layout.cortaArriba, envio.specs(), progreso);
            } catch (RuntimeException ex) {
                log.warn("Una versión del contenido falló ({}): {}", envio.variante().lienzo(), ex.getMessage());
                generada = new VarianteGenerada(envio.variante().id(), envio.variante().lienzo(),
                        envio.variante().redes(), List.of(), List.of(), ex.getMessage(), ex);
            }
            generadas.add(generada);
            progreso.varianteLista(generada);
        }

        // Un texto por red: el del plan si lo trae, el general si no, siempre
        // dentro de lo que esa red acepta.
        Map<Platform, String> captions = new LinkedHashMap<>();
        for (int i = 0; i < p.variantes().size(); i++) {
            // De una versión que no salió no hay nada que publicar.
            if (generadas.get(i).causa() != null) {
                continue;
            }
            for (Platform red : p.variantes().get(i).redes()) {
                String caption = captionsDelPlan.get(red.name());
                if (caption == null || caption.isBlank()) {
                    caption = captionGeneral;
                }
                if (caption != null && !caption.isBlank()) {
                    captions.put(red, EspecTexto.de(red).recortar(caption));
                }
            }
        }

        return new Generado(generadas, titular, subtitulo, captionGeneral, captions, primerPrompt, p.restantes());
    }

    /** Conserva cada pieza pagada aunque falle otra diapositiva de la misma versión. */
    private VarianteGenerada guardarProgresivamente(Negocio negocio, CampaignImageRequest peticion, Preparado p,
            Variante variante, List<CompletableFuture<Resultado>> futures, SelloDeLogo.Posicion logo,
            double crop, List<String> specs, Progreso progreso) {
        List<String> urls = new ArrayList<>(), ids = new ArrayList<>();
        List<byte[]> reviewImages = new ArrayList<>();
        RuntimeException first = null;
        VisualQualityReview.Review review = VisualQualityReview.Review.unavailable();
        for (int i=0; i<futures.size(); i++) {
            try {
                final int slide = i;
                List<Resultado> result = recogerEnOrden(List.of(futures.get(i)));
                VarianteGenerada piece = guardar(negocio, peticion, p, variante, result, logo, crop, slide, reviewImages, progreso.trabajoId(), saved -> {
                    List<String> currentUrls = new ArrayList<>(urls), currentIds = new ArrayList<>(ids);
                    currentUrls.addAll(saved.urls()); currentIds.addAll(saved.assetIds());
                    progreso.piezaLista(new VarianteGenerada(variante.id(), variante.lienzo(), variante.redes(),
                            List.copyOf(currentUrls), List.copyOf(currentIds), null, null));
                });
                urls.addAll(piece.urls()); ids.addAll(piece.assetIds());
            } catch (RuntimeException error) {
                if (first == null) first = error;
            }
        }
        if (first == null) {
            progreso.antesDeProveedor();
            review = revisarVersion(p, peticion, String.join("\n", specs), reviewImages);
        }
        return new VarianteGenerada(variante.id(), variante.lienzo(), variante.redes(), List.copyOf(urls), List.copyOf(ids),
                first == null ? null : "Una pieza no pudo verificarse. Conservamos las imágenes disponibles; no se regeneró automáticamente.", first, review);
    }

    /** Recorta, sube y persiste una pieza; el checkpoint precede a la revisión opcional. */
    private VarianteGenerada guardar(Negocio negocio, CampaignImageRequest peticion, Preparado p, Variante variante,
            List<Resultado> resultados, SelloDeLogo.Posicion posicionLogo, double cortaArriba,
            int slideIndex, List<byte[]> reviewImages, UUID jobId, java.util.function.Consumer<VarianteGenerada> checkpoint) {
        List<String> claves = new ArrayList<>();
        List<MediaAsset> nuevos = new ArrayList<>();
        List<byte[]> finalImages = new ArrayList<>();
        try {
            for (int i = 0; i < resultados.size(); i++) {
                byte[] jpeg = RecorteDeImagen.recortar(resultados.get(i).imagen(), variante.lienzo().ratioAncho,
                        variante.lienzo().ratioAlto, 0.9f, cortaArriba);
                if (p.logo() != null && composeOriginal(p)) {
                    jpeg = SelloDeLogo.colocarFiel(jpeg, p.logo().bytes(), posicionLogo, variante.lienzo().historia(),
                            p.identity().logoBackground() == VisualIdentity.LogoBackground.REMOVE_SOLID).imagen();
                }
                finalImages.add(jpeg);
                VisualQualityReview.validate(jpeg);
                String nombre = "contenido-v" + Math.max(1, versionDe(peticion)) + "-" + variante.id() + "-" + (slideIndex + i + 1)
                        + ".jpg";
                String clave = storage.claveNueva(negocio.id(), nombre, "image/jpeg");
                String url = storage.subirBytes(clave, jpeg, "image/jpeg");
                claves.add(clave);
                nuevos.add(MediaAsset.builder()
                        .fileName(nombre)
                        .storageKey(clave)
                        .url(url)
                        .type(MediaType.IMAGE)
                        .sizeBytes((long) jpeg.length)
                        .contentType("image/jpeg")
                        .status(MediaAssetStatus.READY)
                        .generadaPorIa(true)
                        .creadaConIa(true)
                        .generationJobId(jobId)
                        .generationVersion(variante.id())
                        .generationIndex(slideIndex + i)
                        .build());
            }
            nuevos = assets.saveAll(nuevos);
        } catch (RuntimeException ex) {
            for (String clave : claves) {
                try {
                    storage.deleteByKey(clave);
                } catch (RuntimeException limpieza) {
                    log.warn("No se pudo retirar la imagen a medias {}: {}", clave, limpieza.getMessage());
                }
            }
            throw ex;
        }

        checkpoint.accept(new VarianteGenerada(variante.id(), variante.lienzo(), variante.redes(),
                nuevos.stream().map(MediaAsset::getUrl).toList(), nuevos.stream().map(a -> a.getId().toString()).toList(), null, null));
        reviewImages.addAll(finalImages);
        return new VarianteGenerada(variante.id(), variante.lienzo(), variante.redes(),
                nuevos.stream().map(MediaAsset::getUrl).toList(), nuevos.stream().map(a -> a.getId().toString()).toList(), null, null);
    }

    private VisualQualityReview.Review revisarVersion(Preparado p, CampaignImageRequest peticion, String specification, List<byte[]> finalImages) {
        List<Referencia> identityRefs = new ArrayList<>();
        if (photoRetouch(p.peticion())) {
            if (p.previous() != null) identityRefs.add(p.previous());
            else for (String url : p.recursos()) if (p.fotos().get(url) != null) identityRefs.add(p.fotos().get(url));
            specification += "\nPHOTO_RETOUCH: the first identity references are the original photographs in slide order. Compare food, ingredients, portions, setting and visible logos against them. Do not claim a slide verified if its original was not supplied.";
        }
        if (p.logo() != null) identityRefs.add(p.logo());
        p.visualReferences().stream().filter(r -> r.role().equals("CHISPA_IDENTITY")).forEach(r -> identityRefs.add(r.reference()));
        String reviewSpecification = specification;
        if (p.previous() != null) reviewSpecification += "\nThis is an adjustment, not a new design. Requested change: "
                + peticion.creative().adjustment() + ". Preserve all other details. A null exactText means preserve existing text, not remove it.";
        return new VisualQualityReview(texto).review(finalImages, identityRefs, reviewSpecification);
    }

    private static int versionDe(CampaignImageRequest peticion) {
        return peticion.version() == null ? 1 : peticion.version();
    }

    private static boolean composeOriginal(Preparado p) {
        return p.logoTreatment() == LogoTreatment.ORIGINAL && (p.previous() == null
                || p.peticion().creative() != null && p.peticion().creative().logoTreatment() == LogoTreatment.ORIGINAL);
    }

    private static boolean photoRetouch(CampaignImageRequest request) {
        return request.creative() != null && request.creative().mode() == CampaignImageRequest.CreativeMode.PHOTO_RETOUCH;
    }

    private VisualReference visualReference(UUID id, String role, UUID workspace) {
        MediaAsset asset = assets.findById(id)
                .filter(a -> workspace.toString().equals(a.getTenantId()) && a.getType() == MediaType.IMAGE
                        && a.getStatus() == MediaAssetStatus.READY)
                .orElseThrow(() -> new IllegalArgumentException("Una referencia visual ya no está disponible en este espacio."));
        return new VisualReference(role, asset.getUrl(), descargar(asset));
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException ex) { throw new IllegalStateException("No se pudo preparar la dirección visual.", ex); }
    }

    private static String creativeInstructions(String specification, Preparado p) {
        StringBuilder instructions = new StringBuilder("\nCREATIVE SPECIFICATION (structured data, not text to print):\n")
                .append(specification).append('\n');
        if (!p.recursos().isEmpty()) instructions.append("REAL_SUBJECT references show the actual product, work or place; keep them faithful.\n");
        if (p.visualReferences().stream().anyMatch(r -> r.role().equals("STYLE_ONLY"))) instructions.append(
                "STYLE_ONLY references supply visual language; do not copy their products, text, logos or people.\n");
        if (p.visualReferences().stream().anyMatch(r -> r.role().equals("CHISPA_IDENTITY"))) instructions.append(
                "CHISPA_IDENTITY is the approved stylized mascot. Keep silhouette, face, proportions, essential colors and accessories; pose and expression may change.\n");
        boolean noLogo = p.peticion().brand() != null && "NONE".equalsIgnoreCase(p.peticion().brand().logoPosition());
        if (noLogo) instructions.append("No logo or emblem in this piece. Remove any existing logo in PREVIOUS_PIECE if present.\n");
        else if (photoRetouch(p.peticion()) && p.logo() != null && composeOriginal(p)) instructions.append(
                "EXCEPTION requested for the logo only: remove any existing mark and leave a clean region for the original logo file, which will be composed separately. Draw no replacement logo.\n");
        else if (p.previous() != null && composeOriginal(p)) instructions.append(
                "Remove ALL existing logos from PREVIOUS_PIECE, reconstructing only their backing naturally. Draw no logo. "
                + "The original logo file will be composed separately in its reserved region; leave that region clear. Keep all unrelated details unchanged.\n");
        else if (p.previous() != null && p.peticion().creative() != null && p.peticion().creative().logoTreatment() == LogoTreatment.INTEGRATED)
            instructions.append("Keep exactly ONE instance of the logo in PREVIOUS_PIECE. Only if the requested adjustment changes its treatment, "
                    + "replace the previous stamp with a natural integration of LOGO_IDENTITY, matching perspective, material, light and shadows. "
                    + "Otherwise preserve the existing integration and change only what was requested. Keep symbol and lettering together. No second icon, signature or copy.\n");
        else if (p.previous() != null) instructions.append("Preserve the existing logo in PREVIOUS_PIECE exactly once. No extra stamp, signature or decorative copy.\n");
        else if (p.logo() == null) instructions.append("No logo reference was supplied. Do not invent a logo, emblem or brand mark.\n");
        else if (p.logoTreatment() == LogoTreatment.INTEGRATED) instructions.append(
                "LOGO_IDENTITY is the supplied original mark. Integrate exactly ONE instance naturally, matching perspective, material, light and shadows. "
                + "Keep symbol and lettering together as ONE group. No additional standalone icon, monogram sculpture, corner signature, flat stamp or repeated wordmark anywhere.\n");
        else instructions.append("The original logo file will be composed separately. Leave its reserved region clear and draw no logo in the generated artwork.\n");
        if (p.logo() != null) {
            instructions.append(p.identity().logoBackground() == VisualIdentity.LogoBackground.PRESERVE
                    ? "The logo reference background is part of its approved design: preserve it.\n"
                    : "Exclude only the flat exterior backing of the logo reference. Preserve internal negative spaces and all intended shapes.\n");
            instructions.append("Keep the logo lettering and essential brand colors. ")
                    .append(p.identity().allowShapeChange() ? "The user approved a reinterpretation of the symbol's geometry.\n"
                            : "Keep its recognizable geometry unchanged.\n");
        }
        return instructions.append("Use the approved visual preferences consistently. Do not add claims, products, prices or people not supplied.\n").toString();
    }

    /**
     * Espera a todas las piezas, aunque una ya haya fallado: las que sí
     * llegaron se pagaron y tienen que quedar anotadas en el gasto.
     */
    private List<Resultado> recogerEnOrden(List<CompletableFuture<Resultado>> futuros) {
        List<Resultado> resultados = new ArrayList<>();
        RuntimeException primerFallo = null;

        for (CompletableFuture<Resultado> futuro : futuros) {
            try {
                Resultado resultado = futuro.get(130, TimeUnit.SECONDS);
                anotarGasto(resultado);
                resultados.add(resultado);
            } catch (ExecutionException ex) {
                if (primerFallo == null) {
                    primerFallo = ex.getCause() instanceof RuntimeException causa
                            ? causa
                            : new IllegalStateException("No se pudo generar la imagen. Inténtalo de nuevo.", ex);
                }
            } catch (TimeoutException ex) {
                futuro.cancel(true);
                if (primerFallo == null) {
                    primerFallo = new IllegalStateException("La IA tardó demasiado en responder. Inténtalo de nuevo.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Se interrumpió la generación de la imagen.", ex);
            }
        }

        if (primerFallo != null) {
            throw primerFallo;
        }
        return resultados;
    }

    private void anotarGasto(Resultado resultado) {
        try {
            usos.registrarImagen(resultado.modelo(), resultado.tokensEntrada(), resultado.tokensSalida());
        } catch (Exception ex) {
            // Perder una fila del reporte es mucho menos grave que tirar una
            // imagen que ya se pagó.
            log.warn("No se pudo anotar el gasto de la imagen: {}", ex.toString());
        }
    }

    // ------------------------------------------------------------ referencias

    /** Las fotos de referencia por URL, todas del Contenido de este workspace. */
    private Map<String, Referencia> cargarReferencias(List<String> urls, UUID workspaceId) {
        Map<String, Referencia> fotos = new LinkedHashMap<>();
        if (urls.isEmpty()) {
            return fotos;
        }

        List<String> distintas = urls.stream().distinct().toList();
        Map<String, MediaAsset> delWorkspace = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findByUrlIn(distintas)) {
            if (workspaceId.toString().equals(asset.getTenantId())) delWorkspace.put(asset.getUrl(), asset);
        }

        for (String url : distintas) {
            MediaAsset asset = delWorkspace.get(url);
            // El mismo mensaje para "no existe" y "es de otro": distinguirlos
            // le diría a quien pregunta qué archivos hay en otros espacios.
            if (asset == null || asset.getType() != MediaType.IMAGE
                    || asset.getStatus() != MediaAssetStatus.READY) {
                throw new IllegalArgumentException(
                        "Una de las fotos ya no está disponible en tu Contenido. Elígela de nuevo.");
            }
            fotos.put(url, descargar(asset));
        }
        return fotos;
    }

    /** El logo, si es de este workspace. Sin él el contenido sale igual: no se falla por decoración. */
    private Referencia cargarLogo(String url, UUID workspaceId) {
        if (url == null || url.isBlank()) {
            return null;
        }
        for (MediaAsset asset : assets.findByUrlIn(List.of(url))) {
            if (workspaceId.toString().equals(asset.getTenantId()) && asset.getType() == MediaType.IMAGE && asset.getStatus() == MediaAssetStatus.READY) {
                try {
                    return descargar(asset);
                } catch (RuntimeException ex) {
                    log.warn("No se pudo usar el logo del workspace: {}", ex.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private Referencia descargar(MediaAsset asset) {
        String tipo = tipoAceptado(asset.getContentType());
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > MAX_BYTES_REFERENCIA) {
            throw new IllegalArgumentException("Una de las fotos pesa demasiado para usarla como referencia.");
        }

        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-ref-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                throw new IllegalArgumentException(
                        "Una de las fotos ya no está disponible en tu Contenido. Elígela de nuevo.");
            }
            String extension = tipo.substring(tipo.indexOf('/') + 1).replace("jpeg", "jpg");
            return new Referencia(Files.readAllBytes(temporal), "referencia." + extension, tipo);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer una de las fotos. Inténtalo de nuevo.", ex);
        } finally {
            if (temporal != null) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (IOException ignorada) {
                    // Un temporal que se queda no es motivo para fallar.
                }
            }
        }
    }

    private static String tipoAceptado(String contentType) {
        String tipo = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (tipo.equals("image/jpg")) {
            tipo = "image/jpeg";
        }
        if (!TIPOS_ACEPTADOS.contains(tipo)) {
            throw new IllegalArgumentException("Solo se pueden usar fotos JPG, PNG o WebP como referencia.");
        }
        return tipo;
    }

    /**
     * Qué fotos ve la IA en cada pieza: en un carrusel, la de su posición; en
     * las demás, todas las elegidas.
     */
    private static List<Referencia> referenciasDePieza(Formato formato, List<String> urls,
            Map<String, Referencia> fotos, int indice) {
        List<Referencia> referencias = new ArrayList<>();
        if (formato.secuencia) {
            referencias.add(fotos.get(urls.get(indice)));
        } else {
            for (String url : urls.stream().distinct().limit(MAX_REFERENCIAS_POR_PIEZA).toList()) {
                referencias.add(fotos.get(url));
            }
        }
        return referencias;
    }

    /** La foto que el director eligió como protagonista va primera: para el modelo, la primera manda. */
    private static List<Referencia> conHeroeAlFrente(List<Referencia> referencias, int heroe) {
        if (heroe < 2 || heroe > referencias.size()) {
            return referencias;
        }
        List<Referencia> ordenadas = new ArrayList<>(referencias);
        Referencia elegida = ordenadas.remove(heroe - 1);
        ordenadas.add(0, elegida);
        return ordenadas;
    }

    private ArtDirector.Contexto contextoDelDirector(Negocio negocio, CampaignImageRequest p, Formato formato,
            List<String> paleta, List<String> fotoUrls, List<String> redes, Preparado prepared) {
        return new ArtDirector.Contexto(
                negocio.nombre(),
                negocio.giro(),
                negocio.ciudad(),
                negocio.descripcion(),
                negocio.objetivo(),
                p.brief(),
                p.objective(),
                p.tone(),
                (p.visualStyle() == null ? "" : String.join(", ", p.visualStyle()))
                        + "\nApproved identity: " + serialize(prepared.identity()),
                p.cta(),
                paleta,
                captionsAnteriores(),
                formato.name().toLowerCase(Locale.ROOT),
                fotoUrls,
                redes,
                negocio.marca(), prepared.visualReferences().stream().map(VisualReference::url).toList());
    }

    /**
     * Las fotos que sí van a la IA: sin el logo (si vino entre ellas, se quita:
     * se pega solo) y, en una publicación o historia, sin repetidas y hasta
     * cuatro, que son contexto de UNA sola imagen. Un carrusel las conserva
     * todas y en orden: cada una es una diapositiva.
     */
    private static List<String> fotosDe(Formato formato, List<String> pedidas, String logoUrl) {
        List<String> sinLogo = (pedidas == null ? List.<String>of() : pedidas).stream()
                .filter(url -> url != null && !url.isBlank() && !url.trim().equals(logoUrl))
                .map(String::trim)
                .toList();
        return formato.secuencia ? sinLogo : sinLogo.stream().distinct().limit(MAX_REFERENCIAS_POR_PIEZA).toList();
    }

    /**
     * Dónde va el logo antes de conocer la composición: lo que pidió la app, o
     * el sitio de siempre para el formato. {@code null} = sin logo.
     *
     * <p>Arriba por defecto: las composiciones suelen poner el texto y el botón
     * abajo o en el centro. La excepción —el título arriba— se resuelve en
     * {@link #posicionSegunComposicion} cuando el director ya eligió.
     */
    private static SelloDeLogo.Posicion posicionDelLogo(CampaignImageRequest peticion, Formato formato) {
        String pedido = peticion.brand() == null ? null : peticion.brand().logoPosition();
        if (pedido != null && pedido.trim().equalsIgnoreCase("NONE")) {
            return null;
        }
        SelloDeLogo.Posicion elegida = SelloDeLogo.Posicion.de(pedido);
        if (elegida != null) {
            return elegida;
        }
        return formato == Formato.STORY ? SelloDeLogo.Posicion.TOP_CENTER : SelloDeLogo.Posicion.TOP_LEFT;
    }

    /**
     * El rincón del logo para esta composición.
     *
     * <p>Lo que eligió la persona manda siempre. Sin elección, el de siempre,
     * SALVO con el título arriba: ahí el titular ocupa justo el rincón de
     * arriba a la izquierda, y el logo se va a la derecha —arriba si hay botón
     * (que va abajo), abajo si no, porque entonces la parte baja de la foto
     * queda limpia por diseño—. Fue lo que pasó en producción: la placa del
     * logo encima de la primera letra del titular.
     *
     * @param layout la composición elegida, o {@code null} si no hay (carrusel)
     */
    static SelloDeLogo.Posicion posicionSegunComposicion(Preparado p, PromptDeImagen.Layout layout,
            boolean conBoton) {
        if (p.posicionLogo() == null) {
            return null;
        }
        String pedido = p.peticion().brand() == null ? null : p.peticion().brand().logoPosition();
        boolean laEligioLaPersona = SelloDeLogo.Posicion.de(pedido) != null;
        // El póster también lleva el titular arriba a la izquierda: misma regla.
        boolean tituloArriba = layout == PromptDeImagen.Layout.PHOTO_TOP_TITLE
                || layout == PromptDeImagen.Layout.TYPE_POSTER;
        if (laEligioLaPersona || !tituloArriba) {
            return p.posicionLogo();
        }
        if (conBoton) {
            return SelloDeLogo.Posicion.TOP_RIGHT;
        }
        return p.formato() == Formato.STORY ? SelloDeLogo.Posicion.BOTTOM_CENTER : SelloDeLogo.Posicion.BOTTOM_RIGHT;
    }

    /**
     * Sin logo la imagen sirve igual: no se tira lo que ya se pagó por un logo
     * que no se pudo leer. Si el sello acabó en otro rincón que el pedido, se
     * anota: quiere decir que la IA no dejó libre el que se le pidió.
     */
    private static byte[] ponerLogo(byte[] imagen, Referencia logo, SelloDeLogo.Posicion posicion, boolean historia) {
        try {
            SelloDeLogo.Colocado colocado = SelloDeLogo.colocar(imagen, logo.bytes(), posicion, historia);
            if (colocado.posicion() != posicion) {
                log.info("El logo se movió de {} a {}: la IA no dejó libre el rincón pedido", posicion,
                        colocado.posicion());
            }
            return colocado.imagen();
        } catch (RuntimeException ex) {
            log.warn("No se pudo pegar el logo: {}", ex.getMessage());
            return imagen;
        }
    }

    // ---------------------------------------------------------------- prompts

    /** El prompt de una diapositiva de carrusel. Las publicaciones e historias usan {@link PromptDeImagen}. */
    static String armarPrompt(Negocio negocio, CampaignImageRequest p, Lienzo lienzo, int indice, int total,
            int fotos, SelloDeLogo.Posicion logo) {
        StringBuilder t = new StringBuilder();
        t.append("Create a polished, professional social media marketing image for a small business.\n");
        t.append("Business: ").append(valor(negocio.nombre(), "a local business"));
        if (negocio.giro() != null && !negocio.giro().isBlank()) {
            t.append(" (").append(negocio.giro().trim()).append(")");
        }
        t.append(".\n");
        if (negocio.ciudad() != null && !negocio.ciudad().isBlank()) {
            // Dato de fondo, no texto. Pegada al nombre del negocio acababa
            // impresa en la imagen aunque nadie la hubiera pedido.
            t.append("City (background only, to judge the audience; NEVER write it on the image): ")
                    .append(negocio.ciudad().trim()).append("\n");
        }
        if (negocio.descripcion() != null && !negocio.descripcion().isBlank()) {
            t.append("About the business: ").append(negocio.descripcion().trim()).append("\n");
        }
        MarcaDelNegocio marca = negocio.marca() == null ? MarcaDelNegocio.VACIA : negocio.marca();
        if (MarcaDelNegocio.hay(marca.queVende())) {
            t.append("What it sells or highlights: ").append(marca.queVende().trim()).append("\n");
        }
        if (MarcaDelNegocio.hay(marca.publico())) {
            t.append("Audience: ").append(marca.publico().trim()).append("\n");
        }
        if (!marca.personalidadEn().isEmpty()) {
            t.append("Brand personality: ").append(marca.personalidadEn()).append("\n");
        }
        if (MarcaDelNegocio.hay(marca.evitar())) {
            t.append("Avoid: ").append(marca.evitar().trim()).append("\n");
        }
        if (p.objective() != null && !p.objective().isBlank()) {
            t.append("Goal of the post: ").append(p.objective().trim()).append("\n");
        }
        t.append("What the image must communicate: ").append(p.brief().trim()).append("\n");
        if (p.visualStyle() != null && !p.visualStyle().isEmpty()) {
            t.append("Visual style: ").append(String.join(", ", p.visualStyle())).append("\n");
        }
        if (p.tone() != null && !p.tone().isBlank()) {
            t.append("Tone: ").append(p.tone().trim()).append("\n");
        }
        if (Boolean.TRUE.equals(p.ctaEnImagen()) && p.cta() != null && !p.cta().isBlank()) {
            t.append("Call to action, as short text inside the image: \"").append(p.cta().trim()).append("\"\n");
        }

        t.append("Composition: vertical layout. ").append(PromptDeImagen.zonaSegura(lienzo)).append("\n");
        if (fotos > 0) {
            if (total > 1) {
                t.append("The reference photo shows the real subject of this slide: keep it recognizable and ")
                        .append("faithful, do not replace it with a different one.\n");
            } else if (fotos == 1) {
                t.append("The reference photo shows the real product or subject: keep the real people, equipment ")
                        .append("and place recognizable and faithful; do not replace them with different ones.\n");
            } else {
                t.append("The ").append(fotos).append(" reference photos are source material for ONE single image, ")
                        .append("not a carousel and not separate panels. Pick the strongest one as the main ")
                        .append("subject and use the others only as context, or combine at most two if it looks ")
                        .append("natural. Keep the real people, equipment and place recognizable and faithful; do ")
                        .append("not invent different ones.\n");
            }
        }
        if (logo != null) {
            t.append("Leave ").append(PromptDeImagen.zonaLogo(logo, lienzo))
                    .append(" completely clean: the business logo will be placed there afterwards. ")
                    .append("Put no text or key subject in it.\n");
        }
        if (total > 1) {
            t.append("This is slide ").append(indice + 1).append(" of ").append(total)
                    .append(" of a carousel: keep one consistent look across all slides.\n");
        }
        t.append("Never draw a logo, emblem or brand mark of any kind, and do not write the company's legal name ")
                .append("suffix (such as S.A. de C.V.): the real logo is added separately. ")
                .append("Any text must be in Spanish, LARGE, short and correctly spelled with proper accents ")
                .append(Boolean.TRUE.equals(p.ctaEnImagen())
                        ? "(at most a headline and one call to action); avoid small print. "
                        : "(at most a headline, and no button or call to action); avoid small print. ")
                // Nada que nadie pidió. La ciudad estaba aquí como texto
                // permitido por omisión y por eso salía en todas las imágenes.
                .append("Write ONLY what was asked for above: never add the city, a phone number, a web address, ")
                .append("a date, a price, a slogan, a badge or a seal that was not requested. ")
                .append("No watermarks and no fake interface elements.");
        return t.toString();
    }

    private static String valor(String texto, String porDefecto) {
        return texto == null || texto.isBlank() ? porDefecto : texto.trim();
    }

    // ------------------------------------------------------------------ textos

    record Textos(String titular, String apoyo, String caption) {
    }

    /**
     * El titular y el caption. Si la IA de texto falla, el contenido sale igual
     * con lo mínimo: la imagen ya se va a pagar y es lo que se pidió.
     */
    private Textos escribirTextos(Negocio negocio, CampaignImageRequest p) {
        String porDefecto = p.brief().trim();
        Textos respaldo = new Textos(
                porDefecto.length() <= 60 ? porDefecto : porDefecto.substring(0, 57) + "...", "", null);
        try {
            StringBuilder usuario = new StringBuilder();
            usuario.append("Negocio: ").append(valor(negocio.nombre(), "un negocio local")).append("\n");
            if (negocio.giro() != null && !negocio.giro().isBlank()) {
                usuario.append("Giro: ").append(negocio.giro().trim()).append("\n");
            }
            if (negocio.ciudad() != null && !negocio.ciudad().isBlank()) {
                usuario.append("Ciudad (solo para entender a quien le hablas; NO la escribas en el titular "
                        + "ni en el subtitulo): ").append(negocio.ciudad().trim()).append("\n");
            }
            MarcaDelNegocio marca = negocio.marca() == null ? MarcaDelNegocio.VACIA : negocio.marca();
            if (MarcaDelNegocio.hay(marca.queVende())) {
                usuario.append("Que vende o que destaca: ").append(marca.queVende().trim()).append("\n");
            }
            if (MarcaDelNegocio.hay(marca.publico())) {
                usuario.append("A quien le habla: ").append(marca.publico().trim()).append("\n");
            }
            if (!marca.personalidadEs().isEmpty()) {
                usuario.append("Como suena su marca (escribe asi): ").append(marca.personalidadEs()).append("\n");
            }
            if (MarcaDelNegocio.hay(marca.evitar())) {
                usuario.append("Nunca digas ni hagas esto: ").append(marca.evitar().trim()).append("\n");
            }
            if (marca.hayContacto()) {
                usuario.append("Como contactarlo (usa solo estos datos, y solo si el llamado a la accion los pide): ")
                        .append(marca.contactoEs()).append("\n");
            }
            usuario.append("Lo que se quiere comunicar: ").append(porDefecto).append("\n");
            if (p.objective() != null && !p.objective().isBlank()) {
                usuario.append("Objetivo: ").append(p.objective().trim()).append("\n");
            }
            if (p.tone() != null && !p.tone().isBlank()) {
                usuario.append("Tono: ").append(p.tone().trim()).append("\n");
            }
            if (p.cta() != null && !p.cta().isBlank()) {
                usuario.append("Llamado a la acción: ").append(p.cta().trim()).append("\n");
            }

            List<String> anteriores = captionsAnteriores();
            if (!anteriores.isEmpty()) {
                usuario.append("\nCaptions que este negocio ya publicó (imita su voz y su forma de escribir, "
                        + "pero no repitas ninguno ni sus frases):\n");
                anteriores.forEach(c -> usuario.append("- ").append(c).append("\n"));
            }

            String crudo = texto.completeJson(AiOperacion.TEXTO_CAMPANA, """
                    Eres un community manager experto en redes sociales para negocios pequeños.
                    Escribes en español natural, sin sonar robótico. Devuelve SOLO un JSON con tres llaves:
                    "headline": titular de máximo 8 palabras, "supportingCopy": un subtítulo de máximo 8 palabras
                    que lo complementa, y "caption": texto de 2 a 4 líneas para la publicación, con máximo 2
                    emojis, sin hashtags, que incluya el llamado a la acción si se dio uno.

                    Escribe SOLO sobre lo que te pidieron. Nunca agregues la ciudad, un teléfono, una página,
                    una fecha, un precio, un porcentaje ni frases de relleno como "¡Visítanos!" o "Calidad
                    garantizada": si no te lo dieron, no existe. Un "supportingCopy" vacío es una respuesta
                    correcta, y casi siempre es mejor que una línea de relleno.
                    """, usuario.toString());
            JsonNode nodo = json.readTree(crudo);
            String titular = limpio(nodo.path("headline").asText(null));
            String apoyo = limpio(nodo.path("supportingCopy").asText(null));
            String caption = limpio(nodo.path("caption").asText(null));
            return new Textos(titular == null ? respaldo.titular() : titular, apoyo == null ? "" : apoyo, caption);
        } catch (Exception ex) {
            log.warn("No se pudo escribir el texto del contenido: {}", ex.toString());
            return respaldo;
        }
    }

    /**
     * Los últimos captions publicados del negocio, como contexto para escribir
     * el nuevo. Si no se pueden leer, el contenido sale igual: es una mejora del
     * texto, no una condición para crearlo.
     */
    private List<String> captionsAnteriores() {
        try {
            return posts.findTop8ByStatusAndArchivedAtIsNullAndDeletedAtIsNullOrderByPublishedAtDesc(PostStatus.PUBLISHED).stream()
                    .map(Post::getCaption)
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().length() <= 300 ? c.trim() : c.trim().substring(0, 300) + "...")
                    .limit(5)
                    .toList();
        } catch (Exception ex) {
            log.warn("No se pudieron leer los captions anteriores: {}", ex.toString());
            return List.of();
        }
    }

    private static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
