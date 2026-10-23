package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.metricol.api.service.assistant.AssistantGenerationRequestStore;

/** H2 real aislado, sin proveedores. Prueba commits independientes y unicidad durable. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.org.hibernate.SQL=WARN", "logging.level.org.springframework.boot=WARN"
})
@Import(AssistantGenerationRequestStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AssistantGenerationRequestPersistenceTest {
    @Autowired AssistantGenerationRequestStore store;
    @Test void reservationCommitsBeforeWorkAndSurvivesARejectedDuplicateTransaction() {
        UUID workspace = UUID.randomUUID(), run = UUID.randomUUID();
        store.reserve(workspace, run, "a".repeat(64));
        assertThat(store.find(workspace, run)).isPresent();
        assertThatThrownBy(() -> store.reserve(workspace, run, "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        store.accepted(workspace, run, "job-original");
        assertThat(store.find(workspace, run)).get().extracting(AssistantGenerationRequest::getJobId).isEqualTo("job-original");
        assertThat(store.find(UUID.randomUUID(), run)).isEmpty();
    }
}
