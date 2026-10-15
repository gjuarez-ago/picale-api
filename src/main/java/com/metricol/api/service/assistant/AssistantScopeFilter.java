package com.metricol.api.service.assistant;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.User;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.security.JwtService;
import com.metricol.api.service.bitacora.BitacoraIa;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Impide que una delegacion del asistente salga del workspace, hilo o run firmados. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class AssistantScopeFilter extends OncePerRequestFilter {

    public static final String WORKSPACE = "X-Picale-Workspace";
    public static final String THREAD = "X-Picale-Assistant-Thread";
    public static final String RUN = "X-Picale-Assistant-Run";

    private final JwtService jwt;
    private final ObjectMapper json;

    public AssistantScopeFilter(JwtService jwt, ObjectMapper json) {
        this.jwt = jwt;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"assistant".equalsIgnoreCase(request.getHeader(BitacoraIa.CABECERA_ORIGEN));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        User user = currentUser();
        String token = bearer(request.getHeader("Authorization"));
        String workspace = request.getHeader(WORKSPACE);
        String thread = request.getHeader(THREAD);
        String run = request.getHeader(RUN);
        if (user == null || user.getWorkspace() == null || token == null
                || !validUuid(workspace) || !validUuid(thread) || !validUuid(run)
                || !user.getWorkspace().getId().toString().equals(workspace)) {
            reject(response);
            return;
        }
        try {
            if (!jwt.isAssistantDelegation(token, workspace, thread, run)) {
                reject(response);
                return;
            }
        } catch (JwtException | IllegalArgumentException e) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error("ASSISTANT_SCOPE_INVALID",
                "La autorizacion del asistente vencio o no corresponde a esta conversacion.")));
    }

    private static User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User user ? user : null;
    }

    private static String bearer(String value) {
        return value != null && value.startsWith("Bearer ") ? value.substring(7).strip() : null;
    }

    private static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
