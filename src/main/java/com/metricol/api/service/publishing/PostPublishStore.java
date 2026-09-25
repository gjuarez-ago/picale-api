package com.metricol.api.service.publishing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.DailyQuotaProperties;
import com.metricol.api.config.VideoLimitsProperties;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Las dos transacciones cortas de una publicación: la que prepara y la que
 * apunta el resultado. En medio, sin transacción, va la llamada al proveedor
 * (ver {@link PublishPlan}).
 *
 * <p>Está separada del publisher porque {@code @Transactional} solo actúa
 * cuando la llamada cruza el proxy de Spring: si el publisher se llamara a sí
 * mismo estos métodos, correrían sin transacción y no habría nada que
 * confirmar ni deshacer.
 */
@Service
public class PostPublishStore {

    private static final Logger log = LoggerFactory.getLogger(PostPublishStore.class);

    /**
     * Cuánto se espera a una red sin respuesta antes de cerrarla: un día.
     *
     * <p>Eran veinte minutos y el criterio estaba al revés. Cerrar una red sin
     * dato la marca como fallida, y "fallida" en la app tiene un botón de
     * corregir que la vuelve a publicar; si en realidad había salido, eso es
     * publicar dos veces. Contra ese daño, que un destino se quede un día en
     * "publicando" hasta saberse es barato. Y en ese día se pregunta con
     * espera creciente —ver {@link #esperaSegun}—, así que no son miles de
     * llamadas.
     */
    static final Duration ESPERA_MAXIMA = Duration.ofHours(24);

    /**
     * Cuánto esperar antes de volver a preguntar, según cuánto lleva abierto
     * el envío.
     *
     * <p>Crece con el tiempo porque lo que tarda de verdad también: en los
     * envíos medidos, TikTok cierra entre 60 y 90 segundos después del acuse,
     * y las demás antes del minuto. Por eso al principio se pregunta cada
     * medio minuto, que es cuando de verdad cambia algo. Pasados diez minutos
     * ya es raro que cambie y preguntar cada medio minuto solo gasta la llave,
     * que es una para todos los negocios y tiene límite.
     */
    static Duration esperaSegun(Duration abierto) {
        if (abierto.compareTo(Duration.ofMinutes(2)) < 0) {
            return Duration.ofSeconds(30);
        }
        if (abierto.compareTo(Duration.ofMinutes(10)) < 0) {
            return Duration.ofMinutes(1);
        }
        if (abierto.compareTo(Duration.ofHours(1)) < 0) {
            return Duration.ofMinutes(5);
        }
        if (abierto.compareTo(Duration.ofHours(6)) < 0) {
            return Duration.ofMinutes(30);
        }
        return Duration.ofHours(1);
    }

    private final PostRepository postRepository;
    private final WorkspaceRepository workspaceRepository;
    private final VideoLimitsProperties videoLimits;
    private final PublishQuotaService cuotas;

    public PostPublishStore(
            PostRepository postRepository,
            WorkspaceRepository workspaceRepository,
            VideoLimitsProperties videoLimits,
            PublishQuotaService cuotas) {
        this.postRepository = postRepository;
        this.workspaceRepository = workspaceRepository;
        this.videoLimits = videoLimits;
        this.cuotas = cuotas;
    }

