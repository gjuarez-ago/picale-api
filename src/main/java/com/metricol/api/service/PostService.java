package com.metricol.api.service;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.VideoLimitsProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.User;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.PostSaveRequest;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.models.response.PostStatusResponse;
import com.metricol.api.models.response.PostTargetResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.media.EspecImagen;
import com.metricol.api.service.publishing.CuotaComprometida;
import com.metricol.api.service.publishing.FormatRulesService;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.publishing.PublishQueueService;

@Service
public class PostService {

    private final PostRepository postRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final PublishQueueService cola;
    private final MediaLimitsProperties limites;
    private final VideoLimitsProperties videoLimits;
    private final FormatRulesService formatRules;
    private final R2StorageService storage;
    private final CuotaComprometida cupo;

    public PostService(
            PostRepository postRepository,
            SocialAccountRepository socialAccountRepository,
            MediaAssetRepository mediaAssetRepository,
            PublishQueueService cola,
            MediaLimitsProperties limites,
            VideoLimitsProperties videoLimits,
            FormatRulesService formatRules,
            R2StorageService storage,
            CuotaComprometida cupo) {
        this.postRepository = postRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.cola = cola;
        this.limites = limites;
        this.videoLimits = videoLimits;
        this.formatRules = formatRules;
        this.storage = storage;
        this.cupo = cupo;
    }

