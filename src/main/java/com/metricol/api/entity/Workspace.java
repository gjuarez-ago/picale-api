package com.metricol.api.entity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.entity.converter.BrandProfileConverter;
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
     * El resto de lo que la IA debe saber de la marca: qué vende, a quién le
     * habla, cómo suena, qué evitar y cómo contactarla. Ver {@link BrandProfile}.
     * Nulo = todavía no lo contó.
     */
    @Convert(converter = BrandProfileConverter.class)
    @Column(name = "brand_profile", length = 4000)
    private BrandProfile brandProfile;

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

    /**
     * ¿Tiene el perfil de negocio que hace falta para trabajar? Nombre, giro,
     * descripción y objetivo. La ciudad no cuenta: es opcional.
     *
     * <p>Es la regla de la web y de la app móvil, y vive AQUÍ para que las dos
     * lean la misma respuesta. Sin esto cada una la calculaba por su cuenta y
     * bastaba que una se desactualizara para que el mismo espacio fuera
     * «completo» en la computadora e «incompleto» en el teléfono.
     */
    public boolean perfilCompleto() {
        return lleno(name) && lleno(giro) && lleno(descripcion) && objetivo != null;
    }

    private static boolean lleno(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * El agente: la IA que revisa lo que se sube, lo prepara y lo deja en
     * "Por aprobar". Nulo = apagado, que es lo que era todo antes.
     */
    private Boolean agenteActivo;

    /**
     * Desde cuándo está encendido. Solo cuenta lo que se suba a partir de aquí:
     * encenderlo no debe gastarse de golpe el historial de la cuenta.
     */
    private LocalDateTime agenteDesde;

    /**
     * Qué días publica el agente, como números de día ISO separados por coma
     * (1 = lunes … 7 = domingo). Nulo = todos.
     */
    @Column(length = 20)
    private String agenteDias;

    /** Desde qué hora publica (0–23). Nulo = 9. */
    private Integer agenteHoraDesde;

    /** Hasta qué hora publica (1–24). Nulo = 21. */
    private Integer agenteHoraHasta;

    /**
     * Lo que el agente aprendió de cuánto diseño quiere la cuenta: de -1 a 2.
     * Sube cuando la persona descarta un diseño (diseñar menos), baja cuando
     * lo aprueba. Nulo = 0. Ver {@code DecisorDelAgente}.
     */
    private Integer agenteAjusteDiseno;

    /**
     * Cuánto prefiere la cuenta las fotos sin adornos (franja, marco), de 0 a
     * 2: sube al descartar o pedir quitar un acabado, baja al aprobarlo.
     * Nulo = 0. Ver {@code DecisorDelAgente.acabado}.
     */
    private Integer agenteAjusteAcabado;

    /** Las fechas del año ya atendidas ("MADRES-2026,PATRIAS-2026"): no se vuelven a proponer. */
    @Column(length = 1000)
    private String agenteFechasHechas;

    /** Las fotos que el asistente pidió esta semana, en JSON ([{que, consejo}]). */
    @Column(length = 3000)
    private String agenteTomas;

    /** Cuándo pidió esas fotos: cada siete días pide otras. */
    private java.time.LocalDateTime agenteTomasEn;

    /** La última vez que llenó un hueco por su cuenta (sin material): una por semana como mucho. */
    private java.time.LocalDateTime agenteUltimoRelleno;

    /** Las fechas que el dueño pidió preparar ("MADRES-2026"); las de {@link #agenteFechasHechas} solo se ofrecieron. */
    @Column(length = 1000)
    private String agenteFechasPreparadas;

    /** Lo último que se le pidió preparar ("Día de Muertos", "tu pieza de la semana") y cómo va. */
    @Column(length = 120)
    private String agentePiezaQue;

    /** PREPARANDO, LISTA, SIN_CREDITOS o NO_SALIO. */
    @Column(length = 20)
    private String agentePiezaEstado;

    private java.time.LocalDateTime agentePiezaEn;

    /** El archivo de logo que ya se revisó (si cambia el logo, se revisa otra vez). */
    @Column(length = 1000)
    private String logoRevisado;

    /** El "logo" es una foto (de una persona, de un lugar): no se pega como sello. */
    private Boolean logoEsFoto;

    /** Los colores del logotipo ("#1A3A6B,#2BA84A"), sacados al revisarlo. */
    @Column(length = 100)
    private String marcaColores;

    /** Créditos al mes que el asistente puede usar en mejoras y diseños. Nulo = el de siempre. */
    private Integer agentePresupuesto;

    /** El último aviso al teléfono de "tengo publicaciones listas": entre uno y otro pasa al menos una hora. */
    private java.time.LocalDateTime agenteUltimoAviso;

    /**
     * Cómo trabaja el negocio: códigos de {@link com.metricol.api.enums.RasgoDelNegocio}
     * separados por coma. Nulo = todavía no se deducen; vacío = ninguno.
     */
    @Column(length = 300)
    private String perfilRasgos;

    /** Los rasgos los puso el dueño: ya no se vuelven a deducir solos. */
    private Boolean perfilRasgosDelDueno;

    /** Los rasgos, o {@code null} si todavía no se saben. */
    public java.util.Set<com.metricol.api.enums.RasgoDelNegocio> rasgos() {
        return com.metricol.api.enums.RasgoDelNegocio.de(perfilRasgos);
    }

    public boolean conAgente() {
        return Boolean.TRUE.equals(agenteActivo);
    }

    // ------------------------------------------------------------ ubicación
    // Dónde está el negocio, para etiquetar sus publicaciones. Cada red la pide
    // a su manera (Facebook por API no la admite): se guarda lo de cada una.

    /**
     * Si sus clientes van a un local: solo entonces se usa la ubicación. Una
     * tienda en línea o un servicio a domicilio no tiene un lugar que etiquetar.
     * Nulo = no.
     */
    private Boolean ubicacionActiva;

    /** Cómo se llama el lugar, para enseñarlo. Nulo = sin ubicación. */
    @Column(length = 200)
    private String ubicacionNombre;

    /** El id del lugar en Instagram (sale del enlace de la ubicación). */
    @Column(length = 40)
    private String ubicacionInstagramId;

    /** El id del lugar en TikTok; TikTok lo pide junto con su nombre. */
    @Column(length = 80)
    private String ubicacionTiktokId;

    @Column(length = 200)
    private String ubicacionTiktokNombre;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
