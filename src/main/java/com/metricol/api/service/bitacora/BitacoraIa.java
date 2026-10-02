package com.metricol.api.service.bitacora;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.AccionIa;
import com.metricol.api.entity.User;
import com.metricol.api.repository.AccionIaRepository;

/**
 * La bitácora de lo que hacen las IA conectadas: anotar cada acción y
 * leerlas por cuenta.
 */
@Service
public class BitacoraIa {

    private static final Logger log = LoggerFactory.getLogger(BitacoraIa.class);

    public static final String CABECERA_ORIGEN = "X-Picale-Origen";
    public static final String CABECERA_CLIENTE = "X-Picale-Cliente";

    private final AccionIaRepository repository;
    private final ObjectMapper json;

    public BitacoraIa(AccionIaRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * Anota una petición de escritura ya contestada. Solo las que salieron
     * bien (2xx) y vienen de alguien con sesión: lo que se rechazó no cambió
     * nada, y sin sesión no hay a quién atribuírselo.
     */
    @Transactional
    public void anotar(ContentCachingRequestWrapper peticion, ContentCachingResponseWrapper respuesta) {
        int estado = respuesta.getStatus();
        if (estado / 100 != 2) {
            return;
        }
        User usuario = usuarioActual();
        if (usuario == null || usuario.getWorkspace() == null) {
            return;
        }
        String origen = limpio(peticion.getHeader(CABECERA_ORIGEN), 20);
        if (origen == null) {
            return;
        }

        JsonNode cuerpo = leer(peticion.getContentAsByteArray(), peticion.getContentType());
        JsonNode sobre = leer(respuesta.getContentAsByteArray(), respuesta.getContentType());
        JsonNode resultado = sobre == null ? null : sobre.path("result");
        String ruta = peticion.getRequestURI();
        AccionesIa.Clasificacion c = AccionesIa.clasificar(peticion.getMethod(), ruta, cuerpo);

        String entidad = c.entidadId();
        if (entidad == null && resultado != null && resultado.isObject() && resultado.hasNonNull("id")) {
            entidad = limpio(resultado.get("id").asText(), 40);
        }

        AccionIa accion = AccionIa.builder()
                .workspaceId(c.cuentaId() != null ? c.cuentaId() : usuario.getWorkspace().getId())
                .userId(usuario.getId())
                .userEmail(limpio(usuario.getEmail(), 160))
                .origen(origen)
                .cliente(limpio(peticion.getHeader(CABECERA_CLIENTE), 80))
                .accion(c.accion())
                .metodo(limpio(peticion.getMethod(), 8))
                .ruta(limpio(ruta, 300))
                .entidadId(entidad)
                .detalle(AccionesIa.detalle(cuerpo, resultado))
                .estado(estado)
                .build();
        repository.save(accion);
        log.info("Bitácora IA: {} {} por {} vía {}", accion.getAccion(), accion.getEntidadId(), accion.getUserEmail(),
                accion.getCliente());
    }

    /** Lo que las IA hicieron en una cuenta en los últimos {@code dias}. */
    @Transactional(readOnly = true)
    public List<AccionIa> listar(UUID workspaceId, int dias, int limite) {
        LocalDateTime desde = LocalDateTime.now().minusDays(Math.max(1, Math.min(dias, 365)));
        return repository.findByWorkspaceIdAndCreatedAtAfterOrderByCreatedAtDesc(workspaceId, desde,
                PageRequest.of(0, Math.max(1, Math.min(limite, 500))));
    }

    /** El rastro de una publicación o archivo. */
    @Transactional(readOnly = true)
    public List<AccionIa> de(UUID workspaceId, String entidadId) {
        return repository.findByWorkspaceIdAndEntidadIdOrderByCreatedAtDesc(workspaceId, entidadId);
    }

    private static User usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User u ? u : null;
    }

    private JsonNode leer(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0 || contentType == null || !contentType.contains("json")) {
            return null;
        }
        try {
            return json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String limpio(String v, int max) {
        if (v == null) {
            return null;
        }
        String t = v.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
