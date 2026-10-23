package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.models.request.VisualIdentityRequest;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.BrandService;
import com.metricol.api.service.VisualIdentityService;
import jakarta.persistence.EntityManager;

/** Persistencia real en H2 aislado: no accede a cuentas, archivos ni servicios externos. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.springframework.boot=WARN"
})
class VisualIdentityPersistenceTest {
    @Autowired WorkspaceRepository workspaces;
    @Autowired MediaAssetRepository assets;
    @Autowired EntityManager entityManager;

    @Test void preferenciasConfirmadasSobrevivenARecargarYAGuardarMarca() {
        Workspace workspace = workspaces.saveAndFlush(Workspace.builder().name("Picale prueba visual").build());
        User user = User.builder().id(UUID.randomUUID()).workspace(workspace).build();
        var service = new VisualIdentityService(workspaces, assets);
        var saved = service.update(user, new VisualIdentityRequest(0, true, List.of("#0066FF"), "Editorial",
                List.of(), List.of(), List.of(), "Sin marcos", "Nombre y forma", "Pose y luz",
                VisualIdentity.LogoTreatment.INTEGRATED, VisualIdentity.LogoBackground.PRESERVE, false));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.get(user)).isEqualTo(saved);
        new BrandService(workspaces).guardar(user, new BrandRequest());
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(user)).isEqualTo(saved);
        assertThat(service.get(user).confirmedBy()).isEqualTo(user.getId());
    }
}
