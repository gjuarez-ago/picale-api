package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.service.CuentaSinLimites;
import com.metricol.api.service.brand.BrandAccess;

class AssistantAccessTest {

    private final CuentaSinLimites sinLimites = mock(CuentaSinLimites.class);
    private final BrandAccess marca = mock(BrandAccess.class);
    private final AssistantAccess access = new AssistantAccess(sinLimites, marca);

    @Test
    void exigeBanderaDelEspacioYCuentaSinLimites() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = Workspace.builder().id(workspaceId).assistantEnabled(true).build();
        User user = User.builder().workspace(workspace).build();

        when(sinLimites.deWorkspace(workspaceId)).thenReturn(true);
        assertThat(access.habilitado(user)).isTrue();

        workspace.setAssistantEnabled(false);
        assertThat(access.habilitado(user)).isFalse();
        assertThatThrownBy(() -> access.exigir(user))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("no está habilitado");
    }

    @Test
    void unaCuentaConCreditoLimitadoNoPuedeEntrarAunqueLaBanderaEsteActiva() {
        UUID workspaceId = UUID.randomUUID();
        User user = User.builder().workspace(
                Workspace.builder().id(workspaceId).assistantEnabled(true).build()).build();
        when(sinLimites.deWorkspace(workspaceId)).thenReturn(false);

        assertThat(access.habilitado(user)).isFalse();
    }
}
