package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.assistant.AssistantGenerationRegistry;
import com.metricol.api.service.assistant.AssistantScopeFilter;
import com.metricol.api.service.bitacora.BitacoraIa;
import com.metricol.api.service.campaign.ContenidoJobs;

class ContenidoControllerGenerationTest {
    final ContenidoJobs jobs = mock(ContenidoJobs.class);
    final AssistantGenerationRegistry registry = mock(AssistantGenerationRegistry.class);
    final ContenidoController controller = new ContenidoController(jobs, mock(PermissionService.class), registry);
    final User user = User.builder().id(UUID.randomUUID()).workspace(Workspace.builder().id(UUID.randomUUID()).build()).build();
    final CampaignImageRequest request = new CampaignImageRequest(1, null, List.of(), null, "Prueba", null, List.of(), null, null, false, List.of("INSTAGRAM"));
    @Test void assistantDelegationUsesDurableRunKeyInsteadOfDirectGeneration() {
        UUID run = UUID.randomUUID();
        var http = new MockHttpServletRequest();
        http.addHeader(BitacoraIa.CABECERA_ORIGEN, "assistant"); http.addHeader(AssistantScopeFilter.RUN, run.toString());
        when(registry.start(eq(user.getWorkspace().getId()), eq(run), eq(request), any())).thenReturn("job-original");
        assertThat(controller.generar(user, request, http).getBody().getResult()).containsEntry("jobId", "job-original");
        verify(registry).start(eq(user.getWorkspace().getId()), eq(run), eq(request), any());
        verifyNoInteractions(jobs);
    }
    @Test void nonAssistantAndInternalCallersKeepTheirExistingFlow() {
        when(jobs.iniciar(user, request)).thenReturn("job-mobile");
        assertThat(controller.generar(user, request).getBody().getResult()).containsEntry("jobId", "job-mobile");
        verifyNoInteractions(registry);
        verify(jobs).iniciar(user, request);
    }
}
