package com.metricol.api.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cuántas publicaciones lleva hoy la plataforma ENTERA, sumando workspaces.
 *
 * <p>Es la pareja global de {@code DailyPublishUsage}. Aquella protege la
 * página de cada cliente; esta protege la llave de upload-post, que es una
 * sola para todos: si el plan del proveedor un día impone un tope, o si hay
 * que frenar el gasto, aquí está el contador. Mismo truco que la otra —sumar
 * uno solo si cabe, en la propia base— para que ocho workers no se pasen
 * entre todos.
 *
 * <p>Una fila por día, y el día es la llave: no hace falta más.
 */
@Entity
@Table(name = "global_publish_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalPublishUsage {

    @Id
    @Column(name = "usage_day")
    private LocalDate day;

    @Column(nullable = false)
    @Builder.Default
    private int used = 0;
}
