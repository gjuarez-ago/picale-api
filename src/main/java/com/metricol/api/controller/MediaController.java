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
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MediaPresignResponse;
import com.metricol.api.models.response.StorageUsageResponse;
import com.metricol.api.service.MediaService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
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
    public ResponseEntity<ApiResponse<MediaAssetResponse>> confirm(@PathVariable UUID id) {
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
        return ResponseEntity.ok(ApiResponse.success(
                service.upload(file, currentUser.getWorkspace().getId())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
