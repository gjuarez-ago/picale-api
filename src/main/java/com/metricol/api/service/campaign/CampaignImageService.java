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
import com.metricol.api.service.ai.ArtDirector;
import com.metricol.api.service.ai.EspecTexto;
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

    public CampaignImageService(OpenAiImageClient imagenes, OpenAiClient texto, R2StorageService storage,
            MediaAssetRepository assets, PostRepository posts, StorageQuotaService cuota, AiQuotaGuard cupo,
            AiUsageRecorder usos, ArtDirector director) {
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
    record Negocio(UUID id, String nombre, String giro, String ciudad, String descripcion, String objetivo) {
        static Negocio de(Workspace w) {
            return new Negocio(w.getId(), w.getName(), w.getGiro(), w.getCiudad(), w.getDescripcion(),
                    w.getObjetivo() == null ? null : w.getObjetivo().name());
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
            int restantes) {
    }

    /** El resultado de una versión. {@code causa} solo sirve dentro del proceso: es lo que se relanza. */
    record VarianteGenerada(
            String id,
            Lienzo lienzo,
            List<Platform> redes,
            List<String> urls,
            List<String> assetIds,
            String error,
            RuntimeException causa) {
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
        Generado g = ejecutar(p, Progreso.NINGUNO);
        VarianteGenerada v = g.variantes().get(0);
        if (v.causa() != null) {
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

    // ------------------------------------------------------------ preparar

    /**
     * Valida y reúne todo, sin gastar nada. Corre en la petición: aquí es donde
     * se le dice a la persona lo que está mal, y falla antes de crear ningún
     * trabajo.
     */
    Preparado preparar(User usuario, CampaignImageRequest peticion) {
        Formato formato = Formato.de(peticion.format() == null ? null : peticion.format().code());
        String logoUrl = peticion.brand() == null || peticion.brand().logoUrl() == null
                || peticion.brand().logoUrl().isBlank() ? null : peticion.brand().logoUrl().trim();
        List<String> recursos = fotosDe(formato, peticion.resourceUrls(), logoUrl);

        int piezas = 1;
        if (formato.secuencia) {
            if (recursos.size() < 2 || recursos.size() > 5) {
                throw new IllegalArgumentException(
                        "Un carrusel necesita entre 2 y 5 fotos. Tu logo no cuenta: se agrega solo.");
            }
            piezas = recursos.size();
        }

        Workspace workspace = usuario.getWorkspace();
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

        Map<String, Referencia> fotos = cargarReferencias(recursos);
        SelloDeLogo.Posicion posicionLogo = posicionDelLogo(peticion, formato);
        Referencia logo = posicionLogo == null ? null : cargarLogo(logoUrl);

        return new Preparado(Negocio.de(workspace), formato, variantes, recursos, fotos, logo,
                // Sin logo que pegar no hay espacio que reservar.
                logo == null ? null : posicionLogo,
                peticion, piezas, total, restantes);
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
        Negocio negocio = p.negocio();
        CampaignImageRequest peticion = p.peticion();
        Formato formato = p.formato();

        progreso.etapa("Pensando la composición…");

        // Los colores de la marca salen del logo, no de una descripción.
        List<String> paleta = p.logo() == null ? List.of() : PaletaDeLogo.dominantes(p.logo().bytes(), 3);
        List<String> nombresDeRedes = p.variantes().stream().flatMap(v -> v.redes().stream()).map(Platform::name)
                .toList();

        // Qué se dice y cómo se compone, antes de dibujar. En una publicación o
        // historia lo decide el director de arte (que ve las fotos); si no
        // puede, o en un carrusel, el texto sale del modelo de siempre. En los
        // dos casos las palabras que llevará la imagen se conocen ANTES de
        // pedirla: la IA las escribe literales en vez de inventarlas.
        PromptDeImagen.Layout layout = PromptDeImagen.Layout.PHOTO_BOTTOM_BAND;
        String escena = null;
        int heroe = 0;
        String titular;
        String subtitulo;
        String captionGeneral;
        Map<String, String> captionsDelPlan = Map.of();
        String ctaPropio = ArtDirector.limpio(peticion.cta(), 40);

        Optional<ArtDirector.Brief> plan = formato.secuencia || !director.disponible()
                ? Optional.empty()
                : director.dirigir(contextoDelDirector(negocio, peticion, formato, paleta, p.recursos(),
                        nombresDeRedes));
        if (plan.isPresent()) {
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

        progreso.etapa("Creando las imágenes…");

        // Se piden TODAS las piezas de TODAS las versiones a la vez.
        record Envio(Variante variante, List<CompletableFuture<Resultado>> futuros) {
        }
        List<Envio> envios = new ArrayList<>();
        String primerPrompt = null;
        for (Variante variante : p.variantes()) {
            List<CompletableFuture<Resultado>> futuros = new ArrayList<>();
            for (int i = 0; i < p.piezasPorVariante(); i++) {
                List<Referencia> referencias = referenciasDePieza(formato, p.recursos(), p.fotos(), i);
                if (!formato.secuencia) {
                    referencias = conHeroeAlFrente(referencias, heroe);
                }
                String prompt = formato.secuencia
                        ? armarPrompt(negocio, peticion, variante.lienzo(), i, p.piezasPorVariante(),
                                referencias.size(), p.posicionLogo())
                        : PromptDeImagen.armar(new PromptDeImagen.Datos(variante.lienzo(), layout, negocio.nombre(),
                                escena, titular, subtitulo, ctaPropio, paleta, referencias.size(), p.posicionLogo()));
                if (primerPrompt == null) {
                    primerPrompt = prompt;
                }
                List<Referencia> paraLaIa = referencias;
                String tamano = variante.lienzo().tamano;
                futuros.add(CompletableFuture.supplyAsync(() -> paraLaIa.isEmpty()
                        ? imagenes.generar(prompt, tamano)
                        : imagenes.editar(prompt, paraLaIa, tamano), pool));
            }
            envios.add(new Envio(variante, futuros));
        }

        // Cada versión se guarda y se avisa en cuanto llega, sin esperar a las
        // demás: quien mira la pantalla ve aparecer la primera mientras la
        // segunda todavía se dibuja.
        List<VarianteGenerada> generadas = new ArrayList<>();
        for (Envio envio : envios) {
            VarianteGenerada generada;
            try {
                List<Resultado> resultados = recogerEnOrden(envio.futuros());
                generada = guardar(negocio, peticion, p, envio.variante(), resultados,
                        formato.secuencia ? 0.5 : layout.cortaArriba);
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

    /**
     * Recorta, pega el logo y sube las piezas de una versión. Si algo falla no
     * se conserva nada a medias.
     */
    private VarianteGenerada guardar(Negocio negocio, CampaignImageRequest peticion, Preparado p, Variante variante,
            List<Resultado> resultados, double cortaArriba) {
        List<String> claves = new ArrayList<>();
        List<MediaAsset> nuevos = new ArrayList<>();
        try {
            for (int i = 0; i < resultados.size(); i++) {
                byte[] jpeg = RecorteDeImagen.recortar(resultados.get(i).imagen(), variante.lienzo().ratioAncho,
                        variante.lienzo().ratioAlto, 0.9f, cortaArriba);
                if (p.logo() != null) {
                    jpeg = ponerLogo(jpeg, p.logo(), p.posicionLogo(), variante.lienzo().historia());
                }
                String nombre = "contenido-v" + Math.max(1, versionDe(peticion)) + "-" + variante.id() + "-" + (i + 1)
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

        return new VarianteGenerada(variante.id(), variante.lienzo(), variante.redes(),
                nuevos.stream().map(MediaAsset::getUrl).toList(),
                nuevos.stream().map(a -> a.getId().toString()).toList(), null, null);
    }

    private static int versionDe(CampaignImageRequest peticion) {
        return peticion.version() == null ? 1 : peticion.version();
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
    private Map<String, Referencia> cargarReferencias(List<String> urls) {
        Map<String, Referencia> fotos = new LinkedHashMap<>();
        if (urls.isEmpty()) {
            return fotos;
        }

        List<String> distintas = urls.stream().distinct().toList();
        Map<String, MediaAsset> delWorkspace = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findByUrlIn(distintas)) {
            delWorkspace.put(asset.getUrl(), asset);
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
    private Referencia cargarLogo(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        for (MediaAsset asset : assets.findByUrlIn(List.of(url))) {
            if (asset.getType() == MediaType.IMAGE && asset.getStatus() == MediaAssetStatus.READY) {
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
            List<String> paleta, List<String> fotoUrls, List<String> redes) {
        return new ArtDirector.Contexto(
                negocio.nombre(),
                negocio.giro(),
                negocio.ciudad(),
                negocio.descripcion(),
                negocio.objetivo(),
                p.brief(),
                p.objective(),
                p.tone(),
                p.visualStyle() == null ? null : String.join(", ", p.visualStyle()),
                p.cta(),
                paleta,
                captionsAnteriores(),
                formato.name().toLowerCase(Locale.ROOT),
                fotoUrls,
                redes);
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
     * Dónde va el logo: lo que pidió la app, o el sitio de siempre para el
     * formato. {@code null} = sin logo.
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
        // Arriba en todas: las composiciones ponen el texto y el botón abajo o en
        // el centro, y el logo necesita un rincón que ninguna les quite.
        return formato == Formato.STORY ? SelloDeLogo.Posicion.TOP_CENTER : SelloDeLogo.Posicion.TOP_LEFT;
    }

    /** Sin logo la imagen sirve igual: no se tira lo que ya se pagó por un logo que no se pudo leer. */
    private static byte[] ponerLogo(byte[] imagen, Referencia logo, SelloDeLogo.Posicion posicion, boolean historia) {
        try {
            return SelloDeLogo.poner(imagen, logo.bytes(), posicion, historia);
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
        if (negocio.ciudad() != null && !negocio.ciudad().isBlank()) {
            t.append(", ").append(negocio.ciudad().trim());
        }
        t.append(".\n");
        if (negocio.descripcion() != null && !negocio.descripcion().isBlank()) {
            t.append("About the business: ").append(negocio.descripcion().trim()).append("\n");
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
        if (p.cta() != null && !p.cta().isBlank()) {
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
                .append("(at most a headline, the city and one call to action); avoid small print. ")
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
                usuario.append("Ciudad: ").append(negocio.ciudad().trim()).append("\n");
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
            return posts.findTop8ByStatusAndArchivedAtIsNullOrderByPublishedAtDesc(PostStatus.PUBLISHED).stream()
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
