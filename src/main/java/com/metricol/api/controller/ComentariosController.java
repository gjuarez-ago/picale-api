package com.metricol.api.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.Comentario;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Platform;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.ComentarioResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.comentarios.ComentariosService;

/**
 * La bandeja de comentarios: lo que la gente dejó en las publicaciones del
 * espacio, y las respuestas.
 *
 * <p>Ver se puede siempre (quien está en el espacio ve lo que pasa en sus
 * cuentas). Contestar y ocultar piden {@link Permission#COMMENT_REPLY}: eso ya
 * no es mirar, es hablar en nombre del negocio.
 */
@RestController
@RequestMapping("/api/v1/comentarios")
public class ComentariosController {

    public record Respuesta(String texto) {
    }

    public record Leidos(List<UUID> ids) {
    }

    private final ComentariosService comentarios;
    private final PermissionService permisos;

    public ComentariosController(ComentariosService comentarios, PermissionService permisos) {
        this.comentarios = comentarios;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> bandeja(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "pendientes") String estado,
            @RequestParam(required = false) String red,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<Comentario> pagina = comentarios.bandeja(currentUser, !"todos".equalsIgnoreCase(estado),
                redDe(red), q, page, size);
        // De qué publicación es cada uno, de una sola consulta para la página.
        Map<UUID, ComentarioResponse.Publicacion> publicaciones =
                comentarios.publicacionesDe(pagina.getContent());
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("comentarios", pagina.getContent().stream()
                .map(c -> ComentarioResponse.de(c, publicaciones.get(c.getPostTargetId())))
                .toList());
        cuerpo.put("total", pagina.getTotalElements());
        cuerpo.put("hayMas", pagina.hasNext());
        return ResponseEntity.ok(ApiResponse.success(cuerpo));
    }

    /**
     * Solo los números, para el punto de la pestaña y la barra de arriba. Se
     * pide seguido, así que no trae ni un comentario.
     */
    @GetMapping("/resumen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumen(@AuthenticationPrincipal User currentUser) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("pendientes", comentarios.pendientes(currentUser));
        List<Map<String, Object>> porRed = new ArrayList<>();
        for (Object[] fila : comentarios.pendientesPorRed(currentUser)) {
            Platform p = (Platform) fila[0];
            porRed.add(Map.of("red", p.name(), "redNombre", p.getLabel(), "pendientes", fila[1]));
        }
        cuerpo.put("porRed", porRed);
        cuerpo.put("puedeResponder", permisos.puede(currentUser, Permission.COMMENT_REPLY));
        // Las que hay que volver a conectar para poder ver sus comentarios
        // (TikTok, YouTube). Van aquí para que la pantalla lo pueda decir en
        // vez de enseñar una bandeja vacía sin explicación.
        cuerpo.put("porReconectar", comentarios.redesPorReconectar(currentUser).stream()
                .map(p -> Map.of("red", p.name(), "redNombre", p.getLabel()))
                .toList());
        return ResponseEntity.ok(ApiResponse.success(cuerpo));
    }

    /** El comentario y lo que se le contestó. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<List<ComentarioResponse>>> hilo(@AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        List<Comentario> hilo = comentarios.hilo(currentUser, id);
        Map<UUID, ComentarioResponse.Publicacion> publicaciones = comentarios.publicacionesDe(hilo);
        return ResponseEntity.ok(ApiResponse.success(hilo.stream()
                .map(c -> ComentarioResponse.de(c, publicaciones.get(c.getPostTargetId())))
                .toList()));
    }

    @PostMapping("/{id}/responder")
    public ResponseEntity<ApiResponse<ComentarioResponse>> responder(@AuthenticationPrincipal User currentUser,
            @PathVariable UUID id, @RequestBody Respuesta cuerpo) {
        permisos.exigir(currentUser, Permission.COMMENT_REPLY);
        Comentario c = comentarios.responder(currentUser, id, cuerpo == null ? null : cuerpo.texto());
        return ResponseEntity.ok(ApiResponse.success(ComentarioResponse.de(c)));
    }

    /** Sale de pendientes sin contestar nada. */
    @PostMapping("/{id}/atender")
    public ResponseEntity<ApiResponse<ComentarioResponse>> atender(@AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.COMMENT_REPLY);
        return ResponseEntity.ok(ApiResponse.success(
                ComentarioResponse.de(comentarios.atender(currentUser, id))));
    }

    /** El "Deshacer" de la pantalla: vuelve a pendientes. */
    @PostMapping("/{id}/deshacer")
    public ResponseEntity<ApiResponse<ComentarioResponse>> deshacer(@AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.COMMENT_REPLY);
        return ResponseEntity.ok(ApiResponse.success(
                ComentarioResponse.de(comentarios.devolverAPendientes(currentUser, id))));
    }

    @PostMapping("/{id}/ocultar")
    public ResponseEntity<ApiResponse<ComentarioResponse>> ocultar(@AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.COMMENT_REPLY);
        return ResponseEntity.ok(ApiResponse.success(
                ComentarioResponse.de(comentarios.ocultar(currentUser, id))));
    }

    /** Al abrir la bandeja. No pide permiso: ver siempre se puede. */
    @PostMapping("/leidos")
    public ResponseEntity<ApiResponse<Map<String, Object>>> leidos(@AuthenticationPrincipal User currentUser,
            @RequestBody Leidos cuerpo) {
        int cuantos = comentarios.marcarLeidos(currentUser, cuerpo == null ? null : cuerpo.ids());
        return ResponseEntity.ok(ApiResponse.success(Map.of("marcados", cuantos)));
    }

    private static Platform redDe(String red) {
        if (red == null || red.isBlank()) {
            return null;
        }
        try {
            return Platform.valueOf(red.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null; // Una red que no manejamos: como si no se hubiera filtrado.
        }
    }
}
