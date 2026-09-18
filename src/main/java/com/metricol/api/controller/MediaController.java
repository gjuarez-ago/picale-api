package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MediaPresignResponse;
import com.metricol.api.models.response.StorageUsageResponse;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.PermissionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService service;
    private final PermissionService permisos;

    public MediaController(MediaService service, PermissionService permisos) {
        this.service = service;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MediaAssetResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    /**
     * Cuánto espacio lleva usado el workspace y cuáles son los topes.
     *
     * <p>La app lo consulta antes de dejar elegir archivos, para avisar cuando
     * queda poco en vez de dejar subir cinco fotos y fallar en la sexta.
     */
    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<StorageUsageResponse>> usage() {
        return ResponseEntity.ok(ApiResponse.success(service.usage()));
    }

    /**
     * Paso 1 de la subida directa: pide permiso y una URL para subir a R2.
     *
     * <p>Este es el camino de la app. El archivo no pasa por este servidor:
     * va del teléfono al bucket, y por aquí solo cruzan esta petición y la de
     * confirmar. Antes iba todo por multipart, y eso significaba el doble de
     * tráfico, un hilo retenido lo que durara la subida y un techo de 32 MiB
     * por petición en la plataforma, que tumbaba los videos grandes antes de
     * que la aplicación los viera.
     */
    @PostMapping("/presign")
    public ResponseEntity<ApiResponse<MediaPresignResponse>> presign(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody MediaPresignRequest request) {
        // Subir es parte de crear: quien no puede crear publicaciones tampoco
        // llena el espacio del cliente con archivos.
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(
                service.presign(request, currentUser.getWorkspace().getId())));
    }

    /**
     * Paso 2: avisa de que la subida terminó.
     *
     * <p>No es un trámite: aquí el servidor le pregunta a R2 cuánto pesa de
     * verdad el archivo y lo apunta. Hasta que esto pasa, el archivo no
     * aparece en la galería ni se puede publicar, porque nadie ha comprobado
     * que exista.
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> confirm(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(service.confirm(id)));
    }

    /**
     * Sube el archivo a través de la API, en una sola petición.
     *
     * <p>El camino del panel web: ahí el archivo ya está en un navegador de
     * escritorio con buena conexión, y tres pasos serían complicar lo simple.
     * La app no debería usar esto — ver la nota de {@code /presign}.
     */
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> upload(
            @AuthenticationPrincipal User currentUser,
            @RequestParam("file") MultipartFile file) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(
                service.upload(file, currentUser.getWorkspace().getId())));
    }

    /** Lo archivado, para devolverlo a la galería. */
    @GetMapping("/archived")
    public ResponseEntity<ApiResponse<List<MediaAssetResponse>>> archived() {
        return ResponseEntity.ok(ApiResponse.success(service.listArchived()));
    }

    /** Sale de Contenido, sigue en R2 y sigue contando en la cuota. Se puede devolver. */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> archive(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.MEDIA_DELETE);
        return ResponseEntity.ok(ApiResponse.success(service.archive(id, true)));
    }

    /** Lo devuelve a la galería. */
    @DeleteMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> unarchive(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.MEDIA_DELETE);
        return ResponseEntity.ok(ApiResponse.success(service.archive(id, false)));
    }

    /**
     * Ya no borra: archiva.
     *
     * <p>Nada se elimina físicamente. La ruta se deja por si una versión vieja
     * de la web o de la app la llama, pero hace lo mismo que
     * {@code POST /{id}/archive}: el archivo no se borra de R2 ni libera cuota.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.MEDIA_DELETE);
        service.archive(id, true);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
