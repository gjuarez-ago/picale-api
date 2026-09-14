package com.metricol.api.entity;

import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.enums.ObjetivoRedes;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    @CreationTimestamp
    private LocalDateTime createdAt;
}
