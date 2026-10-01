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

    private final RootAccessService acceso = new RootAccessService(
            new AdministradoresService(null, "root@picale.test", true));

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

    @Test
    @DisplayName("eliminar es solo de la raíz: otro admin de plataforma recibe 403")
    void eliminarSoloLaRaiz() {
        User raiz = User.builder().id(UUID.randomUUID()).email("Root@Picale.test").role(Role.ADMIN)
                .platformAdmin(true).build();
        User otroAdmin = User.builder().id(UUID.randomUUID()).email("ops@picale.test").role(Role.ADMIN)
                .platformAdmin(true).build();

        assertThatCode(() -> acceso.exigirRaiz(raiz)).doesNotThrowAnyException();
        assertThatThrownBy(() -> acceso.exigirRaiz(otroAdmin)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("raíz");
        assertThatThrownBy(() -> acceso.exigirRaiz(null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("sin cuenta raíz configurada nadie puede eliminar")
    void sinRaizNadieElimina() {
        RootAccessService sinRaiz = new RootAccessService(new AdministradoresService(null, "root@picale.test", false));
        User admin = User.builder().id(UUID.randomUUID()).email("root@picale.test").role(Role.ADMIN)
                .platformAdmin(true).build();
        assertThatThrownBy(() -> sinRaiz.exigirRaiz(admin)).isInstanceOf(ForbiddenException.class);
    }
}
