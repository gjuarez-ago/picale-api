package com.metricol.api.service.social;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.PostPublishStore;

/**
 * Vuelve a poner una publicación de acuerdo con lo que el proveedor dice hoy.
 *
 * <p>Existe porque hubo publicaciones guardadas al revés de la realidad: un
 * TikTok que salió y quedó como fallido, tres redes publicadas sin su enlace.
 * El worker ya no las produce así, pero las que ya estaban no se arreglan
 * solas, y la persona ve "falló" donde hay un video publicado. Esto pregunta
 * otra vez, con la misma cadena que usa el worker, y sobrescribe lo guardado
 * con la respuesta.
 *
 * <p>Va por el endpoint de operaciones, no por la app: es quien opera la
 * plataforma quien decide reconciliar una publicación, y hace falta el
 * identificador del envío cuando la fila no lo guardó.
 */
@Service
public class ReconciliacionUploadPost {

    private final PostPublishStore store;
    private final ConsultaDeEnvio consulta;

    public ReconciliacionUploadPost(PostPublishStore store, ConsultaDeEnvio consulta) {
        this.store = store;
        this.consulta = consulta;
    }

    /**
     * @param workspaceId el negocio dueño de la publicación. Hace falta porque
     *                    aquí no hay sesión de usuario, y sin tenant Hibernate
     *                    no encuentra la fila.
     * @param requestId   el identificador del envío, si la publicación no lo
     *                    tiene guardado. Se saca del registro del servidor:
     *                    "upload-post acepto la publicacion ... (envio X)".
     */
    public PostPublishStore.Reconciliacion reconciliar(UUID workspaceId, UUID postId, String requestId) {
        // En un hilo aparte, y no en el de la petición, a propósito.
        //
        // Al entrar una petición web Spring ya abrió la sesión de Hibernate y
        // la ató al hilo (open-in-view), resuelta al tenant GLOBAL porque aquí
        // no hay usuario. El tenant que se impone abajo llega tarde para esa
        // sesión, y ni REQUIRES_NEW la sustituye: sin una transacción previa
        // que suspender, Spring reutiliza la sesión ya atada. El post salía
        // como inexistente. En un hilo limpio no hay sesión previa, y el
        // tenant impuesto es el que se usa: es exactamente como trabaja el
        // worker de publicación, que por eso nunca tuvo este problema.
        try {
            return CompletableFuture.supplyAsync(() -> conTenant(workspaceId, postId, requestId)).join();
        } catch (CompletionException ex) {
            // Que el 404 y el 400 lleguen al cliente como tales, no envueltos.
            if (ex.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw ex;
        }
    }

    private PostPublishStore.Reconciliacion conTenant(UUID workspaceId, UUID postId, String requestId) {
        AtomicReference<PostPublishStore.Reconciliacion> resultado = new AtomicReference<>();

        TenantIdentifierResolver.comoTenant(workspaceId.toString(), () -> {
            PostPublishStore.EnvioEnCurso guardado = store.envioDe(postId);
            if (guardado == null) {
                throw new ResourceNotFoundException("No hay una publicacion con ese id en ese workspace.");
            }

            String id = requestId != null && !requestId.isBlank() ? requestId.trim() : guardado.requestId();
            if (id == null && guardado.iniciadoEn() == null) {
                throw new IllegalStateException("Esta publicacion no tiene rastro del envio: ni identificador"
                        + " ni hora de entrega. Pasa el request_id que aparece en el registro del servidor.");
            }

            ConfirmacionDelProveedor confirmacion = consulta.preguntar(
                    new PostPublishStore.EnvioEnCurso(id, guardado.iniciadoEn(), guardado.profile()));
            resultado.set(store.reconciliar(postId, id, confirmacion));
        });

        return resultado.get();
    }
}
