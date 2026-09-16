package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.SocialAccountStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "social_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Enumerated(EnumType.STRING)
    private Platform platform;

    private String accountName;

    /**
     * La foto de la cuenta o de la Página donde va a salir la publicación.
     *
     * <p>Importa sobre todo en Facebook y LinkedIn: ahí no se publica en tu
     * perfil sino en una Página, y una cuenta puede administrar varias. Con
     * solo el logo de la red, dos Páginas distintas se ven idénticas, y no
     * hay forma de notar que está fijada la equivocada hasta que la
     * publicación sale en el sitio que no era.
     *
     * <p>{@code null} cuando la red no da foto o la cuenta no tiene Página
     * fijada; ahí la pantalla cae al logo de la red de siempre.
     */
    @Column(length = MAX_AVATAR_URL)
    private String avatarUrl;

    /**
     * Tope de la URL de la foto.
     *
     * <p>Estaba en 500 y no alcanzaba: las fotos de Página de Facebook son
     * URLs firmadas de su CDN, con el token y la caducidad dentro, y pasan
     * holgadamente de 500 caracteres. El insert reventaba y se llevaba por
     * delante la consulta de conexiones entera — no se podían ni ver las
     * redes conectadas por no caber una foto.
     *
     * <p>2048 es el límite práctico de una URL. Y aun así el servicio que
     * guarda comprueba el largo antes: una foto que no quepa debe perderse
     * sola, no tumbar nada.
     */
    public static final int MAX_AVATAR_URL = 2048;

    /**
     * El id de la Página fijada en upload-post, cuando la hay. No se enseña:
     * sirve para saber si la Página cambió sin comparar nombres, que se
     * repiten.
     */
    private String pageId;

    @Enumerated(EnumType.STRING)
    private SocialAccountStatus status;

    /**
     * La apagó la persona desde la app, no el proveedor.
     *
     * <p>Existe porque desconectar de verdad no está en nuestra mano: los
     * tokens viven en upload-post y nuestro cliente no conoce ningún endpoint
     * suyo para revocarlos. Sin esta marca, borrar o desconectar la fila no
     * servía de nada — la siguiente consulta de estado la encontraba
     * autorizada y la volvía a poner en verde.
     *
     * <p>Con ella, {@code SocialAccountSyncService} respeta la decisión: la
     * red deja de aparecer donde se publica aunque el proveedor siga
     * diciendo que está conectada. Se limpia al volver a conectarla a
     * propósito, que es la única señal fiable de que se cambió de opinión.
     *
     * <p>{@code Boolean} y no {@code boolean}: la columna nace nula en las
     * filas anteriores, y un primitivo no admite nulo.
     */
    private Boolean desactivadaPorUsuario;

    private LocalDateTime connectedAt;

    /** Nulo cuenta como "no", que es lo que era antes de existir la columna. */
    public boolean apagadaPorLaPersona() {
        return Boolean.TRUE.equals(desactivadaPorUsuario);
    }

    /**
     * ¿Esta red exige elegir una Página? Solo Facebook.
     *
     * <p>En Facebook no se publica en el perfil personal sino en una Página, y
     * una cuenta puede administrar varias: sin decir cuál, upload-post publica
     * en la primera que encuentre —o en ninguna—. LinkedIn es distinto: sin
     * organización elegida publica en el perfil personal, que es un destino
     * válido y el más común. Ahí la organización es opcional y no se exige.
     */
    public boolean exigePagina() {
        return platform == Platform.FACEBOOK;
    }

    /**
     * Conectada pero sin página elegida: no puede recibir publicaciones.
     *
     * <p>Es la comprobación que faltaba. La pantalla de redes avisaba
     * «elige la página», pero ni la captura ni el guardado la miraban, así
     * que un reel se iba a Facebook sin que nadie supiera a qué página.
     */
    public boolean sinPagina() {
        return exigePagina() && (pageId == null || pageId.isBlank());
    }
}