    /**
     * Deja el post en PUBLISHING, descarta las redes que no pueden recibirlo
     * y reserva su cuota del día. Devuelve lo que hay que enviar.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishPlan preparar(UUID postId, UUID workspaceId) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) {
            return vacio(postId, workspaceId,
                    PublishOutcome.permanent("La publicacion ya no existe."));
        }
        // Eliminarla cancela sus trabajos, pero uno que ya estuviera tomado
        // llega hasta aquí: se cierra sin publicar y sin ruido. Para la
        // persona esa publicación ya no existe.
        if (post.eliminada()) {
            return vacio(postId, workspaceId,
                    PublishOutcome.permanent("La publicacion se elimino antes de salir."));
        }

        // Se revisa el estado otra vez: entre que el despachador la encontró y
        // este momento la pudieron publicar a mano o cancelar, y publicar dos
        // veces no se deshace.
        if (post.getStatus() == PostStatus.PUBLISHED) {
            return vacio(postId, workspaceId,
                    PublishOutcome.permanent("Ya estaba publicada."));
        }

        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        String profile = workspace == null ? null : workspace.getUploadPostProfile();

        List<PostTarget> targets = post.getTargets();
        if (targets.isEmpty()) {
            post.setStatus(PostStatus.FAILED);
            postRepository.save(post);
            return vacio(postId, workspaceId,
                    PublishOutcome.permanent("La publicacion no tiene ninguna red de destino."));
        }

        // El perfil se comprueba ANTES de reservar cuota. Al revés —como
        // estaba— se apartaba el hueco del día de cada red para descubrir dos
        // líneas después que no había con qué publicar, y había que
        // devolverlo: contador arriba y abajo por algo que nunca iba a salir.
        if (profile == null || profile.isBlank()) {
            String motivo = "Falta conectar upload-post.com en Ajustes del workspace.";
            targets.forEach(target -> {
                if (target.getStatus() != PostTargetStatus.PUBLISHED) {
                    marcar(target, PostTargetStatus.FAILED, motivo);
                }
            });
            post.setStatus(PostStatus.FAILED);
            postRepository.save(post);
            return vacio(postId, workspaceId, PublishOutcome.permanent(motivo));
        }

        post.setStatus(PostStatus.PUBLISHING);

        List<PublishPlan.Destino> destinos = new ArrayList<>();
        boolean algunaSinCuota = false;

        for (PostTarget target : targets) {
            if (target.getStatus() == PostTargetStatus.PUBLISHED) {
                // Un reintento no vuelve a publicar donde ya salió.
                continue;
            }

            Platform platform = target.getSocialAccount().getPlatform();

            // Sin página elegida no se manda: upload-post publicaría en la que
            // él decidiera, o en ninguna. Se cubre aquí además de al guardar
            // por las programadas que se guardaron antes de esta comprobación.
            if (target.getSocialAccount().sinPagina()) {
                marcar(target, PostTargetStatus.FAILED, platform.getLabel()
                        + " no tiene una página elegida. Elígela en Redes y vuelve a intentar.");
                continue;
            }

            if (videoLimits.excede(platform, post.getVideoDurationSeconds())) {
                Integer tope = videoLimits.maxSecondsFor(platform);
                marcar(target, PostTargetStatus.FAILED, "El video dura "
                        + VideoLimitsProperties.legible(post.getVideoDurationSeconds())
                        + " y " + platform.getLabel() + " acepta hasta "
                        + VideoLimitsProperties.legible(tope) + ".");
                continue;
            }

            PublishQuotaService.Reserva reserva = cuotas.reservar(workspaceId, platform);
            if (!reserva.concedida()) {
                algunaSinCuota = true;
                marcar(target, PostTargetStatus.SKIPPED,
                        reserva == PublishQuotaService.Reserva.GLOBAL_AGOTADO
                                ? "El servicio alcanzo su tope diario de publicaciones. Sale manana en cuanto se reinicie."
                                : "Alcanzaste el limite diario de " + platform.getLabel()
                                        + ". Se publica manana en cuanto se reinicie el contador.");
                continue;
            }

            marcar(target, PostTargetStatus.PUBLISHING, null);
            destinos.add(new PublishPlan.Destino(target.getId(), platform, target.getCaption()));
        }

        if (destinos.isEmpty()) {
            // Nada que enviar. Si el motivo fue la cuota, la publicación no
            // ha fallado: sigue en cola y sale mañana, así que se deja en
            // QUEUED para que la pantalla no diga que se perdió.
            if (algunaSinCuota) {
                post.setStatus(PostStatus.QUEUED);
                postRepository.save(post);
                return vacio(postId, workspaceId, PublishOutcome.deferred(
                        "Cuota diaria agotada en todas las redes elegidas.",
                        DailyQuotaProperties.cuandoReintentar()));
            }
            post.setStatus(PostStatus.FAILED);
            postRepository.save(post);
            return vacio(postId, workspaceId,
                    PublishOutcome.permanent("Ninguna red pudo recibir esta publicacion."));
        }

        postRepository.save(post);

        return new PublishPlan(
                postId,
                workspaceId,
                profile,
                post.getCaption(),
                post.getTitulo(),
                List.copyOf(post.getMediaUrls()),
                post.esVideo(),
                post.formatoEfectivo(),
                Boolean.TRUE.equals(post.getMusicaAutomatica()),
                destinos,
                null);
    }

    /**
     * Guarda el acuse del proveedor: el envío ya está en sus manos.
     *
     * <p>Se escribe ANTES de saber cómo acabó, y esa es toda su razón de ser.
     * Mientras el identificador esté puesto y queden destinos abiertos, el
     * worker consulta en vez de volver a subir; sin él, un reintento
     * publicaría el mismo video por segunda vez.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void guardarEnvio(UUID postId, String requestId, LocalDateTime iniciadoEn) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setUploadRequestId(requestId);
            post.setUploadStartedAt(iniciadoEn);
            postRepository.save(post);
        });
    }

    /**
     * El envío que sigue abierto en el proveedor, o null si no hay ninguno.
     *
     * <p>Lo mira el worker nada más empezar: si esto contesta, no hay nada que
     * subir, solo que preguntar. "Abierto" lo deciden los destinos, no el
     * identificador: mientras alguno siga en PUBLISHING hay algo que
     * confirmar. El identificador ya no se borra al cerrar, para poder volver
     * a preguntar después si algo quedó mal guardado.
     */
    @Transactional(readOnly = true)
    public EnvioEnCurso envioEnCurso(UUID postId) {
        return postRepository.findById(postId)
                .filter(p -> p.getUploadStartedAt() != null)
                .filter(p -> p.getTargets().stream()
                        .anyMatch(t -> t.getStatus() == PostTargetStatus.PUBLISHING))
                .map(this::envioDe)
                .orElse(null);
    }

