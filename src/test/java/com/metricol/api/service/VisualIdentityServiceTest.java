package com.metricol.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.util.*;
import org.junit.jupiter.api.*;
import com.metricol.api.entity.*;
import com.metricol.api.entity.converter.BrandProfileConverter;
import com.metricol.api.enums.*;
import com.metricol.api.models.request.*;
import com.metricol.api.repository.*;

class VisualIdentityServiceTest {
    WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    MediaAssetRepository assets = mock(MediaAssetRepository.class);
    VisualIdentityService service = new VisualIdentityService(workspaces, assets);
    Workspace workspace = Workspace.builder().id(UUID.randomUUID()).build();
    User user = User.builder().id(UUID.randomUUID()).workspace(workspace).build();
    @BeforeEach void setup() {
        when(workspaces.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(workspaces.findLockedById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(workspaces.save(any())).thenAnswer(i -> i.getArgument(0));
    }
    private VisualIdentityRequest request(int revision, boolean confirmed, List<UUID> references) {
        return new VisualIdentityRequest(revision, confirmed, List.of("#1a42bc"), "Editorial con fotos propias", references,
                null, null, "Sin marcos genéricos", "Nombre y forma del símbolo", "Posición y luz", VisualIdentity.LogoTreatment.INTEGRATED,
                VisualIdentity.LogoBackground.PRESERVE, false);
    }
    @Test void persistePreferenciasConfirmadasYSobreviveAlGuardadoDeMarcaAnterior() {
        var saved = service.update(user, request(0, true, List.of()));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.confirmedBy()).isEqualTo(user.getId());
        assertThat(saved.colors()).containsExactly("#1A42BC");
        BrandProfileConverter converter = new BrandProfileConverter();
        var restored = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(workspace.getBrandProfile()));
        assertThat(restored.visualIdentity()).isEqualTo(saved);
        new BrandService(workspaces).guardar(user, new BrandRequest());
        assertThat(service.get(user)).isEqualTo(saved);
    }
    @Test void rechazaPropuestasSinConfirmarYRevisionesObsoletas() {
        assertThatThrownBy(() -> service.update(user, request(0, false, List.of()))).isInstanceOf(IllegalArgumentException.class);
        service.update(user, request(0, true, List.of()));
        assertThatThrownBy(() -> service.update(user, request(0, true, List.of()))).hasMessageContaining("cambió");
    }
    @Test void rechazaReferenciasAjenas() {
        UUID id = UUID.randomUUID();
        when(assets.findById(id)).thenReturn(Optional.of(MediaAsset.builder().id(id).tenantId(UUID.randomUUID().toString())
                .type(MediaType.IMAGE).status(MediaAssetStatus.READY).build()));
        assertThatThrownBy(() -> service.update(user, request(0, true, List.of(id)))).hasMessageContaining("referencia");
        verify(workspaces, never()).save(any());
    }
    @Test void leeElPerfilHistoricoSinIdentidadVisual() {
        var old = new BrandProfileConverter().convertToEntityAttribute("{\"queVende\":\"Tacos\",\"publico\":\"Familias\",\"tono\":[]}");
        assertThat(old.queVende()).isEqualTo("Tacos");
        assertThat(old.visualIdentity()).isNull();
        workspace.setBrandProfile(old);
        assertThat(service.get(user)).isEqualTo(VisualIdentity.EMPTY);
    }
}
