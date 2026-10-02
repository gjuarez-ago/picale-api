package com.metricol.api.service.bitacora;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Anota en la bitácora las peticiones de escritura que llegan con la cabecera
 * {@code X-Picale-Origen} (las manda el servidor MCP en nombre de una IA).
 *
 * <p>Va con la prioridad más baja para correr DESPUÉS de Spring Security: así
 * ya hay usuario del que colgar la acción. Solo envuelve las peticiones que
 * traen la cabecera, y solo guarda hasta 64 KB del cuerpo —lo que ocupa un
 * JSON— para que una subida de 25 MB no se copie entera en memoria por una
 * anotación.
 *
 * <p>Si anotar falla, la petición ya se atendió y la respuesta sale igual: la
 * bitácora nunca puede ser la razón de que una publicación no se guarde.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BitacoraIaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BitacoraIaFilter.class);
    private static final int TOPE_CUERPO = 64 * 1024;

    private final BitacoraIa bitacora;

    public BitacoraIaFilter(BitacoraIa bitacora) {
        this.bitacora = bitacora;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String origen = request.getHeader(BitacoraIa.CABECERA_ORIGEN);
        if (origen == null || origen.isBlank()) {
            return true;
        }
        String metodo = request.getMethod();
        if ("GET".equals(metodo) || "HEAD".equals(metodo) || "OPTIONS".equals(metodo)) {
            return true;
        }
        String ruta = request.getRequestURI();
        return ruta == null || !ruta.startsWith("/api/v1/") || ruta.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper peticion = new ContentCachingRequestWrapper(request, TOPE_CUERPO);
        ContentCachingResponseWrapper respuesta = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(peticion, respuesta);
            try {
                bitacora.anotar(peticion, respuesta);
            } catch (RuntimeException e) {
                log.warn("No se pudo anotar en la bitácora de IA {} {}: {}", request.getMethod(),
                        request.getRequestURI(), e.getMessage());
            }
        } finally {
            respuesta.copyBodyToResponse();
        }
    }
}
