package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.converter.BrandProfileConverter;
import com.metricol.api.repository.WorkspaceRepository;

import jakarta.persistence.EntityManager;

/** El perfil de marca sobrevive a la base de datos, y un JSON que no se entiende no tumba la lectura. */
@SpringBootTest
@Transactional
class BrandProfilePersistenceTest {

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("se guarda como JSON en el espacio y se lee de vuelta completo")
    void ida_y_vuelta() {
        Workspace w = workspaces.save(Workspace.builder().name("Tacos").brandProfile(new BrandProfile(
                "Tacos", "Familias", List.of("CERCANO", "DIVERTIDO"), "No hablar de precios", "+529991234567",
                "https://tacos.mx", "Calle 60")).build());
        em.flush();
        em.clear();

        BrandProfile leido = workspaces.findById(w.getId()).orElseThrow().getBrandProfile();

        assertThat(leido).isEqualTo(new BrandProfile("Tacos", "Familias", List.of("CERCANO", "DIVERTIDO"),
                "No hablar de precios", "+529991234567", "https://tacos.mx", "Calle 60"));
    }

    @Test
    @DisplayName("un espacio sin perfil queda con la columna en nulo")
    void sinPerfil() {
        Workspace w = workspaces.save(Workspace.builder().name("Sin marca").build());
        em.flush();
        em.clear();

        assertThat(workspaces.findById(w.getId()).orElseThrow().getBrandProfile()).isNull();
    }

    @Test
    @DisplayName("el conversor: vacío se guarda como nulo, y un JSON roto no tumba la lectura")
    void conversor() {
        BrandProfileConverter c = new BrandProfileConverter();

        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToDatabaseColumn(BrandProfile.VACIO)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
        assertThat(c.convertToEntityAttribute("  ")).isNull();
        assertThat(c.convertToEntityAttribute("{esto no es json")).isNull();
        // Un campo de una versión futura no debe impedir abrir el espacio.
        assertThat(c.convertToEntityAttribute("{\"queVende\":\"tacos\",\"campoNuevo\":1}")).isNull();
    }
}
