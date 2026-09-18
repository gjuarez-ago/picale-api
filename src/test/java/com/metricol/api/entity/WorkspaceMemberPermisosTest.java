package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;

/**
 * Los permisos efectivos de una persona en un espacio.
 *
 * <p>Sin base de datos ni Spring: es aritmética de conjuntos, y es el cálculo
 * del que depende que un VIEWER no publique en la cuenta de un cliente. Un
 * error aquí no se ve en pantalla —se ve cuando algo sale publicado.
 */
class WorkspaceMemberPermisosTest {

    @Test
    @DisplayName("El rol trae sus permisos: un editor publica, un lector no")
    void elRolTraeLoSuyo() {
        assertThat(miembro(Role.EDITOR).permisosEfectivos())
                .contains(Permission.POST_PUBLISH, Permission.POST_CREATE, Permission.AI_USE);

        assertThat(miembro(Role.VIEWER).permisosEfectivos()).isEmpty();
    }

    @Test
    @DisplayName("Un editor no administra las redes ni el equipo")
    void elEditorNoTocaLoDelicado() {
        assertThat(miembro(Role.EDITOR).permisosEfectivos())
                .doesNotContain(Permission.NETWORK_MANAGE, Permission.MEMBER_MANAGE);
    }

    @Test
    @DisplayName("Un permiso suelto se suma a los del rol")
    void losExtrasSeSuman() {
        WorkspaceMember miembro = miembro(Role.EDITOR);
        miembro.setExtraPermissions(EnumSet.of(Permission.NETWORK_MANAGE));

        assertThat(miembro.puede(Permission.NETWORK_MANAGE)).isTrue();
        assertThat(miembro.puede(Permission.POST_PUBLISH)).isTrue();
    }

    @Test
    @DisplayName("Negar gana: quitar un permiso lo quita aunque el rol lo traiga")
    void negarGanaAlRol() {
        WorkspaceMember miembro = miembro(Role.EDITOR);
        miembro.setDeniedPermissions(EnumSet.of(Permission.POST_PUBLISH));

        assertThat(miembro.puede(Permission.POST_PUBLISH)).isFalse();
        // El resto de su rol sigue en pie: se le quitó uno, no todos.
        assertThat(miembro.puede(Permission.POST_CREATE)).isTrue();
    }

    @Test
    @DisplayName("Negar gana también sobre un permiso dado a mano")
    void negarGanaAlExtra() {
        WorkspaceMember miembro = miembro(Role.EDITOR);
        miembro.setExtraPermissions(EnumSet.of(Permission.NETWORK_MANAGE));
        miembro.setDeniedPermissions(EnumSet.of(Permission.NETWORK_MANAGE));

        assertThat(miembro.puede(Permission.NETWORK_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("Un administrador del espacio lo puede todo")
    void elAdminDelEspacioLoPuedeTodo() {
        assertThat(miembro(Role.ADMIN).permisosEfectivos())
                .containsAll(EnumSet.allOf(Permission.class));
    }

    @Test
    @DisplayName("Sin rol se trata como lector: nada, nunca todo")
    void sinRolNoSeRegalaNada() {
        WorkspaceMember miembro = WorkspaceMember.builder().build();

        assertThat(miembro.permisosEfectivos()).isEmpty();
    }

    private static WorkspaceMember miembro(Role role) {
        return WorkspaceMember.builder().role(role).build();
    }
}
