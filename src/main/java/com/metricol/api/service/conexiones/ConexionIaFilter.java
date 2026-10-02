package com.metricol.api.service.conexiones;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.ConexionIa;
import com.metricol.api.entity.User;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.bitacora.BitacoraIa;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * La puerta por la que pasa toda llamada que llega desde el servidor MCP:
 * tiene que traer el id de una conexión viva de la misma persona. Así, lo que
 * la persona desconecta desde su perfil deja de funcionar al instante aunque
 * el JWT del asistente siga siendo válido.
 *
 * <p>Corre después de la seguridad (ya hay principal) y antes de la bitácora.
 * Solo mira peticiones con {@code X-Picale-Origen}; la web y la app no se
 * enteran. El alta de la conexión ({@code POST /api/v1/conexiones-ia}) queda
 * fuera porque en ese momento todavía no hay id.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ConexionIaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConexionIaFilter.class);

    public static final String CODIGO_REQUERIDA = "CONEXION_REQUERIDA";
    public static final String CODIGO_REVOCADA = "CONEXION_REVOCADA";

    private final ConexionesIa conexiones;
    private final ObjectMapper json;

    public ConexionIaFilter(ConexionesIa conexiones, ObjectMapper json) {
        this.conexiones = conexiones;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String origen = request.getHeader(BitacoraIa.CABECERA_ORIGEN);
        if (origen == null || origen.isBlank()) {
            return true;
        }
        String ruta = request.getRequestURI();
        if (ruta == null || !ruta.startsWith("/api/v1/") || ruta.startsWith("/api/v1/auth/")) {
            return true;
        }
        return "POST".equals(request.getMethod()) && "/api/v1/conexiones-ia".equals(ruta);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        User persona = usuarioActual();
        if (persona == null) {
            // Sin sesión no hay nada que comprobar: la seguridad ya contestó o contestará.
            chain.doFilter(request, response);
            return;
        }
        String cabecera = request.getHeader(ConexionesIa.CABECERA_CONEXION);
        if (cabecera == null || cabecera.isBlank()) {
            rechazar(response, CODIGO_REQUERIDA,
                    "Esta conexión de IA no está registrada en Pícale. Vuelve a conectar desde tu asistente.");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(cabecera.strip());
        } catch (IllegalArgumentException e) {
            rechazar(response, CODIGO_REVOCADA, "La conexión de IA no es válida. Vuelve a conectar desde tu asistente.");
            return;
        }
        ConexionIa conexion = conexiones.activa(id, persona.getId()).orElse(null);
        if (conexion == null) {
            rechazar(response, CODIGO_REVOCADA,
                    "Esta conexión fue desconectada desde Pícale. Si quieres seguir, vuelve a conectar desde tu asistente.");
            return;
        }
        try {
            conexiones.registrarActividad(conexion, request.getMethod(), request.getRequestURI());
        } catch (RuntimeException e) {
            log.warn("No se pudo anotar la actividad de la conexión {}: {}", id, e.getMessage());
        }
        chain.doFilter(request, response);
    }

    private void rechazar(HttpServletResponse response, String codigo, String mensaje) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(codigo, mensaje)));
    }

    private static User usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User u ? u : null;
    }
}
