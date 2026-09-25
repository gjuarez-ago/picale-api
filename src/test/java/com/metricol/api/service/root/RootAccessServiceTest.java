package com.metricol.api.service.root;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ForbiddenException;

/** La puerta de /root: solo pasa quien administra la plataforma, sea lo que sea en su organización. */
class RootAccessServiceTest {

    private final RootAccessService acceso = new RootAccessService();

    @Test
    @DisplayName("quien administra la plataforma pasa")
    void pasaLaRaiz() {
        User raiz = User.builder().id(UUID.randomUUID()).email("root@picale.test").role(Role.ADMIN)
                .platformAdmin(true).build();

        assertThatCode(() -> acceso.exigir(raiz)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un ADMIN de su workspace, sin la marca, recibe 403; y sin usuario también")
    void rechazaALosDemas() {
        User admin = User.builder().id(UUID.randomUUID()).email("admin@cliente.test").role(Role.ADMIN).build();

        assertThatThrownBy(() -> acceso.exigir(admin)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> acceso.exigir(null)).isInstanceOf(ForbiddenException.class);
    }
}
