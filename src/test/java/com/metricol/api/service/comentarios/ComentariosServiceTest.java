package com.metricol.api.service.comentarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.entity.Comentario;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.ComentarioRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.social.UploadPostClient;

/**
 * La bandeja de comentarios. Lo que se prueba aquí es lo que sostiene todo:
 * que un comentario entre UNA vez, que lo nuestro no sea un pendiente, y que
 * nada se dé por contestado si la red no lo aceptó.
 */
class ComentariosServiceTest {

    private ComentarioRepository repo;
    private UploadPostClient client;
    private ComentariosService servicio;
    private Workspace espacio;
    private User usuario;
    private List<Comentario> guardados;

    private static final UUID DESTINO = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        repo = mock(ComentarioRepository.class);
        client = mock(UploadPostClient.class);
        servicio = new ComentariosService(repo, client, null, null);

        espacio = Workspace.builder().name("Tacos El Güero").uploadPostProfile("tacos").build();
        espacio.setId(UUID.randomUUID());
        usuario = mock(User.class);
        when(usuario.getWorkspace()).thenReturn(espacio);
        when(usuario.getId()).thenReturn(UUID.randomUUID());

        guardados = new ArrayList<>();
        when(repo.cualesYaEstan(anyString(), any())).thenReturn(List.of());
        when(repo.saveAll(any())).thenAnswer(i -> {
            i.getArgument(0, Iterable.class).forEach(c -> guardados.add((Comentario) c));
            return List.of();
        });
        when(repo.save(any(Comentario.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ComentariosService.Destino destino() {
        return new ComentariosService.Destino(DESTINO, espacio.getId(), UUID.randomUUID(), Platform.INSTAGRAM,
                "post-1", "tacos", "tacos_el_guero");
    }

    private static Map<String, Object> pagina(Object... comentarios) {
        return Map.of("success", true, "comments", List.of(comentarios));
    }

    private Comentario mio(UUID id) {
        Comentario c = Comentario.builder()
                .workspaceId(espacio.getId())
                .postTargetId(DESTINO)
                .red(Platform.INSTAGRAM)
                .idEnLaRed("c-1")
                .postIdEnLaRed("post-1")
                .texto("¿Cuánto cuesta?")
                .build();
        c.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    // ------------------------------------------------------------- traer

    @Test
    @DisplayName("C-01: la primera vez entran todos")
    void primeraVez() {
        when(client.comentarios(eq("tacos"), eq("instagram"), eq("post-1"), anyInt(), isNull()))
                .thenReturn(pagina(
                        Map.of("id", "1", "username", "ana", "text", "hola"),
                        Map.of("id", "2", "username", "beto", "text", "¿precio?"),
                        Map.of("id", "3", "username", "caro", "text", "👏")));

        assertThat(servicio.traer(destino())).isEqualTo(3);
        assertThat(guardados).hasSize(3);
        assertThat(guardados).allMatch(Comentario::pendiente);
    }

    @Test
    @DisplayName("C-02 y C-03: los que ya están no entran otra vez")
    void noSeDuplica() {
        when(repo.cualesYaEstan(eq("INSTAGRAM"), any())).thenReturn(List.of("1", "2"));
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(pagina(
                        Map.of("id", "1", "username", "ana", "text", "hola"),
                        Map.of("id", "2", "username", "beto", "text", "¿precio?")));

        assertThat(servicio.traer(destino())).isZero();
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("C-03: si la red repite un id dentro de la misma página, se guarda uno")
    void repetidoEnLaMismaPagina() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(pagina(
                        Map.of("id", "1", "username", "ana", "text", "hola"),
                        Map.of("id", "1", "username", "ana", "text", "hola")));

        assertThat(servicio.traer(destino())).isEqualTo(1);
        assertThat(guardados).hasSize(1);
    }

    @Test
    @DisplayName("C-04: lo que escribimos nosotros se guarda, pero no cuenta como pendiente")
    void loNuestroNoEsPendiente() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(pagina(
                        Map.of("id", "1", "username", "tacos_el_guero", "text", "¡Gracias!"),
                        Map.of("id", "2", "username", "ana", "text", "rico")));

        assertThat(servicio.traer(destino())).isEqualTo(1);
        assertThat(guardados).hasSize(2);
        assertThat(guardados.get(0).pendiente()).isFalse();
        assertThat(guardados.get(1).pendiente()).isTrue();
    }

    @Test
    @DisplayName("C-12: se siguen los cursores hasta que la red dice que no hay más")
    void sigueLosCursores() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(Map.of("comments", List.of(Map.of("id", "1", "text", "a")),
                        "pagination", Map.of("has_next", true, "next_cursor", "p2")));
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), eq("p2")))
                .thenReturn(pagina(Map.of("id", "2", "text", "b")));

