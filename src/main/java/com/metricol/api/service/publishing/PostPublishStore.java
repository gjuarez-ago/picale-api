package com.metricol.api.service.publishing;

import java.time.LocalDateTime;
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
                List.copyOf(post.getMediaUrls()),
                post.esVideo(),
                post.formatoEfectivo(),
                destinos,
                null);
    }

    /**
     * Apunta lo que contestó el proveedor, red por red, y cierra el post.
     *
     * <p>Tolerante a propósito con el formato de la respuesta: upload-post no
     * documenta del todo el shape de sus resultados, así que si no encuentra
     * el detalle de una red pero la llamada global fue bien, la da por
     * publicada (ya validó las plataformas antes de aceptar el request).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishOutcome aplicar(
            PublishPlan plan, Map<String, Object> respuesta, Map<String, String> textosEnviados) {
        Post post = postRepository.findById(plan.postId()).orElse(null);
        if (post == null) {
            return PublishOutcome.permanent("La publicacion se borro mientras salia.");
        }

        int publicadas = 0;
        int fallidas = 0;

        for (PublishPlan.Destino destino : plan.destinos()) {
            PostTarget target = buscar(post, destino.targetId());
            if (target == null) {
                continue;
            }

            // Lo que de verdad se mandó a esta red, antes de mirar cómo le
            // fue: se guarda saliera o no. En una fallida es justo lo que hace
            // falta para entender el rechazo —si el texto se recortó, o si
            // salió el que se creía—, y es lo que la pantalla de corregir
            // enseña al lado del motivo.
            apuntarEnviado(target, destino, textosEnviados);

            Detalle detalle = leerDetalle(destino.platform(), respuesta);
            if (detalle.ok()) {
                target.setStatus(PostTargetStatus.PUBLISHED);
                target.setPublishedAt(LocalDateTime.now());
                target.setErrorMessage(null);
                target.setExternalPostId(detalle.postId());
                target.setExternalUrl(detalle.url());
                publicadas++;
            } else {
                // La cuota NO se devuelve aquí: la llamada llegó a la red y
                // la red decidió. Para el proveedor ese intento cuenta, y
                // devolverla dejaria reintentar sin fin contra un limite que
                // ya se estaba tocando.
                marcar(target, PostTargetStatus.FAILED, detalle.error());
                fallidas++;
            }
        }

        // Cuenta lo ya publicado en intentos anteriores: si la primera vuelta
        // saco Instagram y esta saca Facebook, el post esta publicado.
        boolean algoPublicado = publicadas > 0 || post.getTargets().stream()
                .anyMatch(t -> t.getStatus() == PostTargetStatus.PUBLISHED);
        boolean quedaPendiente = post.getTargets().stream()
                .anyMatch(t -> t.getStatus() == PostTargetStatus.SKIPPED);

        if (algoPublicado) {
            post.setStatus(PostStatus.PUBLISHED);
            if (post.getPublishedAt() == null) {
                post.setPublishedAt(LocalDateTime.now());
            }
        } else if (quedaPendiente) {
            post.setStatus(PostStatus.QUEUED);
        } else {
            post.setStatus(PostStatus.FAILED);
        }
        postRepository.save(post);

        log.debug("Publicacion {}: {} salieron, {} con error, pendientes de cuota: {}",
                plan.postId(), publicadas, fallidas, quedaPendiente);

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
        return new PublishPlan(postId, workspaceId, null, null, List.of(), false,
                PostFormat.PHOTO, List.of(), atajo);
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
     * Saca de la respuesta lo que dice de una red: si salió, su id y el enlace
     * a la publicación. El enlace es lo que permite que la app ofrezca "ver
     * publicacion" en vez de dejar a la persona buscandola en la red.
     */
    @SuppressWarnings("unchecked")
    private Detalle leerDetalle(Platform platform, Map<String, Object> respuesta) {
        String clave = platform.name().toLowerCase();
        Object resultsObj = respuesta.get("results");

        if (resultsObj instanceof Map<?, ?> results && results.get(clave) instanceof Map<?, ?> fila) {
            Map<String, Object> detalle = (Map<String, Object>) fila;
            boolean ok = !Boolean.FALSE.equals(detalle.get("success")) && detalle.get("error") == null;
            if (!ok) {
                return new Detalle(false, null, null,
                        texto(detalle.get("error"), "La red rechazo la publicacion."));
            }
            return new Detalle(true,
                    texto(primero(detalle, "post_id", "id"), null),
                    texto(primero(detalle, "url", "post_url", "permalink", "link"), null),
                    null);
        }

        boolean exitoGeneral = !Boolean.FALSE.equals(respuesta.get("success"));
        if (exitoGeneral) {
            return new Detalle(true, null, null, null);
        }
        return new Detalle(false, null, null,
                texto(respuesta.get("message"), "No se pudo confirmar la publicacion."));
    }

    private Object primero(Map<String, Object> mapa, String... claves) {
        for (String clave : claves) {
            Object valor = mapa.get(clave);
            if (valor != null) {
                return valor;
            }
        }
        return null;
    }

    private String texto(Object valor, String porDefecto) {
        return valor == null ? porDefecto : valor.toString();
    }

    private record Detalle(boolean ok, String postId, String url, String error) {
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