    public List<PostResponse> list() {
        return postRepository.findAllByOrderByScheduledAtAscCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * El estado de cada publicación, y nada más.
     *
     * <p>Lo que la app sondea mientras algo está en la cola. Cuando detecta
     * un cambio pide la lista completa una sola vez, en vez de traerla
     * entera cada pocos segundos.
     */
    public List<PostStatusResponse> statuses() {
        return postRepository.findEstados();
    }

    public PostResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    /**
     * Guarda y, si toca, la mete en la cola.
     *
     * <p>Publicar ya NO pasa aquí. Antes esta llamada se quedaba esperando a
     * upload-post —descargar el medio, subirlo, esperar a cada red— y con eso
     * el teléfono veía un timeout mientras la publicación seguía saliendo por
     * detrás, sin forma de saber si había salido. Ahora se responde en cuanto
     * queda encolada, con estado QUEUED, y la app lo enseña como tal.
     */
    @Transactional
    public PostResponse create(PostSaveRequest request, User currentUser) {
        Post post = Post.builder().build();
        applyRequest(post, request);
        // Con la publicacion ya armada y antes de guardarla: si no cabe en
        // su dia —o el workspace ya tiene demasiadas esperando— se dice aqui,
        // cuando todavia se puede cambiar el dia o quitar una red.
        cupo.exigirCupo(post, currentUser.getWorkspace().getId(), null);
        Post guardado = postRepository.saveAndFlush(post);
        encolarSiToca(guardado, request, currentUser);
        return toResponse(guardado);
    }

    @Transactional
    public PostResponse update(UUID id, PostSaveRequest request, User currentUser) {
        Post post = findOrThrow(id);

        // Lo que estuviera en la cola de la versión anterior se cancela: si no,
        // el worker publicaría el texto viejo con las redes viejas justo
        // después de que alguien lo corrigiera.
        cola.cancelarDePost(post.getId());

        // Lo que YA salio se conserva tal cual; solo se rehacen los destinos
        // que no. Antes se borraban todos y se creaban de nuevo, y corregir
        // una publicacion que habia salido en tres redes y fallado en una la
        // volvia a publicar en las cuatro. Publicar dos veces no se deshace.
        // Las redes que ya recibieron la version anterior se quedan con ella:
        // la correccion es para las que no la recibieron.
        post.getTargets().removeIf(target -> target.getStatus() != PostTargetStatus.PUBLISHED);
        applyRequest(post, request);
        // La version anterior de esta misma publicacion no cuenta contra ella.
        cupo.exigirCupo(post, currentUser.getWorkspace().getId(), post.getId());
        Post guardado = postRepository.saveAndFlush(post);
        encolarSiToca(guardado, request, currentUser);
        return toResponse(guardado);
    }

    /**
     * Vuelve a encolar una publicacion que fallo, sin tocar lo que ya salio.
     *
     * <p>Hasta ahora la unica forma de reintentar era volver a guardarla con
     * {@link #update}, y eso es un rodeo con efectos: borra sus destinos y los
     * crea de nuevo, asi que se pierde el rastro de que paso en cada red. Un
     * rechazo definitivo deja el trabajo en FAILED y la cola no lo vuelve a
     * mirar, de modo que sin esto la publicacion se quedaba ahi parada.
     *
     * <p>No hace falta preparar nada: la cola salta los destinos donde ya se
     * publico —ver {@code PostPublishStore.preparar}— y limpia el error de los
     * demas al reclamarlos. Reintentar no puede duplicar lo que ya salio.
     */
    @Transactional
    public PostResponse retry(UUID id, User currentUser) {
        Post post = findOrThrow(id);

        if (post.getStatus() == PostStatus.DRAFT) {
            throw new IllegalStateException(
                    "Es un borrador: todavia no se ha intentado publicar.");
        }
        if (post.getStatus() == PostStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Esta programada y aun no le toca salir, asi que no hay nada que reintentar.");
        }
        if (post.getStatus() == PostStatus.PUBLISHED) {
            throw new IllegalStateException("Esta publicacion ya salio.");
        }
        if (post.getTargets().stream()
                .allMatch(target -> target.getStatus() == PostTargetStatus.PUBLISHED)) {
            throw new IllegalStateException("Ya salio en todas sus redes.");
        }

        // Si ya tenia un trabajo vivo, encolar no crea otro y esto no es un
        // error: la publicacion va a salir igual. Tocar "reintentar" dos veces
        // —o tocarlo mientras la cola ya lo esta sacando— no puede acabar en
        // dos publicaciones, que es lo unico que no se deshace.
        cola.encolarAhora(post.getId(), currentUser.getWorkspace().getId());
        post.setStatus(PostStatus.QUEUED);
        return toResponse(postRepository.saveAndFlush(post));
    }

    @Transactional
    public void delete(UUID id) {
        Post post = findOrThrow(id);
        // Antes de borrar la fila: un trabajo que sobreviva al post intentaria
        // publicar algo que ya no existe, y acabaria como un fallo confuso.
        cola.cancelarDePost(post.getId());
        postRepository.delete(post);
    }

    /**
     * Archiva o devuelve a la vista una publicacion.
     *
     * <p>NO cancela lo que este en la cola ni desprograma nada: archivar es
     * una decision sobre que se enseña, no sobre que se publica. Archivar
     * algo que iba a salir el jueves y que no saliera seria una sorpresa
     * cara, y ademas se hace con un gesto de un dedo que es facil de dar
     * sin querer.
     *
     * <p>Idempotente a proposito: archivar dos veces no mueve la fecha, y
     * desarchivar algo que no estaba archivado no falla. El gesto viene con
     * un "deshacer", y ahi los dobles toques son normales.
     */
    @Transactional
    public PostResponse archive(UUID id, boolean archivar) {
        Post post = findOrThrow(id);

        if (archivar && post.getArchivedAt() == null) {
            post.setArchivedAt(LocalDateTime.now());
        } else if (!archivar) {
            post.setArchivedAt(null);
        }

        return toResponse(postRepository.saveAndFlush(post));
    }

    private void encolarSiToca(Post post, PostSaveRequest request, User currentUser) {
        if (post.getStatus() == PostStatus.DRAFT) {
            return;
        }

        UUID workspaceId = currentUser.getWorkspace().getId();

        if (post.getStatus() == PostStatus.QUEUED) {
            cola.encolarAhora(post.getId(), workspaceId);
        } else if (post.getStatus() == PostStatus.SCHEDULED && request.getScheduledAt() != null) {
            // Se encola desde ya con su hora de salida: la cola respeta runAt,
            // así que no hace falta que nadie vuelva a mirar el reloj por ella.
            cola.encolarPara(post.getId(), workspaceId, request.getScheduledAt());
        }
    }

    private void applyRequest(Post post, PostSaveRequest request) {
        List<SocialAccount> accounts = request.getSocialAccountIds() == null
                ? List.of()
                : socialAccountRepository.findAllById(request.getSocialAccountIds());

        // El formato primero: manda sobre todo lo demas. Cuantos archivos
        // caben, si pueden ser fotos y en que redes sale no dependen del
        // archivo sino de lo que se haya elegido publicar.
        PostFormat formato = formatoPedido(request);

        // Las redes se cargan antes de validar y no despues: lo que cabe en
        // una publicacion depende de a donde va, y sin saberlo solo se pueden
        // comprobar los topes generales de la app.
        Medios medios = validarMedios(request, accounts, formato);

        post.setCaption(request.getCaption());
        // El titulo se recorta a su tope aqui y no solo al publicar: lo que
        // se guarda es lo que va a salir, y asi la pantalla lo enseña igual.
        // Vacio desde un cliente viejo = se conserva el que hubiera.
        String titulo = com.metricol.api.service.ai.EspecTexto.recortarTitulo(request.getTitulo());
        if (titulo != null) {
            post.setTitulo(titulo);
        }
        // La idea dictada, aparte del texto que sale. Puede venir vacia desde
        // clientes que aun no la mandan; ahi se conserva la que hubiera.
        if (request.getBrief() != null && !request.getBrief().isBlank()) {
            post.setBrief(request.getBrief().strip());
        }
        post.getMediaUrls().clear();
        post.getMediaUrls().addAll(medios.urls());
        post.setMediaType(medios.tipo());
        post.setFormat(formato);
        // La portada se copia aquí para que no dependa de que el archivo siga
        // existiendo. Puede venir nula —el fotograma se saca unos segundos
        // después de subir— y entonces se resuelve al leer, como antes; lo que
        // importa es que en cuanto exista, quede pegada a la publicación.
        String portada = miniaturaDelArchivo(post);
        if (portada != null) {
            post.setThumbnailUrl(portada);
        }
        post.setVideoDurationSeconds(request.getVideoDurationSeconds());
        post.setScheduledAt(request.getScheduledAt());

        boolean encolada = request.isPublishNow() || request.getScheduledAt() != null;

        // Solo si va a salir: un borrador puede apuntar a una red sin página
        // mientras la persona la elige; lo que no puede es publicarse así.
        if (encolada) {
            exigirPaginas(accounts);
        }

        java.util.Map<String, String> porRed = request.getCaptionsPorRed() == null
                ? java.util.Map.of()
                : request.getCaptionsPorRed();

        for (SocialAccount account : accounts) {
            // Una red que ya tiene destino —porque ya salio en ella y la
            // correccion lo conservo— no se vuelve a anadir: seria un segundo
            // envio a la misma red.
            boolean yaTiene = post.getTargets().stream()
                    .anyMatch(t -> t.getSocialAccount() != null
                            && account.getId().equals(t.getSocialAccount().getId()));
            if (yaTiene) {
                continue;
            }

            // Nulo = usa el de la publicacion. No se rellena con el caption
            // general aqui a proposito: guardarlo copiado en las cinco filas
            // haria que editar el texto de la publicacion dejara de tener
            // efecto, sin que se vea por que.
            String suyo = porRed.get(account.getPlatform().name());

            post.getTargets().add(PostTarget.builder()
                    .post(post)
                    .socialAccount(account)
                    .caption(suyo == null || suyo.isBlank() ? null : suyo.strip())
                    .status(encolada ? PostTargetStatus.QUEUED : PostTargetStatus.PENDING)
                    .build());
        }

        if (request.isPublishNow()) {
            post.setStatus(PostStatus.QUEUED);
        } else if (request.getScheduledAt() != null) {
            post.setStatus(PostStatus.SCHEDULED);
        } else {
            post.setStatus(PostStatus.DRAFT);
        }
    }

    /**
     * Los medios que se van a publicar, ya validados, y de qué tipo son.
     *
     * <p>Acepta {@code mediaUrls} y también el {@code mediaUrl} suelto de
     * antes, para que una app sin actualizar siga publicando. Los dos topes se
     * comprueban aquí y no solo en la app: el cliente es quien pide, no quien
     * decide, y una app vieja o un script no tienen por qué respetarlos.
     */
    /**
     * El formato que pide quien publica, o {@code null} si no pide ninguno.
     *
     * <p>Nulo no es un error: una app o un panel anteriores a los formatos
     * siguen publicando, y su publicación se deduce del archivo igual que
     * antes (ver {@code Post.formatoEfectivo()}). Lo que sí es un error es
     * pedir un nombre que no existe, o uno que todavía no se ofrece: callarse
     * ahí publicaría algo distinto de lo que se pidió.
     */
    private PostFormat formatoPedido(PostSaveRequest request) {
        String pedido = request.getFormat();
        if (pedido == null || pedido.isBlank()) {
            return null;
        }

        PostFormat formato;
        try {
            formato = PostFormat.valueOf(pedido.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("No conocemos el formato \"" + pedido.strip()
                    + "\". Los que hay son: " + nombresOfrecidos() + ".");
        }

        if (!formato.ofrecido()) {
            throw new IllegalArgumentException(formato.getLabel()
                    + " todavia no esta disponible. Los que hay son: " + nombresOfrecidos() + ".");
        }
        return formato;
    }

    private String nombresOfrecidos() {
        return formatRules.ofrecidas().stream()
                .map(regla -> regla.formato().name())
                .collect(Collectors.joining(", "));
    }

    /**
     * Lo que el formato elegido admite: redes, cuántos archivos, de qué clase
     * y cuánto pueden durar.
     *
     * <p><b>La proporción 9:16 no se comprueba aquí</b>, y no por olvido: en
     * la petición solo viajan URLs, y medir un video exigiría descargarlo
     * antes de aceptar nada. La regla viaja igual en {@code exigeVertical} y
     * la aplica quien sí tiene el archivo delante y sus medidas — la pantalla
     * de captura, antes de dejar subirlo.
     */
    private void comprobarFormato(PostFormat formato, List<SocialAccount> accounts,
            List<String> medios, boolean hayVideo, PostSaveRequest request) {

        FormatRulesService.Regla regla = formatRules.de(formato);

        // Se nombran las redes que sobran en vez de decir "hay un problema":
        // lo que hay que hacer —quitar esa red, o cambiar de formato— solo se
        // puede hacer si se sabe cual es.
        List<String> fuera = accounts.stream()
                .map(SocialAccount::getPlatform)
                .distinct()
                .filter(red -> !regla.redes().contains(red))
                .map(Platform::getLabel)
                .toList();

        if (!fuera.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", fuera)
                    + (fuera.size() == 1 ? " no publica " : " no publican ")
                    + regla.label().toLowerCase(java.util.Locale.ROOT)
                    + ". Quita esa red o cambia de formato.");
        }

        if (medios.size() > regla.maxArchivos()) {
            throw new IllegalArgumentException(regla.label() + ": "
                    + (regla.maxArchivos() == 1
                            ? "va un solo archivo, y llevas " + medios.size() + "."
                            : "caben hasta " + regla.maxArchivos() + " archivos, y llevas "
                                    + medios.size() + "."));
        }

        if (hayVideo && !regla.admiteVideo()) {
            throw new IllegalArgumentException(regla.label() + " no lleva video.");
        }
        if (!hayVideo && !regla.admiteFoto() && !medios.isEmpty()) {
            throw new IllegalArgumentException(regla.label() + " lleva video, no fotos.");
        }

        // La duracion solo si la mandan. Sin ella no se inventa nada: la app
        // la mide, pero un script no tiene por que, y la red sigue siendo
        // quien decide al final.
        Integer segundos = request.getVideoDurationSeconds();
        if (hayVideo && segundos != null && regla.maxSegundos() != null
                && (segundos < regla.minSegundos() || segundos > regla.maxSegundos())) {
            throw new IllegalArgumentException(regla.label() + ": el video tiene que durar entre "
                    + regla.minSegundos() + " y " + regla.maxSegundos()
                    + " segundos, y el tuyo dura " + segundos + ".");
        }
    }

    private Medios validarMedios(PostSaveRequest request, List<SocialAccount> accounts,
            PostFormat formato) {
        List<String> medios = new ArrayList<>();
        if (request.getMediaUrls() != null) {
            request.getMediaUrls().stream()
                    .filter(url -> url != null && !url.isBlank())
                    .forEach(medios::add);
        }
        if (medios.isEmpty() && request.getMediaUrl() != null && !request.getMediaUrl().isBlank()) {
            medios.add(request.getMediaUrl());
        }

        if (medios.isEmpty()) {
            return new Medios(medios, null);
        }

        // Solo archivos de nuestro propio bucket. Al publicar, el servidor
        // DESCARGA cada URL para mandarsela a la red, asi que aceptar
        // cualquier texto aqui era dejar que un usuario con sesion hiciera
        // GET desde este servidor a lo que quisiera: la red interna del
        // docker, el proxy de la base, la metadata de la maquina. Los
        // medios legitimos siempre vienen de /media/presign, que devuelve
        // URLs con nuestro prefijo publico; cualquier otra no tiene por
        // que estar aqui.
        for (String url : medios) {
            if (!storage.esNuestra(url)) {
                throw new IllegalArgumentException(
                        "Los archivos deben subirse desde la app; no se aceptan enlaces externos.");
            }
        }

        boolean hayVideo = hayVideo(medios);
        if (hayVideo && medios.size() > 1) {
            throw new IllegalArgumentException(
                    "Un video se publica solo: quita las demas fotos o quita el video.");
        }

        int tope = limites.getMaxImagesPerPost();
        if (!hayVideo && medios.size() > tope) {
            throw new IllegalArgumentException(
                    "Puedes publicar hasta " + tope + " fotos en una misma publicacion.");
        }

        // El formato antes que las redes: sus reglas son las mas estrechas
        // —una historia lleva un archivo y dura un minuto— y conviene que el
        // mensaje que llegue sea el del formato, que es lo que se eligio, y no
        // el de una red que ademas se pasa.
        if (formato != null) {
            comprobarFormato(formato, accounts, medios, hayVideo, request);
        }

        comprobarRedes(request, accounts, medios, hayVideo);

        return new Medios(medios, hayVideo ? MediaType.VIDEO : MediaType.IMAGE);
    }

    /**
     * Facebook y LinkedIn publican en una Página, y hay que haber elegido cuál.
     *
     * <p>Se dice aquí, al guardar, porque es cuando todavía se puede arreglar
     * en un toque: ir a Redes y elegirla. Al publicar ya no hay nadie
     * mirando, y lo que pasaba era peor que un rechazo: upload-post recibía la
     * publicación sin página y salía donde él decidiera, o no salía, con un
     * error que no apuntaba a esto.
     */
    private void exigirPaginas(List<SocialAccount> accounts) {
        for (SocialAccount account : accounts) {
            if (account.sinPagina()) {
                throw new IllegalArgumentException(account.getPlatform().getLabel()
                        + " está conectada pero no tiene una página elegida. En Redes, elige en"
                        + " qué página publicar, o quita esa red de esta publicación.");
            }
        }
    }

    /**
     * Lo que cada red elegida acepta, comprobado ANTES de guardar.
     *
     * <p>Estas dos reglas ya existían, pero solo se aplicaban al publicar: el
     * video demasiado largo lo cazaba {@code PostPublishStore} y las fotos de
     * más no las cazaba nadie —se enteraba uno por el rechazo de la red—. En
     * los dos casos la publicación ya estaba guardada, a veces programada para
     * la madrugada, y el fallo aparecía cuando ya no había quien lo arreglara.
     *
     * <p>Aquí no se ajusta nada en silencio, al revés que con las imágenes y
     * el texto: quitar una foto o cortar un video es decidir qué se publica, y
     * eso no se hace a espaldas de quien lo escribió. Se dice qué red es y
     * cuánto admite, que es lo que hace falta para arreglarlo en un toque.
     */
    private void comprobarRedes(
            PostSaveRequest request,
            List<SocialAccount> accounts,
            List<String> medios,
            boolean hayVideo) {

        List<Platform> redes = accounts.stream()
                .map(SocialAccount::getPlatform)
                .distinct()
                .toList();

        if (redes.isEmpty()) {
            // Un borrador sin redes todavía: no hay contra qué comprobar.
            return;
        }

        for (Platform red : redes) {
            if (hayVideo) {
                if (videoLimits.excede(red, request.getVideoDurationSeconds())) {
                    throw new IllegalArgumentException("El video dura "
                            + VideoLimitsProperties.legible(request.getVideoDurationSeconds())
                            + " y " + red.getLabel() + " acepta hasta "
                            + VideoLimitsProperties.legible(videoLimits.maxSecondsFor(red))
                            + ". Quita esa red o acorta el video.");
                }
                continue;
            }

            int tope = EspecImagen.de(red).maxFotos();

            // Cero no es un tope apretado: es una red que no publica fotos.
            // "acepta hasta 0 fotos por publicacion" se lee como un error
            // nuestro, y lo que hay que hacer —quitarla o mandar un video— no
            // aparece por ningun lado.
            if (tope == 0) {
                throw new IllegalArgumentException(red.getLabel() + " solo publica video."
                        + " Quita esa red o publica un video en lugar de fotos.");
            }

            if (medios.size() > tope) {
                throw new IllegalArgumentException(red.getLabel() + " acepta hasta "
                        + tope + (tope == 1 ? " foto" : " fotos") + " por publicacion, y llevas "
                        + medios.size() + ".");
            }
        }
    }

    /**
     * ¿Alguno de estos medios es un video? Lo dicen sus filas en
     * {@code media_assets}.
     *
     * <p>El tipo se guardó al subir a partir del content-type que se firmó, que
     * es lo más cerca de la verdad que hay: la extensión de la URL es un
     * parecido, y un archivo servido desde fuera o con parámetros detrás no la
     * tiene. Una sola consulta para todos los medios, no una por cada uno.
     *
     * <p>Solo se cae a la extensión para lo que no tiene fila: una URL externa
     * —{@code POST /posts} las acepta— no está en nuestra base, y ahí el
     * parecido es todo lo que hay.
     */
    private boolean hayVideo(List<String> medios) {
        Map<String, MediaType> tipos = mediaAssetRepository.findByUrlIn(medios).stream()
                .filter(asset -> asset.getUrl() != null && asset.getType() != null)
                .collect(Collectors.toMap(
                        MediaAsset::getUrl,
                        MediaAsset::getType,
                        // Dos filas con la misma URL no deberían existir; si
                        // las hubiera, la primera vale tanto como la otra.
                        (primera, segunda) -> primera));

        return medios.stream().anyMatch(url -> {
            MediaType tipo = tipos.get(url);
            return tipo == null ? Post.esVideoPorExtension(url) : tipo == MediaType.VIDEO;
        });
    }

    /** Los medios de una publicación y el tipo que resultaron ser. */
    private record Medios(List<String> urls, MediaType tipo) {
    }

    private Post findOrThrow(UUID id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Publicacion no encontrada."));
    }

    /**
     * El fotograma del video de esta publicación, si lo hay.
     *
     * <p>Una sola consulta por el primer medio, y solo cuando es video: en una
     * foto la miniatura es la propia foto, y preguntar por ella sería una
     * consulta por fila para no enseñar nada distinto.
     *
     * <p>Devuelve {@code null} sin ruido cuando no hay fila —un medio de fuera,
     * o uno borrado— porque quedarse sin portada no es un error: la pantalla
     * cae al icono de siempre.
     */
    private String miniaturaDe(Post post) {
        // La copia de la publicación manda: es la que sobrevive a que alguien
        // borre el archivo para hacer sitio.
        if (post.getThumbnailUrl() != null && !post.getThumbnailUrl().isBlank()) {
            return post.getThumbnailUrl();
        }
        // Y si no la tiene —publicaciones anteriores a la columna, o un video
        // cuyo fotograma se sacó después de guardarla— se busca donde estaba
        // antes.
        return miniaturaDelArchivo(post);
    }

    /** El fotograma en la fila del archivo, o {@code null} si ya no hay fila. */
    private String miniaturaDelArchivo(Post post) {
        if (!post.esVideo()) {
            return null;
        }
        String primero = post.primerMedio();
        if (primero == null) {
            return null;
        }
        return mediaAssetRepository.findByUrlIn(List.of(primero)).stream()
                .map(MediaAsset::getThumbnailUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
    }

    private PostResponse toResponse(Post post) {
        List<PostTargetResponse> targets = post.getTargets().stream()
                .map(target -> PostTargetResponse.builder()
                        .id(target.getId())
                        .socialAccountId(target.getSocialAccount().getId())
                        .platform(target.getSocialAccount().getPlatform())
                        .accountName(target.getSocialAccount().getAccountName())
                        .status(target.getStatus())
                        .publishedAt(target.getPublishedAt())
                        .externalPostId(target.getExternalPostId())
                        .externalUrl(target.getExternalUrl())
                        .caption(target.getCaption())
                        .captionEnviado(target.getCaptionEnviado())
                        .errorMessage(target.getErrorMessage())
                        .build())
                .toList();

        return PostResponse.builder()
                .id(post.getId())
                .caption(post.getCaption())
                .brief(post.getBrief())
                .titulo(post.getTitulo())
                // Se manda la lista Y el primero como mediaUrl: una app que
                // solo conoce el campo viejo sigue enseñando su miniatura.
                .mediaUrls(List.copyOf(post.getMediaUrls()))
                .mediaUrl(post.primerMedio())
                .thumbnailUrl(miniaturaDe(post))
                .videoDurationSeconds(post.getVideoDurationSeconds())
                .format(post.getFormat() == null ? null : post.getFormat().name())
                .status(post.getStatus())
                .scheduledAt(post.getScheduledAt())
                .publishedAt(post.getPublishedAt())
                .createdAt(post.getCreatedAt())
                .archivedAt(post.getArchivedAt())
                .targets(targets)
                .build();
    }
}
