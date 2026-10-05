package com.metricol.api.models.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Permission;

/**
 * La web manda los permisos sueltos como lista vacía ([]) cuando no hay
 * ninguno. EnumSet.copyOf de un conjunto vacío lanza "Collection is empty", y
 * eso tumbaba la invitación (5 oct 2026).
 */
class PermisosVaciosTest {

    @Test
    @DisplayName("una invitación con permisos sueltos vacíos no truena")
    void invitacion() {
        InvitacionRequest.EspacioAsignado e = new InvitacionRequest.EspacioAsignado();
        e.setExtraPermissions(new HashSet<>());
        e.setDeniedPermissions(new HashSet<>());
        assertThat(e.extras()).isEmpty();
        assertThat(e.negados()).isEmpty();

        e.setExtraPermissions(Set.of(Permission.POST_PUBLISH));
        assertThat(e.extras()).containsExactly(Permission.POST_PUBLISH);
    }

    @Test
    @DisplayName("cambiar el acceso con permisos sueltos vacíos no truena")
    void acceso() {
        AccesoRequest a = new AccesoRequest();
        a.setExtraPermissions(new HashSet<>());
        a.setDeniedPermissions(new HashSet<>());
        assertThat(a.extras()).isEmpty();
        assertThat(a.negados()).isEmpty();
    }
}
