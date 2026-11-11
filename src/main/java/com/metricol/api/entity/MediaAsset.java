package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.EtapaAgente;
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

// Cada guardado escribe solo lo que cambio. Varias tareas trabajan el mismo video a la vez
// (portada, transcripcion, recorte, agente) y sin esto la que guardaba al final reescribia
// la fila entera con lo que habia leido al empezar: el 3 nov 2026 la portada borro la
// transcripcion de un video recien sacada y salio sin subtitulos ni musica.
@DynamicUpdate
@Entity
@Table(name = "media_assets",
        indexes = @jakarta.persistence.Index(name = "ix_media_generation_job", columnList = "generation_job_id,tenant_id"),
        // La misma subida pedida dos veces (se perdió la respuesta, Android repitió el trabajo)
        // es UNA fila: lo garantiza la base, no un chequeo previo que dos peticiones pasan juntas.
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "ux_media_client_upload",
                columnNames = { "tenant_id", "client_upload_id" }))
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

    /** Explicit mobile selection, independent of upload speed. */
    private UUID uploadBatchId;
    private Integer uploadBatchIndex;
    private LocalDateTime uploadRenewedAt;

    /**
     * La clave que el teléfono le pone a una subida en segundo plano ANTES de pedir permiso. Con
     * ella, pedir otra vez devuelve la misma subida (sin apartar espacio dos veces), y la app que
     * vuelve horas después pregunta en qué quedó. Nula en la web y en las versiones viejas de la app.
     */
    @Column(name = "client_upload_id", length = 64)
    private String clientUploadId;

    /** Vincula la pieza pagada al trabajo incluso si cae antes de guardar el checkpoint. */
    private UUID generationJobId;
    private String generationVersion;
    private Integer generationIndex;

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

    /** Datos de una copia de video solicitada por la persona. */
    /**
     * La subida por partes en R2 mientras está en curso: su id y el tamaño de cada parte. Nulos en
     * la subida de un solo PUT y en cuanto se completa o se cancela.
     */
    @Column(length = 512)
    private String multipartUploadId;
    private Long multipartPartSize;

    private UUID sourceAssetId;
    private Double trimStartSeconds;
    private Double trimEndSeconds;
    private LocalDateTime processingStartedAt;
    @Column(length = 500)
    private String processingError;

    /**
     * Lo que se dice en este video, palabra por palabra con sus tiempos (JSON).
     * Se guarda para no volver a pagar la transcripción cada vez que alguien
     * corrige el texto o cambia de estilo.
     */
    @Column(columnDefinition = "text")
    private String transcripcion;

    /**
     * En una copia con subtítulos: el estilo, el color y las palabras que se
     * pintan (JSON). Es lo que la distingue de un recorte en la cola de video.
     */
    @Column(columnDefinition = "text")
    private String subtitulos;

    /**
     * En un video original con edición automática (subtítulos y/o música):
     * ESPERANDO mientras se hace (el agente no lo toma todavía), LISTO cuando
     * ya hay copia, SIN_SUBTITULOS si no se le puso nada (no habla nadie, o
     * falló) y YA_TENIA si ya traía subtítulos pegados y no llevó música. Nulo =
     * no aplica. Ver {@code SubtitulosAutomaticos}.
     */
    @Column(length = 20)
    private String subtitulosEstado;

    /** Cuántas veces se intentó ponerle los automáticos; a las tres se suelta sin ellos. */
    private Integer subtitulosIntentos;

    /**
     * En un video original con música automática: PUESTA si su copia la lleva,
     * YA_TENIA si ya traía música o sonido de fondo y se dejó así. Nulo = no aplica.
     */
    @Column(length = 20)
    private String musicaFondo;

    /** En una versión final recortada: qué se le quitó, en palabras de la persona. Nulo = sin recorte. */
    @Column(length = 300)
    private String recorteNota;

    /**
     * La huella del contenido que calcula la app al subir (SHA-256 de tamaño,
     * principio, medio y final del archivo). Reconoce el mismo archivo aunque
     * se llame distinto, y no confunde dos distintos que pesan lo mismo.
     * Nulo en lo subido antes de la huella o desde la web.
     */
    @Column(length = 64)
    private String huellaArchivo;

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

    /**
     * Si lo creó la IA (una imagen de Crear contenido con IA) y no la persona.
     *
     * <p>El agente solo trabaja lo que sube la gente: sin esto haría campañas
     * de sus propias campañas. Nulo = subido, que es lo que era todo antes.
     */
    private Boolean generadaPorIa;

    /**
     * La imagen la creó un modelo de IA (un diseño de Crear con IA o del
     * agente). No es lo mismo que {@link #generadaPorIa}, que también marca las
     * copias de una foto real (retocada o con el logo encima): esas son fotos
     * reales y no llevan la etiqueta de "hecha con IA" al publicarse.
     */
    private Boolean creadaConIa;

    /** En qué va con el agente. Nulo = no lo ha tocado. Ver {@link EtapaAgente}. */
    @Enumerated(EnumType.STRING)
    private EtapaAgente agenteEtapa;

    /** Por qué el agente lo dejó donde lo dejó, para enseñarlo tal cual. */
    @Column(length = 500)
    private String agenteMotivo;

    /** Cuándo lo tomó quien lo está revisando (etapa REVISANDO), para soltarlo si se cayó. */
    private LocalDateTime agenteTomadoEn;

    /** Cuántas veces no se pudo revisar. Al tercero va a Observación en vez de reintentar para siempre. */
    private Integer agenteIntentos;

    /** Cómo se ve, en 64 bits, para encontrar repetidas. Ver {@code HuellaDeImagen}. Nulo = sin calcular. */
    private Long huella;

    /**
     * Lo que el Analista entendió de un video (JSON de {@code AnalisisDeVideo}):
     * qué pasa, qué se dice, sus tomas y su mejor tramo. Se guarda para que los
     * agentes que vengan después —el editor de tomas, el de audio— no vuelvan
     * a pagar por mirarlo y escucharlo.
     */
    @Column(length = 8000)
    private String agenteAnalisis;

    /**
     * La persona la sacó de Observación o Descartadas diciendo que sí va. Lo
     * que se ve en estas le dice al revisor qué temas son de la marca, para no
     * volver a dudar de ellos. Nulo = no.
     */
    private Boolean agenteRescatada;

    public boolean deLaIa() {
        return Boolean.TRUE.equals(generadaPorIa);
    }
}
