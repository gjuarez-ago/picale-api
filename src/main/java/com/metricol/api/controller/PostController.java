package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.request.PostSaveRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.models.response.PostStatusResponse;
import com.metricol.api.service.PostService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService service;

    public PostController(PostService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PostResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    /**
     * Solo el estado de cada publicación. Lo sondea la app mientras algo sale.
     *
     * <p>Va antes de {@code /{id}} y con ruta literal a propósito: Spring
     * prefiere el segmento fijo sobre el comodín, así que "status" no se
     * intenta convertir a UUID.
     *
     * <p>Existe para que enterarse de que una publicación ya salió no cueste
     * lo que cuesta {@code GET /posts}, que arrastra medios y destinos —y una
     * consulta por destino para leer su cuenta—. Ver la nota de
     * {@code PostStatusResponse}.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<List<PostStatusResponse>>> statuses() {
        return ResponseEntity.ok(ApiResponse.success(service.statuses()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PostResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PostResponse>> create(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody PostSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.create(request, currentUser)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PostResponse>> update(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody PostSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request, currentUser)));
    }

    /**
     * Vuelve a intentar una publicacion que fallo.
     *
     * <p>Es lo que necesita el boton de "reintentar" de la app. Antes habia
     * que reenviar la publicacion entera con PUT para conseguir lo mismo, y
     * eso obligaba a la app a tener a mano todo su contenido solo para pedir
     * otro intento.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<PostResponse>> retry(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.retry(id, currentUser)));
    }

    /**
     * Archivar: la quita de las listas del dia a dia sin borrar nada.
     *
     * <p>Separado de {@code DELETE}, que si borra la fila y cancela lo que
     * quedara en la cola. Aqui no se pierde nada y se puede deshacer.
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<PostResponse>> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.archive(id, true)));
    }

    /** Devolverla a la vista. Es el "deshacer" del gesto de archivar. */
    @DeleteMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<PostResponse>> unarchive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.archive(id, false)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