    /**
     * El rastro del último envío de una publicación, abierto o cerrado, o null
     * si la publicación no existe.
     *
     * <p>Es lo que necesita la reconciliación: volver a preguntar por una
     * publicación que ya se cerró. Los campos pueden venir en null si la
     * publicación es anterior a que se guardara el rastro.
     */
    // REQUIRES_NEW y no la transacción de la petición, a propósito. Esto se
    // llama desde el endpoint de operaciones, donde Spring ya abrió la sesión
    // de Hibernate al entrar la petición (open-in-view) y la ató al tenant
    // GLOBAL, porque ahí no hay usuario. El tenant que impone la
    // reconciliación llega después y esa sesión ya no lo ve: el post "no
    // existía". Con una transacción nueva se abre otra sesión, y esa sí
    // resuelve el tenant impuesto.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public EnvioEnCurso envioDe(UUID postId) {
        return postRepository.findById(postId).map(this::envioDe).orElse(null);
    }

    private EnvioEnCurso envioDe(Post post) {
        return new EnvioEnCurso(post.getUploadRequestId(), post.getUploadStartedAt(), perfilDe(post));
    }

    /** Un envío entregado al proveedor del que todavía se espera respuesta. */
    public record EnvioEnCurso(String requestId, LocalDateTime iniciadoEn, String profile) {
    }

    /**
     * Apunta lo que contestó el proveedor, red por red, y cierra el post.
     *
     * <p>Una red que no venga en la confirmación NO se da por publicada: se
     * queda abierta y se vuelve a preguntar. Antes pasaba lo contrario —si no
     * encontraba el detalle de una red, la daba por buena— y como upload-post
     * publica en diferido, eso era siempre: ninguna publicación llegó a
     * guardar identificador ni enlace, y una red que hubiera fallado habría
     * quedado marcada como publicada igual.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishOutcome aplicar(
            PublishPlan plan, ConfirmacionDelProveedor confirmacion,
            Map<String, String> textosEnviados) {
        Post post = postRepository.findById(plan.postId()).orElse(null);
        if (post == null) {
            return PublishOutcome.permanent("La publicacion se borro mientras salia.");
        }

        // Lo que de verdad se mandó a cada red, antes de mirar cómo le fue: se
        // guarda saliera o no. En una fallida es justo lo que hace falta para
        // entender el rechazo —si el texto se recortó, o si salió el que se
        // creía—, y es lo que la pantalla de corregir enseña al lado del
        // motivo.
        for (PublishPlan.Destino destino : plan.destinos()) {
            PostTarget target = buscar(post, destino.targetId());
            if (target != null) {
                apuntarEnviado(target, destino, textosEnviados);
            }
        }

        return resolver(post, confirmacion);
    }

    /**
     * Vuelve a preguntar por un envío que ya salió, sin volver a mandarlo.
     *
     * <p>Es el mismo cierre que {@link #aplicar}, pero sin plan: aquí no se
     * preparó nada ni se reservó cuota, solo se viene a ver cómo acabó lo que
     * ya está en manos del proveedor.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishOutcome confirmar(UUID postId, ConfirmacionDelProveedor confirmacion) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) {
            return PublishOutcome.permanent("La publicacion se borro mientras salia.");
        }
        return resolver(post, confirmacion);
    }

    /**
     * Cierra cada destino abierto con lo que dijo el proveedor.
     *
     * <p>Recorre los destinos en PUBLISHING y no una lista que venga de fuera,
     * porque los dos caminos que llegan aquí —el del envío recién mandado y el
     * de la consulta posterior— tienen que cerrar exactamente los mismos.
     */
    private PublishOutcome resolver(Post post, ConfirmacionDelProveedor confirmacion) {
        int publicadas = 0;
        int fallidas = 0;
        int sinRespuesta = 0;

        // El ÚNICO motivo para cerrar una red sin dato es llevar un día entero
        // esperándola. Que el proveedor diga "terminado" no basta: pasó que
        // cerró el envío sin la fila de una red que sí había salido. Y antes
        // de llegar aquí, ConsultaDeEnvio ya la buscó también en el historial.
        boolean seAgotoLaEspera = post.getUploadStartedAt() != null
                && post.getUploadStartedAt()
                        .isBefore(LocalDateTime.now(ZoneOffset.UTC).minus(ESPERA_MAXIMA));

        for (PostTarget target : post.getTargets()) {
            if (target.getStatus() != PostTargetStatus.PUBLISHING) {
                continue;
            }

            Platform platform = target.getSocialAccount().getPlatform();
            ResultadoDeRed resultado = confirmacion.porRed().get(platform);

            if (resultado == null) {
                if (!seAgotoLaEspera) {
                    // No se sabe. Ni publicada ni fallida: abierta, y se
                    // vuelve a preguntar. Fallida sin prueba fue lo que dejó
                    // un TikTok publicado marcado como "falló" en la app, con
                    // un botón de corregir que lo habría publicado otra vez.
                    sinRespuesta++;
                    continue;
                }
                marcar(target, PostTargetStatus.FAILED, "No pudimos confirmar si salio en "
                        + platform.getLabel() + ". Revisala antes de volver a publicar:"
                        + " puede que si haya salido.");
                fallidas++;
                continue;
            }

            if (aplicarResultado(target, resultado)) {
                publicadas++;
            } else {
                fallidas++;
            }
        }

        boolean algoPublicado = alguna(post, PostTargetStatus.PUBLISHED);
        boolean quedaPendiente = alguna(post, PostTargetStatus.SKIPPED);
        cerrarEstadoDelPost(post, algoPublicado, quedaPendiente, sinRespuesta > 0);
        postRepository.save(post);

        log.debug("Publicacion {}: {} salieron, {} con error, {} sin respuesta, pendientes de cuota: {}",
                post.getId(), publicadas, fallidas, sinRespuesta, quedaPendiente);

        // Redes de las que todavía no se sabe: se vuelve a preguntar, cada vez
        // con más calma. Aplazar no gasta intento, así que esperar no acerca la
        // publicación a rendirse.
        if (sinRespuesta > 0) {
            Duration abierto = post.getUploadStartedAt() == null
                    ? Duration.ZERO
                    : Duration.between(post.getUploadStartedAt(), LocalDateTime.now(ZoneOffset.UTC));
            return PublishOutcome.deferred(
                    sinRespuesta + " red(es) sin confirmar todavia; se vuelve a preguntar.",
                    LocalDateTime.now().plus(esperaSegun(abierto)));
        }

        if (!algoPublicado) {
            return quedaPendiente
                    ? PublishOutcome.deferred(
                            "Las redes que quedaban se toparon con su cuota diaria.",
                            DailyQuotaProperties.cuandoReintentar())
                    : PublishOutcome.permanent("Ninguna red acepto la publicacion.");
        }

        if (quedaPendiente) {
            return PublishOutcome.deferred(
                    "Publicada en " + publicadas + " red(es); el resto espera su cuota.",
                    DailyQuotaProperties.cuandoReintentar());
        }

        return fallidas == 0
                ? PublishOutcome.success("Publicada en " + publicadas + " red(es).")
                : PublishOutcome.partial(publicadas + " publicada(s), " + fallidas + " con error.");
    }

    /**
     * Vuelve a poner una publicación ya cerrada de acuerdo con lo que el
     * proveedor dice hoy, red por red.
     *
     * <p>No se limita a los destinos abiertos, como {@link #confirmar}: aquí
     * se sobrescribe lo guardado con la respuesta del proveedor, que es la
     * fuente de verdad. Es para publicaciones que quedaron mal —un TikTok que
     * salió marcado como fallido, redes publicadas sin su enlace— y por eso va
     * por el endpoint de operaciones. Lo que el proveedor no menciona no se
     * toca.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reconciliacion reconciliar(UUID postId, String requestId, ConfirmacionDelProveedor confirmacion) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("La publicacion no existe."));

        // El identificador se guarda si no lo había: es lo que permite volver
        // a preguntar la próxima vez sin buscarlo en el registro.
        if (requestId != null && post.getUploadRequestId() == null) {
            post.setUploadRequestId(requestId);
        }

        int publicadas = 0;
        int fallidas = 0;
        int sinDato = 0;

        for (PostTarget target : post.getTargets()) {
            ResultadoDeRed resultado = confirmacion.porRed().get(target.getSocialAccount().getPlatform());
            if (resultado == null) {
                sinDato++;
                continue;
            }
            if (aplicarResultado(target, resultado)) {
                publicadas++;
            } else {
                fallidas++;
            }
        }

        cerrarEstadoDelPost(post,
                alguna(post, PostTargetStatus.PUBLISHED),
                alguna(post, PostTargetStatus.SKIPPED),
                alguna(post, PostTargetStatus.PUBLISHING));
        postRepository.save(post);

        log.info("Publicacion {} reconciliada con el proveedor: {} publicadas, {} fallidas, {} sin dato",
                postId, publicadas, fallidas, sinDato);
        return new Reconciliacion(publicadas, fallidas, sinDato, confirmacion.terminado());
    }

    /** Qué cambió al reconciliar. {@code sinDato} son redes de las que el proveedor no dijo nada. */
    public record Reconciliacion(int publicadas, int fallidas, int sinDato, boolean proveedorTermino) {
    }

    /**
     * Escribe en el destino lo que el proveedor dijo de su red.
     *
     * @return true si quedó publicada
     */
    private boolean aplicarResultado(PostTarget target, ResultadoDeRed resultado) {
        if (resultado.publicada()) {
            target.setStatus(PostTargetStatus.PUBLISHED);
            if (target.getPublishedAt() == null) {
                target.setPublishedAt(LocalDateTime.now());
            }
            target.setErrorMessage(null);
            // Solo se pisa el enlace si viene uno: una fila sin enlace no debe
            // borrar el que ya se tenía.
            if (resultado.postId() != null) {
                target.setExternalPostId(resultado.postId());
            }
            if (resultado.url() != null) {
                target.setExternalUrl(resultado.url());
            }
            return true;
        }
        // La cuota NO se devuelve aquí: la llamada llegó a la red y la red
        // decidió. Para el proveedor ese intento cuenta, y devolverla dejaria
        // reintentar sin fin contra un limite que ya se estaba tocando.
        marcar(target, PostTargetStatus.FAILED, resultado.error());
        return false;
    }

    private boolean alguna(Post post, PostTargetStatus estado) {
        return post.getTargets().stream().anyMatch(t -> t.getStatus() == estado);
    }

    /**
     * El estado del post a partir del de sus destinos.
     *
     * <p>Publicado si salió en alguna red, aunque otra fallara: para la persona
     * su publicación está afuera. En cola si nada salió pero algo sigue
     * pendiente, sea por cuota o por confirmar. Fallido solo cuando ya no hay
     * nada que esperar.
     */
    private void cerrarEstadoDelPost(Post post, boolean algoPublicado, boolean quedaPendiente, boolean quedaAbierto) {
        if (algoPublicado) {
            post.setStatus(PostStatus.PUBLISHED);
            if (post.getPublishedAt() == null) {
                post.setPublishedAt(LocalDateTime.now());
            }
        } else if (quedaPendiente || quedaAbierto) {
            post.setStatus(PostStatus.QUEUED);
        } else {
            post.setStatus(PostStatus.FAILED);
        }
    }

    /**
     * La llamada al proveedor ni llegó a contestar. Devuelve la cuota
     * reservada —no se publico nada, y comersela seria cobrarle a alguien el
     * mal dia del proveedor— y deja el post donde el reintento lo encuentre.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aplicarError(
            PublishPlan plan,
            String motivo,
            boolean habraOtroIntento,
            Map<String, String> textosEnviados) {
        plan.destinos().forEach(destino -> cuotas.devolver(plan.workspaceId(), destino.platform()));

        Post post = postRepository.findById(plan.postId()).orElse(null);
        if (post == null) {
            return;
        }

        for (PublishPlan.Destino destino : plan.destinos()) {
            PostTarget target = buscar(post, destino.targetId());
            if (target == null) {
                continue;
            }

            // El texto que se intento mandar, aunque no haya llegado respuesta
            // que leer. Es el camino por el que se perdia: un timeout o un
            // rechazo seco dejaban la fila sin texto, y es justo el momento en
            // que alguien abre el detalle a ver que se envio.
            apuntarEnviado(target, destino, textosEnviados);

            // Con otro intento por venir se queda en cola, no en fallido: la
            // pantalla diria que se perdio algo que va a salir en un rato.
            marcar(target,
                    habraOtroIntento ? PostTargetStatus.QUEUED : PostTargetStatus.FAILED,
                    motivo);
        }

        post.setStatus(habraOtroIntento ? PostStatus.QUEUED : PostStatus.FAILED);
        postRepository.save(post);
    }

    /** Cierra el post como fallido cuando la cola se rinde con él. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void marcarFallidaDefinitiva(UUID postId, String motivo) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setStatus(PostStatus.FAILED);
            post.getTargets().stream()
                    .filter(t -> t.getStatus() == PostTargetStatus.QUEUED
                            || t.getStatus() == PostTargetStatus.PUBLISHING
                            || t.getStatus() == PostTargetStatus.PENDING)
                    .forEach(t -> marcar(t, PostTargetStatus.FAILED, motivo));
            postRepository.save(post);
        });
    }

    /**
     * Un plan que no hay que enviar: ya se sabe el resultado. El formato va en
     * {@code PHOTO} por poner algo coherente —no se va a leer, porque
     * {@code hayQueEnviar()} es false— y no en nulo, que es lo que obligaría a
     * comprobarlo en cada sitio por un caso que no llega.
     */
    private PublishPlan vacio(UUID postId, UUID workspaceId, PublishOutcome atajo) {
        return new PublishPlan(postId, workspaceId, null, null, null, List.of(), false,
                PostFormat.PHOTO, false, List.of(), atajo);
    }

    /**
     * El nombre de este negocio en upload-post.
     *
     * <p>Se saca del post y no del plan porque en el camino de confirmar no
     * hay plan: no se preparo nada, solo se viene a preguntar por un envio que
     * ya salio.
     */
    private String perfilDe(Post post) {
        try {
            return workspaceRepository.findById(UUID.fromString(post.getTenantId()))
                    .map(Workspace::getUploadPostProfile)
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private PostTarget buscar(Post post, UUID targetId) {
        return post.getTargets().stream()
                .filter(t -> targetId.equals(t.getId()))
                .findFirst()
                .orElse(null);
    }

    private void marcar(PostTarget target, PostTargetStatus estado, String motivo) {
        target.setStatus(estado);
        target.setErrorMessage(motivo == null ? null : recortar(motivo));
    }

    private String recortar(String texto) {
        return texto.length() <= 500 ? texto : texto.substring(0, 497) + "...";
    }

    /**
     * Apunta en el destino lo que de verdad se le mando a esa red.
     *
     * <p>Vive en un metodo porque lo llaman los dos finales posibles de una
     * publicacion —la respuesta del proveedor y el fallo sin respuesta— y
     * tienen que guardar exactamente lo mismo. Cuando solo lo hacia el
     * primero, lo que reventaba por un timeout se quedaba sin texto.
     *
     * <p>Nunca lo borra: un mapa sin esta red —o con el texto vacio— deja lo
     * que ya hubiera. En un reintento vale mas el texto del intento anterior
     * que un hueco.
     */
    private void apuntarEnviado(
            PostTarget target,
            PublishPlan.Destino destino,
            Map<String, String> textosEnviados) {
        String enviado = textosEnviados.get(destino.platform().name());
        if (enviado != null && !enviado.isBlank()) {
            target.setCaptionEnviado(enviado);
        }
    }
}
