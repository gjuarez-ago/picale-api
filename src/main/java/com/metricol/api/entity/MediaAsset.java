package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;

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
@Table(name = "media_assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    private String fileName;

    private String url;

    /**
     * La ruta del objeto dentro del bucket, ej. {@code media/<workspace>/<uuid>.jpg}.
     *
     * <p>Se guarda además de la URL porque es lo que hace falta para hablar
     * con R2 —consultar si el archivo llegó, borrarlo— y deducirla de la URL
     * pública obliga a recortar un prefijo que puede cambiar el día que se
     * ponga un dominio propio. Con la clave guardada, ese cambio no rompe el
     * borrado de lo que ya estaba subido.
     *
     * <p>{@code null} en las filas creadas antes de que esto existiera; ahí el
     * borrado sigue por el camino viejo de recortar la URL.
     */
    private String storageKey;

    /**
     * PENDING mientras solo está apartado el espacio; READY cuando el archivo
     * está de verdad en R2. Ver {@link MediaAssetStatus}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MediaAssetStatus status;

    @Enumerated(EnumType.STRING)
    private MediaType type;

    /**
     * Lo que pesa el archivo en R2, en bytes.
     *
     * <p>Se guarda al subir porque es la única forma de saber cuánto espacio
     * lleva usado un workspace sin preguntárselo al almacén: listar el bucket
     * entero en cada subida sería una llamada de red por archivo. La suma de
     * esta columna es la cuota (ver {@code StorageQuotaService}).
     *
     * <p>Puede ser {@code null} en las filas creadas antes de que esto
     * existiera; ahí cuentan como 0, que es lo único honesto que se puede
     * hacer sin ir a medirlas una por una.
     */
    private Long sizeBytes;

    /** El content-type con el que se subió; sirve para servirlo bien. */
    private String contentType;

    /**
     * Lo que la IA vio en este archivo, en una frase.
     *
     * <p>Vive aquí y no en una cache en memoria porque así se analiza UNA vez
     * en la vida del archivo: sobrevive a reinicios y da igual qué instancia
     * atienda la siguiente petición. Mirar una imagen cuesta dinero y casi dos
     * segundos, y el segundo es el que se nota — es justo lo que esta
     * aplicación promete no hacerte esperar.
     *
     * <p>Nulo mientras no se haya analizado, que es el estado normal de un
     * archivo recién subido.
     */
    @Column(length = 1000)
    private String descripcionIa;

    /**
     * Un fotograma del video, para poder enseñarlo sin reproducirlo.
     *
     * <p>Solo en videos; en una imagen es nulo porque la propia [url] ya
     * sirve de miniatura. Nulo tambien mientras se genera: se saca en segundo
     * plano despues de confirmar la subida, asi que hay unos segundos en los
     * que el video existe y su miniatura todavia no.
     */
    @Column(length = 1000)
    private String thumbnailUrl;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * Cuándo se archivó, o nulo si sigue a la vista.
     *
     * <p>En Pícale nada se elimina físicamente: "quitar" un archivo de Contenido
     * es archivarlo. Sale de la galería, se puede devolver, sigue en R2 y SIGUE
     * CONTANDO en la cuota —el archivo existe y se paga—. Ver
     * {@code MediaService.archive}.
     */
    private LocalDateTime archivedAt;
}
