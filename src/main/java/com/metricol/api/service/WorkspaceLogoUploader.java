package com.metricol.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.models.response.MediaAssetResponse;

/**
 * Sube el logotipo de un espacio en su propio nombre, no en el de quien lo
 * sube.
 *
 * <p><b>Por qué existe esta clase aparte, y no una llamada directa a
 * {@link MediaService#upload} desde {@link WorkspaceMembershipService}.</b>
 * El modal grande de administrar espacios deja tocar cualquier espacio de la
 * organización sin cambiar a él primero —esa es la idea del modal—, así que
 * quien sube el logotipo de "Gimnasio B" puede tener "Taquería A" como
 * espacio activo. {@code MediaAsset} lleva {@code @TenantId}, y con
 * {@code spring.jpa.open-in-view} la sesión de Hibernate de la petición ya
 * abrió —y con ella ya resolvió el tenant— desde antes de llegar aquí:
 * imponer el tenant correcto en el hilo actual no le cambia nada a una sesión
 * que ya está abierta. Hace falta una sesión NUEVA, abierta después de
 * imponerlo, que es lo que {@link Propagation#REQUIRES_NEW} garantiza. Es el
 * mismo motivo, y la misma solución, que {@code MiniaturasEnSegundoPlano} /
 * {@code MiniaturaDeVideo} para las miniaturas de video.
 *
 * <p>Sin esto, el archivo se guardaba a nombre del espacio activo de quien lo
 * subía: contaba para su cuota y aparecía en su Contenido, no en el del
 * espacio al que en verdad pertenecía. Reportado el 18 sep 2026.
 */
@Service
public class WorkspaceLogoUploader {

    private final MediaService media;

    public WorkspaceLogoUploader(MediaService media) {
        this.media = media;
    }

    /**
     * @param workspaceId el espacio dueño del logotipo — no necesariamente el
     *                     activo de quien llama. Quien invoca ya comprobó que
     *                     esa persona administra la organización.
     */
    public MediaAssetResponse subirComo(MultipartFile file, UUID workspaceId) {
        MediaAssetResponse[] resultado = new MediaAssetResponse[1];
        TenantIdentifierResolver.comoTenant(workspaceId.toString(),
                () -> resultado[0] = subirEnTransaccionNueva(file, workspaceId));
        return resultado[0];
    }

    /**
     * Público a propósito, aunque nadie más lo llame: Spring solo intercepta
     * {@code @Transactional} en una llamada que cruza el proxy del bean, y
     * eso exige que el método sea público.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaAssetResponse subirEnTransaccionNueva(MultipartFile file, UUID workspaceId) {
        return media.upload(file, workspaceId);
    }
}
