package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una IA conectada a la cuenta de una persona a través del servidor MCP:
 * qué cliente (Claude, ChatGPT…), desde cuándo, qué fue lo último que hizo y
 * si la persona ya la desconectó.
 *
 * <p>Es lo que hace revocable una conexión aunque el JWT siga vivo: el MCP
 * manda el id en cada llamada ({@code X-Picale-Conexion}) y
 * {@code ConexionIaFilter} rechaza lo que esté revocado o vencido.
 *
 * <p>Pertenece a la persona, no al espacio: la misma conexión sirve para
 * todas las cuentas que la persona pueda ver desde el MCP.
 */
@Entity
@Table(name = "conexiones_ia", indexes = {
        @Index(name = "ix_conexiones_ia_user", columnList = "user_id, creada_en") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConexionIa {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** La persona que entró desde el asistente. */
    @Column(nullable = false)
    private UUID userId;

    @Column(length = 160)
    private String userEmail;

    /** Cómo se presentó el cliente de IA: "Claude", "ChatGPT", "Claude Code"… */
    @Column(nullable = false, length = 80)
    private String cliente;

    /** El client_id OAuth con el que se registró (una URL en el caso de Claude). */
    @Column(length = 300)
    private String clienteId;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime creadaEn = LocalDateTime.now();

    /** Hasta cuándo sirve el JWT con el que entró; después muere sola. */
    private LocalDateTime expiraEn;

    private LocalDateTime ultimaActividadEn;

    /** Lo último que hizo, en palabras: "Consultó publicaciones", "Creó un borrador". */
    @Column(length = 120)
    private String ultimaAccion;

    /** Cuándo la desconectó la persona. Nulo = sigue activa. */
    private LocalDateTime revocadaEn;

    public boolean activa(LocalDateTime ahora) {
        return revocadaEn == null && (expiraEn == null || expiraEn.isAfter(ahora));
    }
}
