package com.metricol.api.service.conexiones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.ConexionIa;
import com.metricol.api.entity.User;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.ConexionIaRepository;

class ConexionesIaTest {

    private final ConexionIaRepository repository = mock(ConexionIaRepository.class);
    private final ConexionesIa servicio = new ConexionesIa(repository);
    private final User ana = User.builder().id(UUID.randomUUID()).email("ana@negocio.mx").build();

    @BeforeEach
    void guardarDevuelveLoMismo() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void crearGuardaQuienYQueClienteYHastaCuando() {
        long dentroDeUnaHora = System.currentTimeMillis() / 1000 + 3600;

        ConexionIa c = servicio.crear(ana, "  Claude  ", "https://claude.ai/oauth/mcp-oauth-client-metadata", dentroDeUnaHora);

        assertThat(c.getUserId()).isEqualTo(ana.getId());
        assertThat(c.getUserEmail()).isEqualTo("ana@negocio.mx");
        assertThat(c.getCliente()).isEqualTo("Claude");
        assertThat(c.getExpiraEn()).isAfter(LocalDateTime.now().plusMinutes(50));
        assertThat(c.activa(LocalDateTime.now())).isTrue();
        assertThat(servicio.crear(ana, "", null, null).getCliente()).isEqualTo("Asistente de IA");
        assertThat(c.getAlcance()).isEqualTo("write");
        assertThat(servicio.crear(ana, "Claude", null, null, "read").getAlcance()).isEqualTo("read");
        assertThat(servicio.crear(ana, "Claude", null, null, "cualquier-cosa").getAlcance()).isEqualTo("write");
    }

    @Test
    void revocarEsDeLaPersonaYRepetirloNoCambiaNada() {
        UUID id = UUID.randomUUID();
        ConexionIa c = ConexionIa.builder().id(id).userId(ana.getId()).cliente("Claude").build();
        when(repository.findByIdAndUserId(id, ana.getId())).thenReturn(Optional.of(c));

        ConexionIa revocada = servicio.revocar(ana, id);
        LocalDateTime primera = revocada.getRevocadaEn();
        servicio.revocar(ana, id);

        assertThat(primera).isNotNull();
        assertThat(revocada.getRevocadaEn()).isEqualTo(primera);
        assertThat(revocada.activa(LocalDateTime.now())).isFalse();
        verify(repository, times(1)).save(any());

        User otro = User.builder().id(UUID.randomUUID()).email("otro@x.mx").build();
        when(repository.findByIdAndUserId(id, otro.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.revocar(otro, id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void soloEstaActivaSiNoSeRevocoNiVencio() {
        UUID viva = UUID.randomUUID();
        UUID vencida = UUID.randomUUID();
        UUID revocada = UUID.randomUUID();
        when(repository.findByIdAndUserId(viva, ana.getId())).thenReturn(Optional.of(
                ConexionIa.builder().id(viva).userId(ana.getId()).cliente("Claude").expiraEn(LocalDateTime.now().plusDays(1)).build()));
        when(repository.findByIdAndUserId(vencida, ana.getId())).thenReturn(Optional.of(
                ConexionIa.builder().id(vencida).userId(ana.getId()).cliente("Claude").expiraEn(LocalDateTime.now().minusMinutes(1)).build()));
        when(repository.findByIdAndUserId(revocada, ana.getId())).thenReturn(Optional.of(
                ConexionIa.builder().id(revocada).userId(ana.getId()).cliente("Claude").revocadaEn(LocalDateTime.now()).build()));

        assertThat(servicio.activa(viva, ana.getId())).isPresent();
        assertThat(servicio.activa(vencida, ana.getId())).isEmpty();
        assertThat(servicio.activa(revocada, ana.getId())).isEmpty();
        assertThat(servicio.activa(viva, UUID.randomUUID())).isEmpty();
    }

    @Test
    void laActividadSeAnotaUnaVezPorMinutoSalvoQueCambieLaAccion() {
        ConexionIa c = ConexionIa.builder().id(UUID.randomUUID()).userId(ana.getId()).cliente("Claude").build();

        servicio.registrarActividad(c, "GET", "/api/v1/posts");
        servicio.registrarActividad(c, "GET", "/api/v1/workspace");
        assertThat(c.getUltimaAccion()).isEqualTo("Consultó publicaciones");
        verify(repository, times(1)).save(any());

        servicio.registrarActividad(c, "POST", "/api/v1/posts");
        assertThat(c.getUltimaAccion()).isNotEqualTo("Consultó publicaciones");
        verify(repository, times(2)).save(any());
    }

    @Test
    void lasEtiquetasHablanEnPalabras() {
        assertThat(ConexionesIa.etiqueta("GET", "/api/v1/posts?status=DRAFT")).isEqualTo("Consultó publicaciones");
        assertThat(ConexionesIa.etiqueta("GET", "/api/v1/agente/bandeja")).isEqualTo("Revisó el agente");
        assertThat(ConexionesIa.etiqueta("GET", "/api/v1/loquesea")).isEqualTo("Consultó loquesea");
        assertThat(ConexionesIa.etiqueta("POST", "/api/v1/posts")).isNotBlank();
    }
}
