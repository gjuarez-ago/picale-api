package com.metricol.api.service.agente.foto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.agente.PresupuestoDelAsistente;
import com.metricol.api.service.agente.TrabajoDeFondo;
import com.metricol.api.service.avisos.AvisosPush;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.campaign.LogoSobreFoto;

/** "Mejorarla con IA": solo si la piden, con sus créditos, y se devuelven si no sale. */
class MejoraBajoDemandaTest {

    private PostRepository posts;
    private MejoraDeFoto mejora;
    private LogoSobreFoto logo;
    private CreditService creditos;
    private PresupuestoDelAsistente presupuesto;
    private MejoraBajoDemanda servicio;
    private Post post;
    private final UUID ws = UUID.randomUUID();

    @BeforeEach
    void preparar() throws Exception {
        posts = mock(PostRepository.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        mejora = mock(MejoraDeFoto.class);
        logo = mock(LogoSobreFoto.class);
        creditos = mock(CreditService.class);
        presupuesto = mock(PresupuestoDelAsistente.class);
        TrabajoDeFondo fondo = mock(TrabajoDeFondo.class);
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(fondo).enEspacio(any(), any());
        servicio = new MejoraBajoDemanda(posts, assets, workspaces, mejora, logo, creditos, presupuesto,
                mock(AvisosPush.class), fondo);

        DirectorDeFoto.Direccion d = new DirectorDeFoto(null, new com.metricol.api.config.OpenAiProperties())
                .interpretar("""
                        {"mejorar": true, "deficiencias": ["sombras oscuras"],
                         "encargo": "Lift the shadows on the stone floor about one stop and neutralize the cast.",
                         "quitar": ["the red tape measure"], "acabado": {"estilo": "FRANJA", "rotulo": "Obra"}}""");
        post = Post.builder().status(PostStatus.DRAFT).propuestaAgente(true).agenteFotoUrl("https://cdn/original.jpg")
                .agenteMotivo("Va con tu marca.").agenteMejoraSugerida("sombras oscuras")
                .agenteDireccion(MejoraBajoDemanda.guardar(d, true, false)).build();
        post.setId(UUID.randomUUID());
        post.setMediaUrls(new java.util.ArrayList<>(List.of("https://cdn/acabada-original.jpg")));
        when(posts.findById(post.getId())).thenReturn(Optional.of(post));
        MediaAsset original = MediaAsset.builder().url("https://cdn/original.jpg").build();
        when(assets.findByUrlIn(any())).thenReturn(List.of(original));
        Workspace w = Workspace.builder().name("CMRG").logoUrl("https://cdn/logo.png").build();
        when(workspaces.findById(ws)).thenReturn(Optional.of(w));
        when(presupuesto.alcanza(ws)).thenReturn(true);
    }

    @Test
    @DisplayName("mejora con sus créditos, cambia la imagen de la propuesta y ya no la vuelve a ofrecer")
    void mejora() {
        MediaAsset mejorada = MediaAsset.builder().url("https://cdn/mejorada.jpg").build();
        when(mejora.mejorar(any(), eq(ws), any())).thenReturn(new MejoraDeFoto.Mejorada(mejorada, null));
        when(logo.acabar(eq(mejorada), anyString(), eq(ws), any(), anyBoolean())).thenReturn("https://cdn/final.jpg");

        servicio.pedir(post.getId(), ws);

        verify(creditos).consumirGeneracion(eq(ws), startsWith("mejora-"));
        verify(creditos, never()).devolverGeneracion(any(), any());
        assertThat(post.getMediaUrls()).containsExactly("https://cdn/final.jpg");
        assertThat(post.getAgenteMejoraSugerida()).isNull();
        assertThat(post.getAgenteMejorando()).isFalse();
        assertThat(post.getAgenteMotivo()).contains("La mejoré con IA como pediste");
    }

    @Test
    @DisplayName("si la mejora no gana, se queda la foto que estaba y se devuelven los créditos")
    void noGana() {
        when(mejora.mejorar(any(), eq(ws), any()))
                .thenReturn(new MejoraDeFoto.Mejorada(null, "Comparé la mejora con tu foto y no ganaba nada.", true));
        servicio.pedir(post.getId(), ws);
        verify(creditos).devolverGeneracion(eq(ws), startsWith("mejora-"));
        assertThat(post.getMediaUrls()).containsExactly("https://cdn/acabada-original.jpg");
        assertThat(post.getAgenteMotivo()).contains("No se te cobró");
    }

    @Test
    @DisplayName("sin presupuesto del mes, no cobra ni intenta")
    void sinPresupuesto() {
        when(presupuesto.alcanza(ws)).thenReturn(false);
        assertThatThrownBy(() -> servicio.pedir(post.getId(), ws)).hasMessageContaining("presupuesto");
        verify(creditos, never()).consumirGeneracion(any(), any());
    }
}
