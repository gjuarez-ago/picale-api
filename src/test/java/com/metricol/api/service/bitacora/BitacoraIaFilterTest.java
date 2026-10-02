package com.metricol.api.service.bitacora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.AccionIa;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.repository.AccionIaRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

class BitacoraIaFilterTest {

    private final AccionIaRepository repository = mock(AccionIaRepository.class);
    private final BitacoraIa bitacora = new BitacoraIa(repository, new ObjectMapper());
    private final BitacoraIaFilter filtro = new BitacoraIaFilter(bitacora);
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void conSesion() {
        Workspace ws = Workspace.builder().id(workspaceId).name("Tacos").build();
        User usuario = User.builder().id(UUID.randomUUID()).email("ana@negocio.mx").workspace(ws).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, java.util.List.of()));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void sinSesion() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest peticion(String metodo, String ruta, String cuerpo) {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, ruta);
        req.setRequestURI(ruta);
        req.addHeader(BitacoraIa.CABECERA_ORIGEN, "mcp");
        req.addHeader(BitacoraIa.CABECERA_CLIENTE, "Claude");
        if (cuerpo != null) {
            req.setContentType("application/json");
            req.setContent(cuerpo.getBytes(StandardCharsets.UTF_8));
        }
        return req;
    }

    /** Una cadena que contesta como lo haría el controlador. */
    private static MockFilterChain contestando(int estado, String json) {
        return new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse resp)
                    throws java.io.IOException {
                // Leer el cuerpo como haría Spring, para que quede en la caché del envoltorio.
                req.getInputStream().readAllBytes();
                resp.setStatus(estado);
                resp.setContentType("application/json");
                resp.getWriter().write(json);
            }
        });
    }

    @Test
    void anotaUnaEscrituraExitosaConSuClienteYSuDetalle() throws ServletException, java.io.IOException {
        MockHttpServletRequest req = peticion("POST", "/api/v1/posts", "{\"caption\":\"Hola\",\"scheduledAt\":\"2026-10-03T11:00:00\"}");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filtro.doFilter(req, resp, contestando(200,
                "{\"ok\":true,\"result\":{\"id\":\"99999999-9999-9999-9999-999999999999\",\"caption\":\"Hola\",\"status\":\"SCHEDULED\"}}"));

        ArgumentCaptor<AccionIa> guardada = ArgumentCaptor.forClass(AccionIa.class);
        verify(repository).save(guardada.capture());
        AccionIa a = guardada.getValue();
        assertThat(a.getAccion()).isEqualTo("PROGRAMAR_PUBLICACION");
        assertThat(a.getCliente()).isEqualTo("Claude");
        assertThat(a.getOrigen()).isEqualTo("mcp");
        assertThat(a.getUserEmail()).isEqualTo("ana@negocio.mx");
        assertThat(a.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(a.getEntidadId()).isEqualTo("99999999-9999-9999-9999-999999999999");
        assertThat(a.getDetalle()).contains("texto: Hola").contains("estado: SCHEDULED");
        assertThat(a.getEstado()).isEqualTo(200);
        // La respuesta llega entera al cliente aunque se haya leído para anotar.
        assertThat(resp.getContentAsString()).contains("\"ok\":true");
    }

    @Test
    void noAnotaSinCabeceraNiLecturasNiFallos() throws ServletException, java.io.IOException {
        MockHttpServletRequest sinCabecera = new MockHttpServletRequest("POST", "/api/v1/posts");
        sinCabecera.setRequestURI("/api/v1/posts");
        filtro.doFilter(sinCabecera, new MockHttpServletResponse(), contestando(200, "{\"ok\":true}"));

        MockHttpServletRequest lectura = peticion("GET", "/api/v1/posts", null);
        filtro.doFilter(lectura, new MockHttpServletResponse(), contestando(200, "{\"ok\":true}"));

        MockHttpServletRequest fallida = peticion("POST", "/api/v1/posts", "{}");
        filtro.doFilter(fallida, new MockHttpServletResponse(), contestando(409, "{\"ok\":false}"));

        MockHttpServletRequest entrada = peticion("POST", "/api/v1/auth/login", "{}");
        filtro.doFilter(entrada, new MockHttpServletResponse(), contestando(200, "{\"ok\":true}"));

        verify(repository, never()).save(any());
    }

    @Test
    void unFalloAlAnotarNoRompeLaRespuesta() throws ServletException, java.io.IOException {
        when(repository.save(any())).thenThrow(new IllegalStateException("base caída"));
        MockHttpServletRequest req = peticion("POST", "/api/v1/posts", "{}");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filtro.doFilter(req, resp, contestando(200, "{\"ok\":true,\"result\":{\"id\":\"x\"}}"));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).contains("\"ok\":true");
    }
}
