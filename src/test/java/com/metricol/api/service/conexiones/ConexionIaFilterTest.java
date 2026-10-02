package com.metricol.api.service.conexiones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.ConexionIa;
import com.metricol.api.entity.User;
import com.metricol.api.repository.ConexionIaRepository;
import com.metricol.api.service.bitacora.BitacoraIa;

class ConexionIaFilterTest {

    private final ConexionIaRepository repository = mock(ConexionIaRepository.class);
    // findAndRegisterModules: el sobre lleva un OffsetDateTime y Spring registra jsr310 solo en su propio mapper.
    private final ConexionIaFilter filtro = new ConexionIaFilter(new ConexionesIa(repository), new ObjectMapper().findAndRegisterModules());
    private final User ana = User.builder().id(UUID.randomUUID()).email("ana@negocio.mx").build();
    private final UUID viva = UUID.randomUUID();
    private final UUID revocada = UUID.randomUUID();

    @BeforeEach
    void conSesion() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ana, null, java.util.List.of()));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.findByIdAndUserId(viva, ana.getId())).thenReturn(Optional.of(
                ConexionIa.builder().id(viva).userId(ana.getId()).cliente("Claude").build()));
        when(repository.findByIdAndUserId(revocada, ana.getId())).thenReturn(Optional.of(
                ConexionIa.builder().id(revocada).userId(ana.getId()).cliente("Claude").revocadaEn(LocalDateTime.now()).build()));
    }

    @AfterEach
    void sinSesion() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest desdeElMcp(String metodo, String ruta, String conexion) {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, ruta);
        req.setRequestURI(ruta);
        req.addHeader(BitacoraIa.CABECERA_ORIGEN, "mcp");
        if (conexion != null) {
            req.addHeader(ConexionesIa.CABECERA_CONEXION, conexion);
        }
        return req;
    }

    @Test
    void unaConexionVivaPasaYDejaRastro() throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filtro.doFilter(desdeElMcp("GET", "/api/v1/posts", viva.toString()), resp, cadena);

        assertThat(cadena.getRequest()).isNotNull();
        assertThat(resp.getStatus()).isEqualTo(200);
        verify(repository).save(any());
    }

    @Test
    void sinIdDeConexionEs401Requerida() throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filtro.doFilter(desdeElMcp("GET", "/api/v1/posts", null), resp, cadena);

        assertThat(cadena.getRequest()).isNull();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains(ConexionIaFilter.CODIGO_REQUERIDA).contains("\"ok\":false");
    }

    @Test
    void unaRevocadaOAjenaEs401Revocada() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filtro.doFilter(desdeElMcp("POST", "/api/v1/posts", revocada.toString()), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains(ConexionIaFilter.CODIGO_REVOCADA).contains("desconectada desde Pícale");

        MockHttpServletResponse ajena = new MockHttpServletResponse();
        filtro.doFilter(desdeElMcp("GET", "/api/v1/posts", UUID.randomUUID().toString()), ajena, new MockFilterChain());
        assertThat(ajena.getStatus()).isEqualTo(401);

        MockHttpServletResponse basura = new MockHttpServletResponse();
        filtro.doFilter(desdeElMcp("GET", "/api/v1/posts", "no-es-uuid"), basura, new MockFilterChain());
        assertThat(basura.getStatus()).isEqualTo(401);
        verify(repository, never()).save(any());
    }

    @Test
    void elAltaDeLaConexionYLaWebNoPasanPorAqui() {
        assertThat(filtro.shouldNotFilter(desdeElMcp("POST", "/api/v1/conexiones-ia", null))).isTrue();
        assertThat(filtro.shouldNotFilter(desdeElMcp("GET", "/api/v1/conexiones-ia", null))).isFalse();
        assertThat(filtro.shouldNotFilter(desdeElMcp("POST", "/api/v1/auth/login", null))).isTrue();
        MockHttpServletRequest web = new MockHttpServletRequest("GET", "/api/v1/posts");
        web.setRequestURI("/api/v1/posts");
        assertThat(filtro.shouldNotFilter(web)).isTrue();
    }
}
