package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostTargetStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostTargetResponse {
    private UUID id;
    private UUID socialAccountId;
    private Platform platform;
    private String accountName;
    private PostTargetStatus status;
    private LocalDateTime publishedAt;
    private String externalPostId;

    /**
     * Enlace a la publicación en la red, cuando el proveedor lo devuelve.
     * {@code null} significa que no hay adónde llevar a nadie: la app esconde
     * el botón en vez de abrir una página muerta.
     */
    private String externalUrl;

    /**
     * El texto que de verdad salio a esta red, ya recortado a lo que admite.
     *
     * <p>Distinto del de la publicacion cuando el recorte entro en juego:
     * Facebook admite 255 en el titulo y TikTok 90. Es lo que se ensena en el
     * detalle, porque es lo que la gente vio en la red.
     *
     * <p>{@code null} en lo que aun no ha salido y en lo publicado antes de
     * que esto se guardara.
     */
    private String captionEnviado;

    /**
     * El texto decidido para ESTA red, antes de salir.
     *
     * <p>Es el que escribio la IA para ella —o el que se edito a mano en la
     * vista previa— ya recortado a lo que admite. {@code null} cuando esta red
     * no tiene uno propio: entonces sale el de la publicacion.
     *
     * <p>Distinto de {@link #captionEnviado}, que es lo que de verdad se
     * mando y solo existe cuando ya salio. Los dos hacen falta: antes de
     * publicar, este es lo unico que responde "que va a decir en Instagram".
     */
    private String caption;

    private String errorMessage;
}
