package com.metricol.api.entity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.entity.converter.StringSetConverter;
import com.metricol.api.enums.ObjetivoRedes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workspaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String logoUrl;

    /**
     * El "user"/perfil con el que este workspace está dado de alta en
     * upload-post.com (ahí es donde se conectan de verdad las cuentas de
     * cada red). Se configura una vez en Ajustes; sin esto no se puede
     * publicar de verdad, solo queda simulado.
     */
    private String uploadPostProfile;

    /**
     * El giro del negocio, en palabras de la persona: "Restaurante",
     * "Inmobiliaria", "Estetica".
     *
     * <p>Texto libre y no un enum, al reves que {@link #objetivo}: la lista
     * que ofrece la pantalla es una ayuda para elegir rapido, no una jaula, y
     * quien no se ve en ella tiene que poder escribir lo suyo. Lo unico que lo
     * consume es el prompt de la IA, que entiende la palabra tal cual.
     */
    private String giro;

    /**
     * Donde opera. Opcional.
     *
     * <p>Le da sitio a lo que escribe la IA: "en Merida" dicho por el negocio
     * vale mas que cualquier frase generica, y es lo que separa una
     * publicacion de barrio de una de folleto.
     */
    private String ciudad;

    /**
     * Que hace el negocio, en una o dos frases suyas.
     *
     * <p>Es el dato que mas cambia lo que escribe la IA: sin el, "publica algo
     * del 2x1" no sabe si es de tacos o de unas.
     */
    @Column(length = 500)
    private String descripcion;

    /** Que busca conseguir. Ver {@link ObjetivoRedes}. */
    @Enumerated(EnumType.STRING)
    private ObjetivoRedes objetivo;

    /**
     * El color de su inicial cuando no hay logotipo.
     *
     * <p>Existe porque el logotipo casi nunca está el día que se da de alta el
     * cliente, y una lista de espacios todos iguales obliga a leer el nombre
     * completo de cada uno para distinguirlos. Un color se reconoce de un
     * vistazo, que es justo lo que hace falta antes de publicar.
     */
    @Column(length = 20)
    private String color;

    /**
     * Etiquetas para agrupar clientes: "Restaurantes", "Mensual", "Mérida".
     *
     * <p>Texto libre y no una tabla: cada agencia ordena a sus clientes por lo
     * que a ella le sirve, y un catálogo cerrado obligaría a que le sirviera
     * el nuestro.
     */
    @Builder.Default
    @Convert(converter = StringSetConverter.class)
    @Column(length = 300)
    private Set<String> tags = new LinkedHashSet<>();

    /**
     * La agencia dueña de este cliente.
     *
     * <p>Nullable en la base para que los workspaces de antes sigan abriendo
     * mientras el arranque les crea la suya (ver OrganizationBackfill). En
     * código, a partir de ahí, siempre tiene una.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /**
     * Archivado: deja de aparecer y deja de publicar, pero no se borra nada.
     *
     * <p>Se archiva en vez de borrar porque borrar se lleva por delante las
     * publicaciones, los archivos de Cloudflare R2 y el perfil de upload-post
     * con sus redes conectadas, y eso no tiene vuelta atrás. Un cliente que
     * "ya no" suele volver, o pide su contenido tres meses después. El borrado
     * definitivo se pide a soporte, igual que la eliminación de una cuenta.
     */
    private LocalDateTime archivedAt;

    public boolean archivado() {
        return archivedAt != null;
    }

    @CreationTimestamp
    private LocalDateTime createdAt;
}