        assertThat(servicio.traer(destino())).isEqualTo(2);
    }

    @Test
    @DisplayName("C-12: una publicación viral no se lleva el cupo entero de la vuelta")
    void topeDePaginas() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(Map.of("comments", List.of(Map.of("id", UUID.randomUUID().toString(), "text", "a")),
                        "pagination", Map.of("has_next", true, "next_cursor", "sigue")));

        servicio.traer(destino());

        verify(client, org.mockito.Mockito.times(ComentariosService.MAX_PAGINAS))
                .comentarios(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("C-07: la red se queja y no se guarda nada, sin reventar")
    void redSeQueja() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(Map.of("success", false, "error", "token expired"));

        assertThat(servicio.traer(destino())).isZero();
        assertThat(guardados).isEmpty();
    }

    @Test
    @DisplayName("C-11: un texto larguísimo se recorta en vez de tumbar el guardado")
    void textoLarguisimo() {
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(pagina(Map.of("id", "1", "text", "a".repeat(9000),
                        "username", "x", "avatar_url", "h".repeat(5000))));

        servicio.traer(destino());

        assertThat(guardados.get(0).getTexto()).hasSize(Comentario.MAX_TEXTO);
        // Una foto que no cabe se pierde sola; el comentario es lo que importa.
        assertThat(guardados.get(0).getAutorAvatarUrl()).isNull();
    }

    // --------------------------------------------------------- contestar

    @Test
    @DisplayName("R-01: se manda a la red y queda atendido")
    void responder() {
        UUID id = UUID.randomUUID();
        mio(id);
        when(client.comentar(eq("tacos"), eq("instagram"), eq("post-1"), eq("c-1"), eq("Son $120")))
                .thenReturn(Map.of("success", true, "comment_id", "r-9"));

        Comentario c = servicio.responder(usuario, id, "  Son $120  ");

        assertThat(c.getRespuestaTexto()).isEqualTo("Son $120");
        assertThat(c.getRespuestaIdEnLaRed()).isEqualTo("r-9");
        assertThat(c.getAtendidoEn()).isNotNull();
        assertThat(c.getAtendidoPor()).isEqualTo(usuario.getId());
    }

    @Test
    @DisplayName("R-02: si la red la rechaza, sigue pendiente")
    void redRechaza() {
        UUID id = UUID.randomUUID();
        Comentario c = mio(id);
        when(client.comentar(anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(Map.of("success", false, "error", "rate limited"));

        assertThatThrownBy(() -> servicio.responder(usuario, id, "Son $120"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inténtalo otra vez");
        assertThat(c.getAtendidoEn()).isNull();
        assertThat(c.getRespondidoEn()).isNull();
    }

    @Test
    @DisplayName("R-03: una respuesta vacía no se manda")
    void respuestaVacia() {
        UUID id = UUID.randomUUID();
        mio(id);

        assertThatThrownBy(() -> servicio.responder(usuario, id, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).comentar(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-05 y R-06: no se contesta dos veces el mismo comentario")
    void noSeContestaDosVeces() {
        UUID id = UUID.randomUUID();
        Comentario c = mio(id);
        c.setRespondidoEn(java.time.LocalDateTime.now());

        assertThatThrownBy(() -> servicio.responder(usuario, id, "otra vez"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya lo contestaron");
        verify(client, never()).comentar(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-07: atender no manda nada a la red")
    void atenderNoHabla() {
        UUID id = UUID.randomUUID();
        mio(id);

        assertThat(servicio.atender(usuario, id).getAtendidoEn()).isNotNull();
        verify(client, never()).comentar(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-08: deshacer devuelve a pendientes, salvo si ya se mandó la respuesta")
    void deshacer() {
        UUID id = UUID.randomUUID();
        Comentario c = mio(id);
        servicio.atender(usuario, id);

        assertThat(servicio.devolverAPendientes(usuario, id).getAtendidoEn()).isNull();

        c.setRespondidoEn(java.time.LocalDateTime.now());
        assertThatThrownBy(() -> servicio.devolverAPendientes(usuario, id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R-12: en una red sin moderación no se intenta ocultar")
    void ocultarDondeNoSePuede() {
        UUID id = UUID.randomUUID();
        mio(id).setRed(Platform.LINKEDIN);

        assertThatThrownBy(() -> servicio.ocultar(usuario, id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LinkedIn");
        verify(client, never()).accionSobreComentario(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-11: ocultar lo saca de la bandeja además de la red")
    void ocultar() {
        UUID id = UUID.randomUUID();
        mio(id);
        when(client.accionSobreComentario(anyString(), anyString(), any(), anyString(), eq("hide")))
                .thenReturn(Map.of("success", true));

        Comentario c = servicio.ocultar(usuario, id);

        assertThat(c.getOcultoEn()).isNotNull();
        assertThat(c.pendiente()).isFalse();
    }

    @Test
    @DisplayName("P-01: si TikTok se queja de permisos, la cuenta queda marcada para reconectar")
    void marcaParaReconectar() {
        SocialAccountRepository cuentas = mock(SocialAccountRepository.class);
        servicio = new ComentariosService(repo, client, null, cuentas);
        ComentariosService.Destino tiktok = new ComentariosService.Destino(DESTINO, espacio.getId(),
                UUID.randomUUID(), Platform.TIKTOK, "post-1", "tacos", "tacos_el_guero");
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(Map.of("success", false, "error", "Insufficient scope for comments"));

        assertThat(servicio.traer(tiktok)).isZero();

        verify(cuentas).marcarComentariosBloqueados(eq(tiktok.socialAccountId()), any());
    }

    @Test
    @DisplayName("Una red caída NO manda a nadie a reconectar nada")
    void redCaidaNoEsReconectar() {
        SocialAccountRepository cuentas = mock(SocialAccountRepository.class);
        servicio = new ComentariosService(repo, client, null, cuentas);
        ComentariosService.Destino tiktok = new ComentariosService.Destino(DESTINO, espacio.getId(),
                UUID.randomUUID(), Platform.TIKTOK, "post-1", "tacos", "tacos_el_guero");
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(Map.of("success", false, "error", "Service temporarily unavailable"));

        servicio.traer(tiktok);

        verify(cuentas, never()).marcarComentariosBloqueados(any(), any());
    }

    @Test
    @DisplayName("En Instagram y Facebook no se marca nada: no hay que reconectar nada")
    void instagramNoPideReconectar() {
        SocialAccountRepository cuentas = mock(SocialAccountRepository.class);
        servicio = new ComentariosService(repo, client, null, cuentas);
        when(client.comentarios(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(Map.of("success", false, "error", "Insufficient scope"));

        servicio.traer(destino());

        verify(cuentas, never()).marcarComentariosBloqueados(any(), any());
    }

    // ------------------------------------------------- de quién es cada cosa

    @Test
    @DisplayName("S-03: un comentario de otro espacio no existe para mí")
    void deOtroEspacio() {
        UUID id = UUID.randomUUID();
        Comentario ajeno = Comentario.builder().workspaceId(UUID.randomUUID()).red(Platform.INSTAGRAM)
                .idEnLaRed("x").build();
        ajeno.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(ajeno));

        assertThatThrownBy(() -> servicio.responder(usuario, id, "hola"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(client, never()).comentar(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S-01: marcar leídos solo toca los del propio espacio")
    void leidosSoloLosMios() {
        UUID mio = UUID.randomUUID();
        UUID ajeno = UUID.randomUUID();
        Comentario a = Comentario.builder().workspaceId(espacio.getId()).red(Platform.INSTAGRAM).idEnLaRed("1")
                .build();
        a.setId(mio);
        Comentario b = Comentario.builder().workspaceId(UUID.randomUUID()).red(Platform.INSTAGRAM).idEnLaRed("2")
                .build();
        b.setId(ajeno);
        when(repo.findAllById(any())).thenReturn(List.of(a, b));
        when(repo.marcarLeidos(any(), any())).thenReturn(1);

        servicio.marcarLeidos(usuario, List.of(mio, ajeno));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).marcarLeidos(captor.capture(), any());
        assertThat(captor.getValue()).containsExactly(mio);
    }
}
