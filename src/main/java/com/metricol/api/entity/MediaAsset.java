package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
